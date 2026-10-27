package id.controlengineer.modbus.protocol

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

open class ModbusIoException(message: String, cause: Throwable? = null) : IOException(message, cause)
class ModbusTimeoutException(message: String, cause: Throwable? = null) : ModbusIoException(message, cause)
class ModbusCrcException(
    val expectedCrc: Int,
    val receivedCrc: Int,
    val rawFrame: ByteArray,
) : ModbusIoException(
    String.format(
        Locale.ROOT,
        "CRC-16 tidak cocok: seharusnya 0x%04X, diterima 0x%04X (%s)",
        expectedCrc and 0xFFFF,
        receivedCrc and 0xFFFF,
        HexFormatUtil.toHexString(rawFrame),
    ),
)

enum class FrameDirection { TX, RX }

/** Entri log bingkai lalu lintas (TX/RX) untuk layar Monitor. */
data class FrameLogEntry(
    val timestampMillis: Long,
    val direction: FrameDirection,
    val hex: String,
    val unitId: Int,
    val functionCode: Int?,
    val isRtu: Boolean,
    val crcValid: Boolean? = null,
    val exceptionCode: Int? = null,
    val note: String = "",
) {
    val isError: Boolean
        get() = crcValid == false || exceptionCode != null
}

fun interface FrameLogger {
    fun onFrame(entry: FrameLogEntry)
}

/**
 * Kontrak transport Modbus (TCP maupun RTU serial) agar logika master/pemindai
 * dapat diuji dengan transport palsu maupun socket/serial nyata.
 */
interface Transport : Closeable {
    fun exchange(unitId: Int, request: ModbusRequest, timeoutMs: Int = 1000): ModbusResponse
    override fun close() {}
}

/**
 * Abstraksi port serial murni JVM agar RtuTransport dapat diuji tanpa ketergantungan Android USB.
 */
interface SerialPortEndpoint : Closeable {
    @Throws(IOException::class)
    fun write(data: ByteArray, timeoutMs: Int)

    @Throws(IOException::class)
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    @Throws(IOException::class)
    fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {}

    @Throws(IOException::class)
    fun setRts(enabled: Boolean) {}

    @Throws(IOException::class)
    fun purgeBuffers() {}

    override fun close() {}
}

/**
 * Transport Modbus TCP memakai java.net.Socket standar.
 */
class TcpSocketTransport(
    val host: String,
    val port: Int = 502,
    private val connectTimeoutMs: Int = 1500,
    private val frameLogger: FrameLogger? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : Transport {
    private val txIdCounter = AtomicInteger(0)
    private var socket: Socket? = null

    @Synchronized
    override fun exchange(unitId: Int, request: ModbusRequest, timeoutMs: Int): ModbusResponse {
        val txId = (txIdCounter.incrementAndGet()) and 0xFFFF
        val reqPdu = ModbusPduCodec.encodeRequest(request)
        val reqTcp = MbapCodec.encode(txId, unitId, reqPdu)

        frameLogger?.onFrame(
            FrameLogEntry(
                timestampMillis = clock(),
                direction = FrameDirection.TX,
                hex = HexFormatUtil.toHexString(reqTcp),
                unitId = unitId,
                functionCode = request.functionCode.code,
                isRtu = false,
                note = "TCP TX #${txId} ${request.functionCode.shortName}",
            ),
        )

        try {
            val sock = ensureConnected(timeoutMs)
            sock.soTimeout = timeoutMs
            val out = sock.getOutputStream()
            val input = sock.getInputStream()

            out.write(reqTcp)
            out.flush()

            val header = readExact(input, MbapCodec.HEADER_LENGTH)
            val rxTxId = ModbusPduCodec.getUInt16BE(header, 0)
            val rxProtoId = ModbusPduCodec.getUInt16BE(header, 2)
            val rxLength = ModbusPduCodec.getUInt16BE(header, 4)
            if (rxProtoId != MbapCodec.MODBUS_PROTOCOL_ID) {
                closeSocketQuietly()
                throw ModbusProtocolException("Protocol ID TCP bukan 0: 0x${rxProtoId.toString(16)}")
            }
            if (rxLength < 2 || rxLength > 254) {
                closeSocketQuietly()
                throw ModbusProtocolException("Panjang MBAP TCP tidak valid: $rxLength")
            }
            val remaining = readExact(input, rxLength - 1)
            val fullRx = ByteArray(MbapCodec.HEADER_LENGTH + remaining.size)
            header.copyInto(fullRx, 0)
            remaining.copyInto(fullRx, MbapCodec.HEADER_LENGTH)

            val rxFc = remaining[0].toInt() and 0xFF
            val exCode = if ((rxFc and 0x80) != 0 && remaining.size >= 2) remaining[1].toInt() and 0xFF else null

            frameLogger?.onFrame(
                FrameLogEntry(
                    timestampMillis = clock(),
                    direction = FrameDirection.RX,
                    hex = HexFormatUtil.toHexString(fullRx),
                    unitId = header[6].toInt() and 0xFF,
                    functionCode = rxFc,
                    isRtu = false,
                    exceptionCode = exCode,
                    note = if (exCode != null) {
                        ModbusExceptionCode.describeId(exCode)
                    } else {
                        "TCP RX #${rxTxId}"
                    },
                ),
            )

            if (rxTxId != txId) {
                closeSocketQuietly()
                throw ModbusProtocolException("Transaction ID tidak cocok: kirim=$txId, terima=$rxTxId")
            }
            val expectedCount = when (request) {
                is ModbusRequest.ReadCoils -> request.count
                is ModbusRequest.ReadDiscreteInputs -> request.count
                is ModbusRequest.ReadHoldingRegisters -> request.count
                is ModbusRequest.ReadInputRegisters -> request.count
                else -> null
            }
            return ModbusPduCodec.decodeResponse(remaining, expectedCount)
        } catch (e: SocketTimeoutException) {
            closeSocketQuietly()
            throw ModbusTimeoutException("Timeout membaca respons Modbus TCP dari $host:$port (${timeoutMs} ms)", e)
        } catch (e: ModbusProtocolException) {
            throw e
        } catch (e: ModbusIoException) {
            throw e
        } catch (e: IOException) {
            closeSocketQuietly()
            throw ModbusIoException("Gagal komunikasi TCP $host:$port: ${e.message}", e)
        }
    }

    private fun ensureConnected(timeoutMs: Int): Socket {
        val current = socket
        if (current != null && current.isConnected && !current.isClosed && !current.isInputShutdown && !current.isOutputShutdown) {
            return current
        }
        closeSocketQuietly()
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs.coerceAtMost(timeoutMs.coerceAtLeast(300)))
        socket = s
        return s
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n < 0) throw EOFException("Koneksi TCP ditutup oleh perangkat ($offset/$count byte)")
            offset += n
        }
        return buf
    }

    private fun closeSocketQuietly() {
        try {
            socket?.close()
        } catch (_: IOException) {
        } finally {
            socket = null
        }
    }

    @Synchronized
    override fun close() {
        closeSocketQuietly()
    }
}

/**
 * Transport Modbus RTU di atas SerialPortEndpoint:
 * - Menangani jeda hening antarframe 3,5 karakter (t3.5)
 * - Opsi toggle RTS untuk transceiver RS485 manual
 * - Verifikasi CRC-16/MODBUS dan deteksi frame exception / pendek
 */
class RtuTransport(
    private val endpoint: SerialPortEndpoint,
    var baudRate: Int = 9600,
    var bitsPerChar: Int = 11,
    var rtsToggleRs485: Boolean = false,
    private val frameLogger: FrameLogger? = null,
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    private val clock: () -> Long = System::currentTimeMillis,
) : Transport {

    @Synchronized
    override fun exchange(unitId: Int, request: ModbusRequest, timeoutMs: Int): ModbusResponse {
        val reqPdu = ModbusPduCodec.encodeRequest(request)
        val txFrame = RtuCodec.encode(unitId, reqPdu)

        // Jeda antarframe 3,5 karakter sebelum transmisi baru
        val t35Ms = RtuTiming.interFrameDelayMillis(baudRate, bitsPerChar)
        sleeper(t35Ms)

        try {
            endpoint.purgeBuffers()
        } catch (_: Exception) {
        }

        frameLogger?.onFrame(
            FrameLogEntry(
                timestampMillis = clock(),
                direction = FrameDirection.TX,
                hex = HexFormatUtil.toHexString(txFrame),
                unitId = unitId,
                functionCode = request.functionCode.code,
                isRtu = true,
                crcValid = true,
                note = "RTU TX Slave $unitId ${request.functionCode.shortName}",
            ),
        )

        try {
            if (rtsToggleRs485) {
                try {
                    endpoint.setRts(true)
                } catch (_: Exception) {
                }
            }
            endpoint.write(txFrame, timeoutMs)
            if (rtsToggleRs485) {
                val txDurationMs = ((txFrame.size * bitsPerChar * 1000L) / baudRate.coerceAtLeast(300)) + 2L
                sleeper(txDurationMs)
                try {
                    endpoint.setRts(false)
                } catch (_: Exception) {
                }
            }

            val expectedLen = RtuCodec.expectedResponseLength(request)
            val rxBuffer = ByteArray(256)
            var totalRead = 0
            val deadline = clock() + timeoutMs
            val chunkBuf = ByteArray(64)

            while (totalRead < expectedLen) {
                val remainingMs = (deadline - clock()).toInt()
                if (remainingMs <= 0) break
                val stepTimeout = if (totalRead == 0) {
                    remainingMs
                } else {
                    remainingMs.coerceAtMost(maxOf(t35Ms.toInt() * 3, 25))
                }
                val n = endpoint.read(chunkBuf, stepTimeout)
                if (n <= 0) {
                    if (totalRead > 0) break
                    continue
                }
                val copyLen = minOf(n, rxBuffer.size - totalRead)
                chunkBuf.copyInto(rxBuffer, destinationOffset = totalRead, startIndex = 0, endIndex = copyLen)
                totalRead += copyLen

                // Bila byte ke-2 memiliki bit 0x80 (Exception Response), panjang frame RTU adalah 5 byte.
                if (totalRead >= 5 && (rxBuffer[1].toInt() and 0x80) != 0) {
                    break
                }
            }

            if (totalRead == 0) {
                throw ModbusTimeoutException("Timeout RTU: tidak ada respons dari Slave $unitId dalam ${timeoutMs} ms")
            }

            val rawRx = rxBuffer.copyOfRange(0, totalRead)
            if (rawRx.size < 4) {
                frameLogger?.onFrame(
                    FrameLogEntry(
                        timestampMillis = clock(),
                        direction = FrameDirection.RX,
                        hex = HexFormatUtil.toHexString(rawRx),
                        unitId = rawRx[0].toInt() and 0xFF,
                        functionCode = if (rawRx.size >= 2) rawRx[1].toInt() and 0xFF else null,
                        isRtu = true,
                        crcValid = false,
                        note = "Frame RTU terpotong (${rawRx.size} byte)",
                    ),
                )
                throw ModbusTimeoutException("Frame RTU tidak lengkap (${rawRx.size} byte)")
            }

            val decodedFrame = RtuCodec.decode(rawRx)
            val rxFc = decodedFrame.pdu[0].toInt() and 0xFF
            val exCode = if (decodedFrame.crcValid && (rxFc and 0x80) != 0 && decodedFrame.pdu.size >= 2) {
                decodedFrame.pdu[1].toInt() and 0xFF
            } else {
                null
            }

            frameLogger?.onFrame(
                FrameLogEntry(
                    timestampMillis = clock(),
                    direction = FrameDirection.RX,
                    hex = HexFormatUtil.toHexString(rawRx),
                    unitId = decodedFrame.slaveId,
                    functionCode = rxFc,
                    isRtu = true,
                    crcValid = decodedFrame.crcValid,
                    exceptionCode = exCode,
                    note = when {
                        !decodedFrame.crcValid -> "CRC-16 SALAH"
                        exCode != null -> ModbusExceptionCode.describeId(exCode)
                        else -> "RTU RX Slave ${decodedFrame.slaveId} (CRC OK)"
                    },
                ),
            )

            if (!decodedFrame.crcValid) {
                val expectedCrc = ModbusCrc16.compute(rawRx, 0, rawRx.size - 2)
                throw ModbusCrcException(expectedCrc = expectedCrc, receivedCrc = decodedFrame.crc, rawFrame = rawRx)
            }

            if (decodedFrame.slaveId != unitId) {
                throw ModbusProtocolException("Slave ID respons (${decodedFrame.slaveId}) tidak cocok dengan permintaan ($unitId)")
            }

            val expectedCount = when (request) {
                is ModbusRequest.ReadCoils -> request.count
                is ModbusRequest.ReadDiscreteInputs -> request.count
                is ModbusRequest.ReadHoldingRegisters -> request.count
                is ModbusRequest.ReadInputRegisters -> request.count
                else -> null
            }
            return ModbusPduCodec.decodeResponse(decodedFrame.pdu, expectedCount)
        } catch (e: ModbusIoException) {
            throw e
        } catch (e: ModbusProtocolException) {
            throw e
        } catch (e: IOException) {
            throw ModbusIoException("Galat I/O serial RTU: ${e.message}", e)
        }
    }

    override fun close() {
        endpoint.close()
    }
}

/**
 * Simulator perangkat Slave Modbus dalam memori untuk pengujian unit, server TCP lokal, dan mode demo=1.
 * Secara bawaan mendukung Slave ID 1 dan 17, dengan nilai awal:
 * - Register 0..1: Float32 ABCD = 123.456f (0x42F6, 0xE979)
 * - Register 2..3: Float32 ABCD = 230.5f (0x4366, 0x8000)
 * - Register 4..5: Float32 ABCD = 50.0f (0x4248, 0x0000)
 * - Register 6..7: Float32 ABCD = -1.0f (0xBF80, 0x0000)
 * - Register 8: 13824 (50% dari 27648 skala analog Siemens)
 */
class ModbusSlaveSimulator(
    val activeSlaveIds: MutableSet<Int> = mutableSetOf(1, 17),
    val maxAddress: Int = 1000,
) {
    private val holdingRegisters = ConcurrentHashMap<Int, Int>()
    private val inputRegisters = ConcurrentHashMap<Int, Int>()
    private val coils = ConcurrentHashMap<Int, Boolean>()
    private val discreteInputs = ConcurrentHashMap<Int, Boolean>()
    var forcedExceptionCode: Int? = null

    init {
        resetDemoData()
    }

    fun resetDemoData() {
        holdingRegisters.clear()
        inputRegisters.clear()
        coils.clear()
        discreteInputs.clear()

        // 123.456f = 0x42F6E979
        val f123 = RegisterDecoder.encodeFloat32(123.456f, ByteOrder.ABCD)
        holdingRegisters[0] = f123[0]
        holdingRegisters[1] = f123[1]

        // 230.5f = 0x43668000 (tegangan V)
        val f230 = RegisterDecoder.encodeFloat32(230.5f, ByteOrder.ABCD)
        holdingRegisters[2] = f230[0]
        holdingRegisters[3] = f230[1]

        // 50.0f = 0x42480000 (frekuensi Hz)
        val f50 = RegisterDecoder.encodeFloat32(50.0f, ByteOrder.ABCD)
        holdingRegisters[4] = f50[0]
        holdingRegisters[5] = f50[1]

        // -1.0f = 0xBF800000
        val fNeg1 = RegisterDecoder.encodeFloat32(-1.0f, ByteOrder.ABCD)
        holdingRegisters[6] = fNeg1[0]
        holdingRegisters[7] = fNeg1[1]

        // Nilai raw analog 13824 = 50.0% dari 27648
        holdingRegisters[8] = 13824
        holdingRegisters[9] = 27648

        for (i in 0..19) {
            inputRegisters[i] = holdingRegisters[i] ?: (1000 + i * 10)
            coils[i] = (i % 2 == 0)
            discreteInputs[i] = (i % 3 == 0)
        }
    }

    fun setHoldingRegister(address: Int, value: Int) {
        holdingRegisters[address] = value and 0xFFFF
    }

    fun getHoldingRegister(address: Int): Int = holdingRegisters[address] ?: 0

    fun setCoil(address: Int, value: Boolean) {
        coils[address] = value
    }

    fun getCoil(address: Int): Boolean = coils[address] ?: false

    /**
     * Memproses PDU request untuk [unitId]; mengembalikan null bila [unitId] tidak aktif (simulasi timeout).
     */
    fun handlePdu(unitId: Int, requestPdu: ByteArray): ByteArray? {
        if (unitId !in activeSlaveIds) return null
        if (requestPdu.isEmpty()) return null
        val rawFc = requestPdu[0].toInt() and 0xFF

        forcedExceptionCode?.let { code ->
            return ModbusPduCodec.encodeResponse(ModbusResponse.Exception(rawFc, code))
        }

        val req = try {
            ModbusPduCodec.decodeRequest(requestPdu)
        } catch (_: ModbusProtocolException) {
            val knownFc = FunctionCode.fromCode(rawFc)
            val ex = if (knownFc == null) {
                ModbusExceptionCode.ILLEGAL_FUNCTION.code
            } else {
                ModbusExceptionCode.ILLEGAL_DATA_VALUE.code
            }
            return ModbusPduCodec.encodeResponse(ModbusResponse.Exception(rawFc, ex))
        }

        val response = when (req) {
            is ModbusRequest.ReadCoils -> {
                if (req.startAddress + req.count > maxAddress) {
                    ModbusResponse.Exception(rawFc, ModbusExceptionCode.ILLEGAL_DATA_ADDRESS.code)
                } else {
                    val bits = (0 until req.count).map { coils[req.startAddress + it] ?: false }
                    ModbusResponse.ReadBits(FunctionCode.FC01_READ_COILS, bits, ModbusPduCodec.packBits(bits))
                }
            }
            is ModbusRequest.ReadDiscreteInputs -> {
                if (req.startAddress + req.count > maxAddress) {
                    ModbusResponse.Exception(rawFc, ModbusExceptionCode.ILLEGAL_DATA_ADDRESS.code)
                } else {
                    val bits = (0 until req.count).map { discreteInputs[req.startAddress + it] ?: false }
                    ModbusResponse.ReadBits(FunctionCode.FC02_READ_DISCRETE_INPUTS, bits, ModbusPduCodec.packBits(bits))
                }
            }
            is ModbusRequest.ReadHoldingRegisters -> {
                if (req.startAddress + req.count > maxAddress) {
                    ModbusResponse.Exception(rawFc, ModbusExceptionCode.ILLEGAL_DATA_ADDRESS.code)
                } else {
                    val regs = (0 until req.count).map { holdingRegisters[req.startAddress + it] ?: 0 }
                    ModbusResponse.ReadRegisters(FunctionCode.FC03_READ_HOLDING_REGISTERS, regs)
                }
            }
            is ModbusRequest.ReadInputRegisters -> {
                if (req.startAddress + req.count > maxAddress) {
                    ModbusResponse.Exception(rawFc, ModbusExceptionCode.ILLEGAL_DATA_ADDRESS.code)
                } else {
                    val regs = (0 until req.count).map { inputRegisters[req.startAddress + it] ?: 0 }
                    ModbusResponse.ReadRegisters(FunctionCode.FC04_READ_INPUT_REGISTERS, regs)
                }
            }
            is ModbusRequest.WriteSingleCoil -> {
                if (req.startAddress >= maxAddress) {
                    ModbusResponse.Exception(rawFc, ModbusExceptionCode.ILLEGAL_DATA_ADDRESS.code)
                } else {
                    coils[req.startAddress] = req.value
                    ModbusResponse.WriteSingleCoil(req.startAddress, req.value)
                }
            }
            is ModbusRequest.WriteSingleRegister -> {
                if (req.startAddress >= maxAddress) {
                    ModbusResponse.Exception(rawFc, ModbusExceptionCode.ILLEGAL_DATA_ADDRESS.code)
                } else {
                    holdingRegisters[req.startAddress] = req.value and 0xFFFF
                    ModbusResponse.WriteSingleRegister(req.startAddress, req.value and 0xFFFF)
                }
            }
            is ModbusRequest.WriteMultipleCoils -> {
                if (req.startAddress + req.values.size > maxAddress) {
                    ModbusResponse.Exception(rawFc, ModbusExceptionCode.ILLEGAL_DATA_ADDRESS.code)
                } else {
                    req.values.forEachIndexed { idx, v -> coils[req.startAddress + idx] = v }
                    ModbusResponse.WriteMultipleCoils(req.startAddress, req.values.size)
                }
            }
            is ModbusRequest.WriteMultipleRegisters -> {
                if (req.startAddress + req.values.size > maxAddress) {
                    ModbusResponse.Exception(rawFc, ModbusExceptionCode.ILLEGAL_DATA_ADDRESS.code)
                } else {
                    req.values.forEachIndexed { idx, v -> holdingRegisters[req.startAddress + idx] = v and 0xFFFF }
                    ModbusResponse.WriteMultipleRegisters(req.startAddress, req.values.size)
                }
            }
        }
        return ModbusPduCodec.encodeResponse(response)
    }
}

/**
 * Transport tiruan dalam proses (untuk mode demo=1 & pengujian) yang melewati RtuTransport
 * di atas endpoint serial palsu sehingga frame RTU lengkap beserta CRC-16 tetap tercatat di Monitor.
 */
class DemoSerialEndpoint(
    val simulator: ModbusSlaveSimulator = ModbusSlaveSimulator(),
    var corruptNextCrc: Boolean = false,
    var dropNextResponse: Boolean = false,
) : SerialPortEndpoint {
    private var responseQueue = ByteArray(0)
    private var readOffset = 0

    override fun write(data: ByteArray, timeoutMs: Int) {
        if (dropNextResponse) {
            dropNextResponse = false
            responseQueue = ByteArray(0)
            readOffset = 0
            return
        }
        val reqFrame = RtuCodec.decode(data)
        if (!reqFrame.crcValid) {
            responseQueue = ByteArray(0)
            readOffset = 0
            return
        }
        val respPdu = simulator.handlePdu(reqFrame.slaveId, reqFrame.pdu)
        if (respPdu == null) {
            responseQueue = ByteArray(0)
            readOffset = 0
            return
        }
        val encoded = RtuCodec.encode(reqFrame.slaveId, respPdu)
        if (corruptNextCrc) {
            corruptNextCrc = false
            encoded[encoded.size - 1] = (encoded[encoded.size - 1].toInt() xor 0xFF).toByte()
        }
        responseQueue = encoded
        readOffset = 0
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val available = responseQueue.size - readOffset
        if (available <= 0) return 0
        val count = minOf(available, buffer.size)
        responseQueue.copyInto(buffer, destinationOffset = 0, startIndex = readOffset, endIndex = readOffset + count)
        readOffset += count
        return count
    }

    override fun purgeBuffers() {
        responseQueue = ByteArray(0)
        readOffset = 0
    }
}

/**
 * Server Modbus TCP tiruan lokal berbasis java.net.ServerSocket pada port acak (ephemeral)
 * untuk pengujian integrasi socket TCP nyata di unit test maupun demo.
 */
class LocalModbusTcpServer(
    val simulator: ModbusSlaveSimulator = ModbusSlaveSimulator(),
) : Closeable {
    private val serverSocket = ServerSocket(0)
    private val running = AtomicBoolean(true)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "local-modbus-tcp-server").apply { isDaemon = true }
    }

    val port: Int get() = serverSocket.localPort

    init {
        executor.execute { acceptLoop() }
    }

    private fun acceptLoop() {
        while (running.get() && !serverSocket.isClosed) {
            try {
                val client = serverSocket.accept()
                executor.execute { handleClient(client) }
            } catch (_: IOException) {
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            val input = sock.getInputStream()
            val out = sock.getOutputStream()
            val header = ByteArray(MbapCodec.HEADER_LENGTH)
            while (running.get() && !sock.isClosed) {
                try {
                    if (!readAllOrEof(input, header)) break
                    val txId = ModbusPduCodec.getUInt16BE(header, 0)
                    val protoId = ModbusPduCodec.getUInt16BE(header, 2)
                    val len = ModbusPduCodec.getUInt16BE(header, 4)
                    val unitId = header[6].toInt() and 0xFF
                    if (protoId != 0 || len < 2 || len > 254) break
                    val pdu = ByteArray(len - 1)
                    if (!readAllOrEof(input, pdu)) break
                    val respPdu = simulator.handlePdu(unitId, pdu)
                    if (respPdu != null) {
                        val respTcp = MbapCodec.encode(txId, unitId, respPdu)
                        out.write(respTcp)
                        out.flush()
                    }
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    private fun readAllOrEof(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    override fun close() {
        running.set(false)
        try {
            serverSocket.close()
        } catch (_: IOException) {
        }
        executor.shutdownNow()
    }
}
