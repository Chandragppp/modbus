package id.controlengineer.modbus

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import id.controlengineer.modbus.protocol.ConnectionMode
import id.controlengineer.modbus.protocol.ConnectionProfile
import id.controlengineer.modbus.protocol.DataSample
import id.controlengineer.modbus.protocol.DemoSerialEndpoint
import id.controlengineer.modbus.protocol.FrameLogEntry
import id.controlengineer.modbus.protocol.FunctionCode
import id.controlengineer.modbus.protocol.ModbusIoException
import id.controlengineer.modbus.protocol.ModbusRequest
import id.controlengineer.modbus.protocol.ModbusResponse
import id.controlengineer.modbus.protocol.RtuTransport
import id.controlengineer.modbus.protocol.ScanHit
import id.controlengineer.modbus.protocol.SerialParity
import id.controlengineer.modbus.protocol.SerialPortEndpoint
import id.controlengineer.modbus.protocol.TcpSocketTransport
import id.controlengineer.modbus.protocol.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

data class UsbAdapterInfo(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val driverName: String,
    val productName: String,
    val hasPermission: Boolean,
) {
    val vidPidHex: String
        get() = String.format(Locale.ROOT, "%04X:%04X", vendorId and 0xFFFF, productId and 0xFFFF)
}

/**
 * Implementasi SerialPortEndpoint di atas pustaka usb-serial-for-android (mik3y).
 */
class AndroidUsbSerialEndpoint(
    private val usbManager: UsbManager,
    private val driver: UsbSerialDriver,
    baudRate: Int,
    dataBits: Int,
    stopBits: Int,
    parity: SerialParity,
) : SerialPortEndpoint {
    private val port: UsbSerialPort = driver.ports.firstOrNull()
        ?: throw IOException("Adaptor USB tidak memiliki port serial")

    init {
        val connection = usbManager.openDevice(driver.device)
            ?: throw IOException("Gagal membuka perangkat USB (izin belum diberikan atau kabel terlepas)")
        port.open(connection)
        setParameters(baudRate, dataBits, stopBits, parity.code)
        try {
            port.dtr = true
        } catch (_: Exception) {
        }
    }

    override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
        val usbStopBits = when (stopBits) {
            2 -> UsbSerialPort.STOPBITS_2
            else -> UsbSerialPort.STOPBITS_1
        }
        val usbParity = when (parity) {
            SerialParity.ODD.code -> UsbSerialPort.PARITY_ODD
            SerialParity.EVEN.code -> UsbSerialPort.PARITY_EVEN
            else -> UsbSerialPort.PARITY_NONE
        }
        port.setParameters(baudRate, dataBits, usbStopBits, usbParity)
    }

    override fun write(data: ByteArray, timeoutMs: Int) {
        port.write(data, timeoutMs.coerceAtLeast(100))
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int =
        port.read(buffer, timeoutMs.coerceAtLeast(20))

    override fun setRts(enabled: Boolean) {
        try {
            port.rts = enabled
        } catch (_: Exception) {
        }
    }

    override fun purgeBuffers() {
        try {
            port.purgeHwBuffers(true, true)
        } catch (_: Exception) {
        }
    }

    override fun close() {
        try {
            port.close()
        } catch (_: IOException) {
        }
    }
}

object UsbSerialHelper {
    const val ACTION_USB_PERMISSION = "id.controlengineer.modbus.USB_PERMISSION"

    fun listAdapters(context: Context): List<UsbAdapterInfo> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        val drivers = try {
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        } catch (_: Throwable) {
            emptyList()
        }
        return drivers.map { drv ->
            val dev = drv.device
            val chip = drv.javaClass.simpleName
                .removeSuffix("SerialDriver")
                .removeSuffix("Driver")
                .ifEmpty { "USB-Serial" }
            UsbAdapterInfo(
                deviceId = dev.deviceId,
                vendorId = dev.vendorId,
                productId = dev.productId,
                driverName = chip,
                productName = dev.productName ?: dev.deviceName ?: chip,
                hasPermission = usbManager.hasPermission(dev),
            )
        }
    }

    fun requestPermission(context: Context, deviceId: Int, onResult: () -> Unit) {
        val appCtx = context.applicationContext
        val usbManager = appCtx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == deviceId } ?: return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                try {
                    appCtx.unregisterReceiver(this)
                } catch (_: Exception) {
                }
                onResult()
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            appCtx,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val explicitIntent = Intent(ACTION_USB_PERMISSION).setPackage(appCtx.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(appCtx, deviceId, explicitIntent, flags)
        usbManager.requestPermission(driver.device, pendingIntent)
    }

    fun openEndpoint(
        context: Context,
        deviceId: Int?,
        profile: ConnectionProfile,
    ): AndroidUsbSerialEndpoint {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw ModbusIoException("Layanan UsbManager tidak tersedia di perangkat ini")
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            throw ModbusIoException("Tidak ada adaptor USB-OTG RS485/RS232 yang terdeteksi")
        }
        val chosen = if (deviceId != null) {
            drivers.firstOrNull { it.device.deviceId == deviceId } ?: drivers.first()
        } else {
            drivers.first()
        }
        if (!usbManager.hasPermission(chosen.device)) {
            throw ModbusIoException("Izin USB belum diberikan untuk adaptor ${chosen.device.deviceName}")
        }
        return AndroidUsbSerialEndpoint(
            usbManager = usbManager,
            driver = chosen,
            baudRate = profile.baudRate,
            dataBits = profile.dataBits,
            stopBits = profile.stopBits,
            parity = profile.parity,
        )
    }
}

/**
 * Pengelola sesi komunikasi Modbus (TCP, USB RTU, maupun Demo dalam proses),
 * daftar frame Monitor, sampel log CSV/Tren, dan Pemindai (Scanner).
 */
class ModbusSessionController(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val demoEndpoint = DemoSerialEndpoint()

    private val _profile = MutableStateFlow(ConnectionProfile(name = "Default PLC"))
    val profile: StateFlow<ConnectionProfile> = _profile.asStateFlow()

    private val _demoMode = MutableStateFlow(false)
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    private val _usbAdapters = MutableStateFlow<List<UsbAdapterInfo>>(emptyList())
    val usbAdapters: StateFlow<List<UsbAdapterInfo>> = _usbAdapters.asStateFlow()

    private val _selectedUsbDeviceId = MutableStateFlow<Int?>(null)
    val selectedUsbDeviceId: StateFlow<Int?> = _selectedUsbDeviceId.asStateFlow()

    private val _frameLog = MutableStateFlow<List<FrameLogEntry>>(emptyList())
    val frameLog: StateFlow<List<FrameLogEntry>> = _frameLog.asStateFlow()

    private val _dataSamples = MutableStateFlow<List<DataSample>>(emptyList())
    val dataSamples: StateFlow<List<DataSample>> = _dataSamples.asStateFlow()

    private val _lastRegisters = MutableStateFlow<List<Int>>(emptyList())
    val lastRegisters: StateFlow<List<Int>> = _lastRegisters.asStateFlow()

    private val _lastBits = MutableStateFlow<List<Boolean>>(emptyList())
    val lastBits: StateFlow<List<Boolean>> = _lastBits.asStateFlow()

    private val _lastStartAddress = MutableStateFlow(0)
    val lastStartAddress: StateFlow<Int> = _lastStartAddress.asStateFlow()

    private val _lastFunctionCode = MutableStateFlow(FunctionCode.FC03_READ_HOLDING_REGISTERS)
    val lastFunctionCode: StateFlow<FunctionCode> = _lastFunctionCode.asStateFlow()

    private val _lastUnitId = MutableStateFlow(1)
    val lastUnitId: StateFlow<Int> = _lastUnitId.asStateFlow()

    private val _lastReadTimestampMillis = MutableStateFlow(0L)
    val lastReadTimestampMillis: StateFlow<Long> = _lastReadTimestampMillis.asStateFlow()

    private val _lastPollIntervalMs = MutableStateFlow(1000)
    val lastPollIntervalMs: StateFlow<Int> = _lastPollIntervalMs.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isError = MutableStateFlow(false)
    val isError: StateFlow<Boolean> = _isError.asStateFlow()

    private val _scanHits = MutableStateFlow<List<ScanHit>>(emptyList())
    val scanHits: StateFlow<List<ScanHit>> = _scanHits.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanCurrentId = MutableStateFlow(1)
    val scanCurrentId: StateFlow<Int> = _scanCurrentId.asStateFlow()

    private val _scanDoneSteps = MutableStateFlow(0)
    val scanDoneSteps: StateFlow<Int> = _scanDoneSteps.asStateFlow()

    private val _scanTotalSteps = MutableStateFlow(1)
    val scanTotalSteps: StateFlow<Int> = _scanTotalSteps.asStateFlow()

    private val _scanStartId = MutableStateFlow(1)
    val scanStartId: StateFlow<Int> = _scanStartId.asStateFlow()

    private val _scanEndId = MutableStateFlow(25)
    val scanEndId: StateFlow<Int> = _scanEndId.asStateFlow()

    private val _scanBaudSummary = MutableStateFlow("")
    val scanBaudSummary: StateFlow<String> = _scanBaudSummary.asStateFlow()

    private val _scanTimestampMillis = MutableStateFlow(0L)
    val scanTimestampMillis: StateFlow<Long> = _scanTimestampMillis.asStateFlow()

    private var scanJob: Job? = null

    fun updateProfile(newProfile: ConnectionProfile) {
        _profile.value = newProfile
    }

    fun updatePollIntervalMs(intervalMs: Int) {
        _lastPollIntervalMs.value = intervalMs
    }

    fun selectUsbDevice(deviceId: Int?) {
        _selectedUsbDeviceId.value = deviceId
    }

    fun refreshUsbAdapters() {
        val list = UsbSerialHelper.listAdapters(appContext)
        _usbAdapters.value = list
        if (_selectedUsbDeviceId.value == null && list.isNotEmpty()) {
            _selectedUsbDeviceId.value = list.first().deviceId
        }
    }

    fun requestUsbPermission(deviceId: Int) {
        UsbSerialHelper.requestPermission(appContext, deviceId) {
            refreshUsbAdapters()
        }
    }

    fun setDemoMode(enabled: Boolean, seedRead: Boolean = false) {
        _demoMode.value = enabled
        if (enabled) {
            demoEndpoint.simulator.resetDemoData()
            if (seedRead) {
                // Jalankan pembacaan 2 register awal secara sinkron pada simulator dalam proses
                // agar layar Baca/Tulis dan Monitor langsung berisi data saat dibuka lewat intent demo=1.
                val demoTransport = RtuTransport(
                    endpoint = demoEndpoint,
                    baudRate = _profile.value.baudRate,
                    frameLogger = { appendFrame(it) },
                    sleeper = {},
                )
                try {
                    val req = ModbusRequest.ReadHoldingRegisters(startAddress = 0, count = 2)
                    val resp = demoTransport.exchange(unitId = 1, request = req, timeoutMs = 200)
                    handleResponseSuccess(req, resp, unitId = 1)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun appendFrame(entry: FrameLogEntry) {
        val current = _frameLog.value
        val updated = (listOf(entry) + current).take(200)
        _frameLog.value = updated
    }

    fun clearFrameLog() {
        _frameLog.value = emptyList()
    }

    fun clearDataSamples() {
        _dataSamples.value = emptyList()
    }

    private fun buildTransport(activeProfile: ConnectionProfile): Transport {
        if (_demoMode.value) {
            return RtuTransport(
                endpoint = demoEndpoint,
                baudRate = activeProfile.baudRate,
                bitsPerChar = 11,
                rtsToggleRs485 = activeProfile.rtsRs485,
                frameLogger = { appendFrame(it) },
                sleeper = {},
            )
        }
        return when (activeProfile.mode) {
            ConnectionMode.TCP -> TcpSocketTransport(
                host = activeProfile.host,
                port = activeProfile.port,
                connectTimeoutMs = activeProfile.timeoutMs,
                frameLogger = { appendFrame(it) },
            )
            ConnectionMode.RTU_USB -> {
                val endpoint = UsbSerialHelper.openEndpoint(appContext, _selectedUsbDeviceId.value, activeProfile)
                RtuTransport(
                    endpoint = endpoint,
                    baudRate = activeProfile.baudRate,
                    bitsPerChar = if (activeProfile.parity == SerialParity.NONE && activeProfile.stopBits == 1) 10 else 11,
                    rtsToggleRs485 = activeProfile.rtsRs485,
                    frameLogger = { appendFrame(it) },
                )
            }
        }
    }

    suspend fun performRequest(unitId: Int, request: ModbusRequest): Result<ModbusResponse> =
        withContext(Dispatchers.IO) {
            val activeProfile = _profile.value
            try {
                buildTransport(activeProfile).use { transport ->
                    val resp = transport.exchange(unitId, request, activeProfile.timeoutMs)
                    handleResponseSuccess(request, resp, unitId)
                    Result.success(resp)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _isError.value = true
                _statusText.value = e.message ?: "Komunikasi Modbus gagal"
                Result.failure(e)
            }
        }

    private fun handleResponseSuccess(
        request: ModbusRequest,
        resp: ModbusResponse,
        unitId: Int = _profile.value.unitId,
    ) {
        val now = System.currentTimeMillis()
        _lastStartAddress.value = request.startAddress
        _lastFunctionCode.value = request.functionCode
        _lastUnitId.value = unitId
        when (resp) {
            is ModbusResponse.ReadRegisters -> {
                _isError.value = false
                _lastRegisters.value = resp.registers
                _lastBits.value = emptyList()
                _lastReadTimestampMillis.value = now
                _statusText.value = "OK (${resp.registers.size} register)"
                val sample = DataSample(
                    timestampMillis = now,
                    startAddress = request.startAddress,
                    registers = resp.registers,
                )
                _dataSamples.value = (_dataSamples.value + sample).takeLast(500)
            }
            is ModbusResponse.ReadBits -> {
                _isError.value = false
                _lastBits.value = resp.bits
                _lastRegisters.value = emptyList()
                _lastReadTimestampMillis.value = now
                _statusText.value = "OK (${resp.bits.size} bit)"
            }
            is ModbusResponse.WriteSingleCoil -> {
                _isError.value = false
                _statusText.value = "FC05 OK: Coil ${resp.address} = ${if (resp.value) "ON" else "OFF"}"
            }
            is ModbusResponse.WriteSingleRegister -> {
                _isError.value = false
                _statusText.value = "FC06 OK: Reg ${resp.address} = ${resp.value}"
            }
            is ModbusResponse.WriteMultipleCoils -> {
                _isError.value = false
                _statusText.value = "FC15 OK: ${resp.count} coil @ ${resp.startAddress}"
            }
            is ModbusResponse.WriteMultipleRegisters -> {
                _isError.value = false
                _statusText.value = "FC16 OK: ${resp.count} register @ ${resp.startAddress}"
            }
            is ModbusResponse.Exception -> {
                _isError.value = true
                _statusText.value = resp.messageId
            }
        }
    }

    fun startScan(
        startId: Int,
        endId: Int,
        scanBaudCombos: Boolean,
    ) {
        stopScan()
        val firstId = startId.coerceIn(1, 247)
        val lastId = endId.coerceIn(firstId, 247)
        val activeProfile = _profile.value
        val combos: List<Pair<Int, SerialParity>> = if (activeProfile.mode == ConnectionMode.RTU_USB && scanBaudCombos) {
            listOf(
                9600 to SerialParity.NONE,
                9600 to SerialParity.EVEN,
                19200 to SerialParity.NONE,
                19200 to SerialParity.EVEN,
            )
        } else {
            listOf(activeProfile.baudRate to activeProfile.parity)
        }

        val total = (lastId - firstId + 1) * combos.size
        _scanStartId.value = firstId
        _scanEndId.value = lastId
        _scanBaudSummary.value = combos.joinToString(", ") {
            "${it.first} ${activeProfile.dataBits}${it.second.shortLabel}${activeProfile.stopBits}"
        }
        _scanTimestampMillis.value = System.currentTimeMillis()
        _scanHits.value = emptyList()
        _scanDoneSteps.value = 0
        _scanTotalSteps.value = total.coerceAtLeast(1)
        _isScanning.value = true

        scanJob = scope.launch(Dispatchers.IO) {
            var step = 0
            try {
                for ((baud, parity) in combos) {
                    val scanProf = activeProfile.copy(baudRate = baud, parity = parity)
                    for (id in firstId..lastId) {
                        if (!isActive) break
                        step++
                        _scanCurrentId.value = id
                        _scanDoneSteps.value = step
                        try {
                            val t0 = System.currentTimeMillis()
                            buildTransport(scanProf).use { transport ->
                                val req = ModbusRequest.ReadHoldingRegisters(startAddress = 0, count = 1)
                                val resp = transport.exchange(
                                    unitId = id,
                                    request = req,
                                    timeoutMs = if (_demoMode.value) 50 else activeProfile.timeoutMs.coerceIn(120, 600),
                                )
                                val elapsedMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                                val detail = when (resp) {
                                    is ModbusResponse.ReadRegisters ->
                                        "Reg[0] = ${resp.registers.firstOrNull() ?: 0} (0x${String.format(Locale.ROOT, "%04X", resp.registers.firstOrNull() ?: 0)})"
                                    is ModbusResponse.Exception ->
                                        "Merespons Exception 0x${String.format(Locale.ROOT, "%02X", resp.exceptionCode)}"
                                    else -> "Merespons"
                                }
                                val hit = ScanHit(
                                    unitId = id,
                                    baudRate = if (activeProfile.mode == ConnectionMode.RTU_USB) baud else null,
                                    parity = if (activeProfile.mode == ConnectionMode.RTU_USB) parity else null,
                                    detail = detail,
                                    responseTimeMs = elapsedMs,
                                )
                                _scanHits.value = _scanHits.value + hit
                            }
                        } catch (_: ModbusIoException) {
                            // Timeout / tidak ada slave pada ID ini, lanjut ke ID berikutnya.
                        } catch (_: Throwable) {
                        }
                    }
                }
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }
}
