package id.controlengineer.modbus

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.controlengineer.modbus.protocol.AddressNotation
import id.controlengineer.modbus.protocol.ByteOrder
import id.controlengineer.modbus.protocol.ConnectionMode
import id.controlengineer.modbus.protocol.ConnectionProfile
import id.controlengineer.modbus.protocol.CsvExporter
import id.controlengineer.modbus.protocol.FrameDirection
import id.controlengineer.modbus.protocol.FunctionCode
import id.controlengineer.modbus.protocol.HexFormatUtil
import id.controlengineer.modbus.protocol.LinearScale
import id.controlengineer.modbus.protocol.ModbusAddress
import id.controlengineer.modbus.protocol.ModbusPduCodec
import id.controlengineer.modbus.protocol.ModbusRequest
import id.controlengineer.modbus.protocol.RegisterDecoder
import id.controlengineer.modbus.protocol.RtuCodec
import id.controlengineer.modbus.protocol.SerialParity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ConnectionScreen(
    session: ModbusSessionController,
    isPro: Boolean,
    savedProfiles: List<ConnectionProfile>,
    onSaveProfiles: (List<ConnectionProfile>) -> Unit,
    onRequestPro: () -> Unit,
) {
    val profile by session.profile.collectAsState()
    val demoMode by session.demoMode.collectAsState()
    val usbAdapters by session.usbAdapters.collectAsState()
    val selectedUsbId by session.selectedUsbDeviceId.collectAsState()

    var hostText by rememberSaveable(profile.host) { mutableStateOf(profile.host) }
    var portText by rememberSaveable(profile.port) { mutableStateOf(profile.port.toString()) }
    var unitIdText by rememberSaveable(profile.unitId) { mutableStateOf(profile.unitId.toString()) }
    var timeoutText by rememberSaveable(profile.timeoutMs) { mutableStateOf(profile.timeoutMs.toString()) }
    var profileName by rememberSaveable { mutableStateOf(profile.name) }

    LaunchedEffect(Unit) {
        session.refreshUsbAdapters()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen_connection"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (demoMode) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().testTag("demo_banner"),
            ) {
                Text(
                    stringResource(R.string.conn_demo_active),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.conn_mode_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = profile.mode == ConnectionMode.TCP,
                        onClick = { session.updateProfile(profile.copy(mode = ConnectionMode.TCP)) },
                        label = { Text(stringResource(R.string.conn_mode_tcp)) },
                        modifier = Modifier.testTag("conn_mode_tcp"),
                    )
                    FilterChip(
                        selected = profile.mode == ConnectionMode.RTU_USB,
                        onClick = { session.updateProfile(profile.copy(mode = ConnectionMode.RTU_USB)) },
                        label = { Text(stringResource(R.string.conn_mode_rtu)) },
                        modifier = Modifier.testTag("conn_mode_rtu"),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DecimalField(
                        value = unitIdText,
                        onChange = {
                            unitIdText = it
                            it.toIntOrNull()?.coerceIn(0, 247)?.let { id ->
                                session.updateProfile(profile.copy(unitId = id))
                            }
                        },
                        label = stringResource(R.string.unit_slave_id),
                        modifier = Modifier.weight(1f).testTag("input_unit_id"),
                    )
                    DecimalField(
                        value = timeoutText,
                        onChange = {
                            timeoutText = it
                            it.toIntOrNull()?.coerceIn(100, 30000)?.let { ms ->
                                session.updateProfile(profile.copy(timeoutMs = ms))
                            }
                        },
                        label = stringResource(R.string.timeout_ms),
                        modifier = Modifier.weight(1f).testTag("input_timeout"),
                    )
                }

                if (profile.mode == ConnectionMode.TCP) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        DecimalField(
                            value = hostText,
                            onChange = {
                                hostText = it
                                session.updateProfile(profile.copy(host = it.trim()))
                            },
                            label = stringResource(R.string.tcp_host),
                            modifier = Modifier.weight(2f).testTag("input_tcp_host"),
                        )
                        DecimalField(
                            value = portText,
                            onChange = {
                                portText = it
                                it.toIntOrNull()?.coerceIn(1, 65535)?.let { p ->
                                    session.updateProfile(profile.copy(port = p))
                                }
                            },
                            label = stringResource(R.string.tcp_port),
                            modifier = Modifier.weight(1f).testTag("input_tcp_port"),
                        )
                    }
                } else {
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.usb_devices_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedButton(
                            onClick = { session.refreshUsbAdapters() },
                            modifier = Modifier.testTag("btn_usb_refresh"),
                        ) {
                            Text(stringResource(R.string.usb_refresh))
                        }
                    }

                    if (usbAdapters.isEmpty()) {
                        Text(
                            stringResource(R.string.usb_no_devices),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("usb_empty_text"),
                        )
                    } else {
                        for (adapter in usbAdapters) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedUsbId == adapter.deviceId) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { session.selectUsbDevice(adapter.deviceId) },
                            ) {
                                Row(
                                    Modifier.padding(10.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${adapter.driverName} (${adapter.vidPidHex})",
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        Text(
                                            if (adapter.hasPermission) {
                                                stringResource(R.string.usb_permission_granted)
                                            } else {
                                                stringResource(R.string.usb_permission_needed)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (!adapter.hasPermission) {
                                        OutlinedButton(onClick = { session.requestUsbPermission(adapter.deviceId) }) {
                                            Text(stringResource(R.string.usb_request_permission))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Text(stringResource(R.string.serial_baud), style = MaterialTheme.typography.labelLarge)
                    ChipRow(
                        options = ConnectionProfile.STANDARD_BAUD_RATES,
                        selected = profile.baudRate,
                        label = { it.toString() },
                        onSelect = { session.updateProfile(profile.copy(baudRate = it)) },
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.serial_data_bits), style = MaterialTheme.typography.labelLarge)
                            ChipRow(
                                options = listOf(8, 7),
                                selected = profile.dataBits,
                                label = { it.toString() },
                                onSelect = { session.updateProfile(profile.copy(dataBits = it)) },
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.serial_stop_bits), style = MaterialTheme.typography.labelLarge)
                            ChipRow(
                                options = listOf(1, 2),
                                selected = profile.stopBits,
                                label = { it.toString() },
                                onSelect = { session.updateProfile(profile.copy(stopBits = it)) },
                            )
                        }
                    }

                    Text(stringResource(R.string.serial_parity), style = MaterialTheme.typography.labelLarge)
                    ChipRow(
                        options = SerialParity.entries,
                        selected = profile.parity,
                        label = { "${it.shortLabel} (${it.name})" },
                        onSelect = { session.updateProfile(profile.copy(parity = it)) },
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.serial_rts_rs485), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = profile.rtsRs485,
                            onCheckedChange = { session.updateProfile(profile.copy(rtsRs485 = it)) },
                            modifier = Modifier.testTag("switch_rts_rs485"),
                        )
                    }
                }
            }
        }

        // Profil Koneksi Tersimpan (Gratis: maks 1, Pro: tak terbatas)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Judul dan petunjuk batas profil ditumpuk agar tidak berdesakan di layar sempit
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.profiles_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.profile_limit_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DecimalField(
                        value = profileName,
                        onChange = { profileName = it },
                        label = stringResource(R.string.profile_name),
                        modifier = Modifier.weight(1f).testTag("input_profile_name"),
                    )
                    Button(
                        onClick = {
                            val cleanName = profileName.trim().ifEmpty { "PLC" }
                            val toSave = profile.copy(name = cleanName)
                            val existingIdx = savedProfiles.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
                            if (!isPro && existingIdx < 0 && savedProfiles.size >= ConnectionProfile.FREE_LIMIT) {
                                onRequestPro()
                            } else {
                                val updated = if (existingIdx >= 0) {
                                    savedProfiles.toMutableList().also { it[existingIdx] = toSave }
                                } else {
                                    savedProfiles + toSave
                                }
                                onSaveProfiles(updated)
                            }
                        },
                        modifier = Modifier.testTag("btn_save_profile"),
                    ) {
                        Text(stringResource(R.string.profile_save))
                    }
                }

                for ((idx, saved) in savedProfiles.withIndex()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                profileName = saved.name
                                session.updateProfile(saved)
                            }
                            .padding(vertical = 6.dp)
                            .testTag("saved_profile_$idx"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val summary = if (saved.mode == ConnectionMode.TCP) {
                            "${saved.name} · TCP ${saved.host}:${saved.port} (ID ${saved.unitId})"
                        } else {
                            "${saved.name} · RTU ${saved.baudRate} 8${saved.parity.shortLabel}${saved.stopBits} (ID ${saved.unitId})"
                        }
                        Text(summary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onSaveProfiles(savedProfiles.filterIndexed { i, _ -> i != idx }) }) {
                            Text("×")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadWriteScreen(session: ModbusSessionController) {
    val profile by session.profile.collectAsState()
    val lastRegs by session.lastRegisters.collectAsState()
    val lastBits by session.lastBits.collectAsState()
    val lastStartAddr by session.lastStartAddress.collectAsState()
    val statusText by session.statusText.collectAsState()
    val isError by session.isError.collectAsState()

    val scope = rememberCoroutineScope()
    var selectedFc by rememberSaveable { mutableStateOf(FunctionCode.FC03_READ_HOLDING_REGISTERS) }
    var notation by rememberSaveable { mutableStateOf(AddressNotation.AUTO) }
    var addressInput by rememberSaveable { mutableStateOf("0") }
    var countInput by rememberSaveable { mutableStateOf("2") }
    var slaveInput by rememberSaveable(profile.unitId) { mutableStateOf(profile.unitId.toString()) }

    var isPolling by rememberSaveable { mutableStateOf(false) }
    var pollIntervalMs by rememberSaveable { mutableIntStateOf(1000) }

    var scalingEnabled by rememberSaveable { mutableStateOf(false) }
    var rawMinText by rememberSaveable { mutableStateOf("0") }
    var rawMaxText by rememberSaveable { mutableStateOf("27648") }
    var engMinText by rememberSaveable { mutableStateOf("0") }
    var engMaxText by rememberSaveable { mutableStateOf("100") }
    var engUnitText by rememberSaveable { mutableStateOf("bar") }

    var writeInput by rememberSaveable { mutableStateOf("100") }
    var writeFloatMode by rememberSaveable { mutableStateOf(false) }
    var writeByteOrder by rememberSaveable { mutableStateOf(ByteOrder.ABCD) }
    var pendingWriteRequest by remember { mutableStateOf<Pair<Int, ModbusRequest>?>(null) }

    val resolvedAddr = remember(addressInput, notation, selectedFc) {
        ModbusAddress.resolve(addressInput, notation, selectedFc)
    }

    val effectiveFc = remember(selectedFc, resolvedAddr) {
        if (notation != AddressNotation.ZERO_BASED && resolvedAddr?.isModicon == true && !selectedFc.isWrite) {
            resolvedAddr.suggestedFunctionCode ?: selectedFc
        } else {
            selectedFc
        }
    }

    val activeScale = remember(scalingEnabled, rawMinText, rawMaxText, engMinText, engMaxText, engUnitText) {
        if (!scalingEnabled) return@remember null
        val rMin = parseDecimal(rawMinText) ?: return@remember null
        val rMax = parseDecimal(rawMaxText) ?: return@remember null
        val eMin = parseDecimal(engMinText) ?: return@remember null
        val eMax = parseDecimal(engMaxText) ?: return@remember null
        if (rMin == rMax || eMin == eMax) return@remember null
        LinearScale(rMin, rMax, eMin, eMax, engUnitText.trim())
    }

    fun buildReadRequest(): Pair<Int, ModbusRequest>? {
        val addr = resolvedAddr?.protocolAddress ?: return null
        val qty = countInput.trim().toIntOrNull()?.coerceIn(1, 125) ?: return null
        val uid = slaveInput.trim().toIntOrNull()?.coerceIn(0, 247) ?: profile.unitId
        val req = when (effectiveFc) {
            FunctionCode.FC01_READ_COILS,
            FunctionCode.FC05_WRITE_SINGLE_COIL,
            FunctionCode.FC15_WRITE_MULTIPLE_COILS -> ModbusRequest.ReadCoils(addr, qty)
            FunctionCode.FC02_READ_DISCRETE_INPUTS -> ModbusRequest.ReadDiscreteInputs(addr, qty)
            FunctionCode.FC04_READ_INPUT_REGISTERS -> ModbusRequest.ReadInputRegisters(addr, qty)
            FunctionCode.FC03_READ_HOLDING_REGISTERS,
            FunctionCode.FC06_WRITE_SINGLE_REGISTER,
            FunctionCode.FC16_WRITE_MULTIPLE_REGISTERS -> ModbusRequest.ReadHoldingRegisters(addr, qty)
        }
        return uid to req
    }

    DisposableEffect(Unit) {
        onDispose { isPolling = false }
    }

    LaunchedEffect(isPolling, pollIntervalMs, addressInput, countInput, slaveInput, effectiveFc) {
        if (!isPolling) return@LaunchedEffect
        while (isActive && isPolling) {
            val pair = buildReadRequest()
            if (pair != null) {
                session.performRequest(pair.first, pair.second)
            }
            delay(pollIntervalMs.toLong().coerceIn(250L, 10_000L))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen_rw"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.rw_function_code),
                    style = MaterialTheme.typography.labelLarge,
                )
                ChipRow(
                    options = FunctionCode.entries,
                    selected = selectedFc,
                    label = { String.format(Locale.ROOT, "FC%02d", it.code) },
                    onSelect = { selectedFc = it },
                )

                Text(stringResource(R.string.rw_address_notation), style = MaterialTheme.typography.labelLarge)
                ChipRow(
                    options = AddressNotation.entries,
                    selected = notation,
                    label = { it.name },
                    onSelect = { notation = it },
                )

                DecimalField(
                    value = addressInput,
                    onChange = { addressInput = it },
                    label = stringResource(R.string.rw_address_input),
                    isError = resolvedAddr == null,
                    modifier = Modifier.fillMaxWidth().testTag("input_rw_address"),
                )

                if (resolvedAddr != null) {
                    val previewText = stringResource(
                        R.string.rw_address_preview_fmt,
                        effectiveFc.code,
                        resolvedAddr.protocolAddress,
                        resolvedAddr.hexAddress,
                        resolvedAddr.modiconEquivalent,
                    )
                    Text(
                        previewText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("rw_address_preview"),
                    )
                    val sampleReq = buildReadRequest()
                    if (sampleReq != null) {
                        val rawFrame = RtuCodec.encode(sampleReq.first, ModbusPduCodec.encodeRequest(sampleReq.second))
                        Text(
                            "RTU Hex: ${HexFormatUtil.toHexString(rawFrame)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("rw_raw_frame_preview"),
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.rw_address_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("rw_address_error"),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DecimalField(
                        value = countInput,
                        onChange = { countInput = it },
                        label = stringResource(R.string.rw_quantity),
                        modifier = Modifier.weight(1f).testTag("input_rw_quantity"),
                    )
                    DecimalField(
                        value = slaveInput,
                        onChange = { slaveInput = it },
                        label = stringResource(R.string.unit_slave_id),
                        modifier = Modifier.weight(1f).testTag("input_rw_slave"),
                    )
                }

                Text(stringResource(R.string.rw_poll_interval), style = MaterialTheme.typography.labelLarge)
                ChipRow(
                    options = listOf(250, 500, 1000, 2000, 5000, 10000),
                    selected = pollIntervalMs,
                    label = { if (it < 1000) "${it}ms" else "${it / 1000}s" },
                    onSelect = { pollIntervalMs = it },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val pair = buildReadRequest()
                            if (pair != null) {
                                scope.launch { session.performRequest(pair.first, pair.second) }
                            }
                        },
                        enabled = resolvedAddr != null,
                        modifier = Modifier.weight(1f).testTag("btn_read_once"),
                    ) {
                        Text(stringResource(R.string.rw_read_once))
                    }
                    OutlinedButton(
                        onClick = { isPolling = !isPolling },
                        enabled = resolvedAddr != null,
                        modifier = Modifier.weight(1f).testTag("btn_poll_toggle"),
                    ) {
                        Text(
                            if (isPolling) stringResource(R.string.rw_poll_stop) else stringResource(R.string.rw_poll_start),
                        )
                    }
                }

                val displayStatus = statusText.ifBlank { stringResource(R.string.rw_status_idle) }
                Text(
                    displayStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("rw_status_text"),
                )
            }
        }

        // Opsi Skala Linear Analog
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.rw_scaling_toggle),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Switch(
                        checked = scalingEnabled,
                        onCheckedChange = { scalingEnabled = it },
                        modifier = Modifier.testTag("switch_scaling"),
                    )
                }
                if (scalingEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        DecimalField(rawMinText, { rawMinText = it }, stringResource(R.string.rw_raw_min), Modifier.weight(1f))
                        DecimalField(rawMaxText, { rawMaxText = it }, stringResource(R.string.rw_raw_max), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        DecimalField(engMinText, { engMinText = it }, stringResource(R.string.rw_eng_min), Modifier.weight(1f))
                        DecimalField(engMaxText, { engMaxText = it }, stringResource(R.string.rw_eng_max), Modifier.weight(1f))
                        DecimalField(engUnitText, { engUnitText = it }, stringResource(R.string.rw_eng_unit), Modifier.weight(1f))
                    }
                }
            }
        }

        // Tabel Register / Coil Hasil Bacaan
        if (lastRegs.isNotEmpty() || lastBits.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.rw_registers_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (lastRegs.isNotEmpty()) {
                        val decodedRegs = RegisterDecoder.decodeRegisters(lastStartAddr, lastRegs, activeScale)
                        for ((idx, reg) in decodedRegs.withIndex()) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("reg_row_$idx"),
                            ) {
                                val scaledPart = reg.scaledText?.let { " · Scaled=$it" } ?: ""
                                Text(
                                    "[${reg.address}] UInt16=${reg.uint16} | Int16=${reg.int16} | ${reg.hex}$scaledPart",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Bin: ${reg.binary}" + (reg.bcd?.let { " | BCD: $it" } ?: ""),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (idx < decodedRegs.lastIndex) HorizontalDivider()
                        }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            lastBits.forEachIndexed { idx, bit ->
                                val addr = lastStartAddr + idx
                                Text(
                                    "[$addr]=${if (bit) "1(ON)" else "0(OFF)"}",
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(4.dp).testTag("bit_row_$idx"),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Inspektor Pasangan Register 32-Bit (Int32 & Float32 4 Urutan Byte Sekaligus — GRATIS!)
        if (lastRegs.size >= 2) {
            val pairs = RegisterDecoder.decodePairs(lastStartAddr, lastRegs)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.rw_pairs_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    for ((idx, pair) in pairs.withIndex()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .testTag("pair_card_$idx"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "Reg [${pair.startAddress}..${pair.startAddress + 1}] " +
                                    "(0x${String.format(Locale.ROOT, "%04X", pair.regHigh)}, 0x${String.format(Locale.ROOT, "%04X", pair.regLow)})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                            )
                            for (ov in pair.allOrders) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "${ov.order.name} (${ov.hex})",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "Float32=${ov.floatText} | Int32=${ov.int32}",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.testTag("pair_${idx}_order_${ov.order.name}"),
                                    )
                                }
                            }
                        }
                        if (idx < pairs.lastIndex) HorizontalDivider()
                    }
                }
            }
        }

        // Bagian Tulis Coil / Register Tunggal & Banyak (dengan Konfirmasi)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.rw_write_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.rw_write_float_mode), style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = writeFloatMode,
                        onCheckedChange = { writeFloatMode = it },
                        modifier = Modifier.testTag("switch_write_float"),
                    )
                }
                if (writeFloatMode) {
                    ChipRow(
                        options = ByteOrder.entries,
                        selected = writeByteOrder,
                        label = { it.name },
                        onSelect = { writeByteOrder = it },
                    )
                }
                DecimalField(
                    value = writeInput,
                    onChange = { writeInput = it },
                    label = stringResource(R.string.rw_write_values),
                    modifier = Modifier.fillMaxWidth().testTag("input_write_values"),
                )
                Button(
                    onClick = {
                        val addr = resolvedAddr?.protocolAddress ?: return@Button
                        val uid = slaveInput.trim().toIntOrNull()?.coerceIn(0, 247) ?: profile.unitId
                        val parts = writeInput.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        if (parts.isEmpty()) return@Button
                        val req: ModbusRequest? = try {
                            when {
                                writeFloatMode -> {
                                    val fVal = parts.first().replace(',', '.').toFloatOrNull() ?: return@Button
                                    val regs = RegisterDecoder.encodeFloat32(fVal, writeByteOrder)
                                    ModbusRequest.WriteMultipleRegisters(addr, regs)
                                }
                                selectedFc == FunctionCode.FC05_WRITE_SINGLE_COIL ||
                                    (selectedFc.isBitType && parts.size == 1 && selectedFc != FunctionCode.FC15_WRITE_MULTIPLE_COILS) -> {
                                    val b = parts.first().let { it == "1" || it.equals("true", true) || it.equals("on", true) }
                                    ModbusRequest.WriteSingleCoil(addr, b)
                                }
                                selectedFc == FunctionCode.FC15_WRITE_MULTIPLE_COILS || selectedFc.isBitType -> {
                                    val bits = parts.map { it == "1" || it.equals("true", true) || it.equals("on", true) }
                                    ModbusRequest.WriteMultipleCoils(addr, bits)
                                }
                                selectedFc == FunctionCode.FC16_WRITE_MULTIPLE_REGISTERS || parts.size > 1 -> {
                                    val regs = parts.map { token ->
                                        if (token.startsWith("0x", true)) token.substring(2).toInt(16) else (token.toInt() and 0xFFFF)
                                    }
                                    ModbusRequest.WriteMultipleRegisters(addr, regs)
                                }
                                else -> {
                                    val v = parts.first().let { token ->
                                        if (token.startsWith("0x", true)) token.substring(2).toInt(16) else (token.toInt() and 0xFFFF)
                                    }
                                    ModbusRequest.WriteSingleRegister(addr, v)
                                }
                            }
                        } catch (_: Exception) {
                            null
                        }
                        if (req != null) {
                            pendingWriteRequest = uid to req
                        }
                    },
                    enabled = resolvedAddr != null,
                    modifier = Modifier.fillMaxWidth().testTag("btn_write_submit"),
                ) {
                    Text(stringResource(R.string.rw_write_button))
                }
            }
        }
    }

    pendingWriteRequest?.let { (uid, req) ->
        AlertDialog(
            onDismissRequest = { pendingWriteRequest = null },
            modifier = Modifier.testTag("write_confirm_dialog"),
            title = { Text(stringResource(R.string.rw_confirm_write_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(
                        R.string.rw_confirm_write_body,
                        req.functionCode.shortName,
                        uid,
                        req.startAddress,
                        String.format(Locale.ROOT, "0x%04X", req.startAddress),
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingWriteRequest = null
                        scope.launch { session.performRequest(uid, req) }
                    },
                    modifier = Modifier.testTag("btn_write_confirm"),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingWriteRequest = null },
                    modifier = Modifier.testTag("btn_write_cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun MonitorScreen(session: ModbusSessionController) {
    val frames by session.frameLog.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.copied)
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss.SSS") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen_monitor"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.monitor_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = {
                        val allText = frames.joinToString("\n") { f ->
                            val t = Instant.ofEpochMilli(f.timestampMillis).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormatter)
                            "$t [${f.direction}] ${f.hex} (${f.note})"
                        }
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ModbusTraffic", allText)))
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = frames.isNotEmpty(),
                    modifier = Modifier.testTag("btn_copy_log"),
                ) {
                    Text(stringResource(R.string.monitor_copy_all), maxLines = 1, softWrap = false)
                }
                TextButton(
                    onClick = { session.clearFrameLog() },
                    modifier = Modifier.testTag("btn_clear_log"),
                ) {
                    Text(stringResource(R.string.monitor_clear), maxLines = 1, softWrap = false)
                }
            }
        }

        if (frames.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.monitor_empty),
                    modifier = Modifier.padding(16.dp).testTag("monitor_empty_text"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            for ((idx, entry) in frames.withIndex()) {
                val timeStr = Instant.ofEpochMilli(entry.timestampMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalTime()
                    .format(timeFormatter)
                val containerColor = when {
                    entry.crcValid == false || entry.exceptionCode != null -> MaterialTheme.colorScheme.errorContainer
                    entry.direction == FrameDirection.TX -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.secondaryContainer
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = containerColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Frame", entry.hex)))
                                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        .testTag("frame_item_$idx"),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "$timeStr · ${entry.direction} (ID ${entry.unitId})",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                            val badgeText = when {
                                entry.crcValid == false -> stringResource(R.string.monitor_badge_crc_err)
                                entry.exceptionCode != null -> stringResource(R.string.monitor_badge_exception)
                                entry.isRtu && entry.crcValid == true -> stringResource(R.string.monitor_badge_crc_ok)
                                else -> "TCP"
                            }
                            Text(
                                badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("frame_badge_$idx"),
                            )
                        }
                        Text(
                            entry.hex,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("frame_hex_$idx"),
                        )
                        if (entry.note.isNotBlank()) {
                            Text(
                                entry.note,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProToolsScreen(
    initialSubTab: Int = 0,
    session: ModbusSessionController,
) {
    var subTab by rememberSaveable(initialSubTab) { mutableIntStateOf(initialSubTab.coerceIn(0, 1)) }
    val isScanning by session.isScanning.collectAsState()
    val scanCurrentId by session.scanCurrentId.collectAsState()
    val scanDone by session.scanDoneSteps.collectAsState()
    val scanTotal by session.scanTotalSteps.collectAsState()
    val scanHits by session.scanHits.collectAsState()
    val samples by session.dataSamples.collectAsState()
    val context = LocalContext.current

    var startIdText by rememberSaveable { mutableStateOf("1") }
    var endIdText by rememberSaveable { mutableStateOf("25") }
    var scanBaudCombos by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen_pro"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                label = { Text(stringResource(R.string.subtab_scanner)) },
                modifier = Modifier.testTag("subtab_scanner"),
            )
            FilterChip(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                label = { Text(stringResource(R.string.subtab_trend)) },
                modifier = Modifier.testTag("subtab_trend"),
            )
        }

        if (subTab == 0) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.scanner_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        DecimalField(
                            value = startIdText,
                            onChange = { startIdText = it },
                            label = stringResource(R.string.scanner_start_id),
                            modifier = Modifier.weight(1f).testTag("input_scan_start"),
                        )
                        DecimalField(
                            value = endIdText,
                            onChange = { endIdText = it },
                            label = stringResource(R.string.scanner_end_id),
                            modifier = Modifier.weight(1f).testTag("input_scan_end"),
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = scanBaudCombos,
                            onCheckedChange = { scanBaudCombos = it },
                            modifier = Modifier.testTag("check_scan_baud"),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.scanner_scan_baud), style = MaterialTheme.typography.bodySmall)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val s = startIdText.trim().toIntOrNull() ?: 1
                                val e = endIdText.trim().toIntOrNull() ?: 247
                                session.startScan(s, e, scanBaudCombos)
                            },
                            enabled = !isScanning,
                            modifier = Modifier.weight(1f).testTag("btn_scan_start"),
                        ) {
                            Text(stringResource(R.string.scanner_btn_start))
                        }
                        OutlinedButton(
                            onClick = { session.stopScan() },
                            enabled = isScanning,
                            modifier = Modifier.weight(1f).testTag("btn_scan_stop"),
                        ) {
                            Text(stringResource(R.string.scanner_btn_stop))
                        }
                    }

                    if (isScanning) {
                        val progress = (scanDone.toFloat() / scanTotal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().testTag("scan_progress_bar"),
                        )
                        Text(
                            stringResource(R.string.scanner_progress, scanCurrentId, scanDone, scanTotal),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else if (scanDone > 0) {
                        Text(
                            stringResource(R.string.scanner_done, scanHits.size),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("scan_done_text"),
                        )
                    }

                    HorizontalDivider()
                    if (scanHits.isEmpty()) {
                        Text(
                            stringResource(R.string.scanner_no_hits),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        for (hit in scanHits) {
                            val hitBaud = hit.baudRate
                            val hitParity = hit.parity
                            val serialInfo = if (hitBaud != null && hitParity != null) {
                                " · $hitBaud 8${hitParity.shortLabel}1"
                            } else {
                                ""
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("scan_hit_${hit.unitId}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "Slave/Unit ID ${hit.unitId}$serialInfo",
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    hit.detail,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.trend_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (samples.isEmpty()) {
                        Text(
                            stringResource(R.string.trend_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val lineColors = listOf(
                            Color(0xFF29B6F6),
                            Color(0xFFFFB300),
                            Color(0xFF66BB6A),
                            Color(0xFFEF5350),
                        )
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                                .testTag("trend_canvas"),
                        ) {
                            val maxSeries = minOf(4, samples.maxOfOrNull { it.registers.size } ?: 0)
                            val allValues = samples.flatMap { it.registers.take(maxSeries) }
                            val minVal = (allValues.minOrNull() ?: 0).toFloat()
                            val maxVal = (allValues.maxOrNull() ?: 1).toFloat()
                            val span = (maxVal - minVal).takeIf { it > 0f } ?: 1f

                            for (sIdx in 0 until maxSeries) {
                                val path = Path()
                                var started = false
                                samples.forEachIndexed { ptIdx, sample ->
                                    val regVal = sample.registers.getOrNull(sIdx)?.toFloat() ?: return@forEachIndexed
                                    val x = if (samples.size <= 1) {
                                        size.width / 2f
                                    } else {
                                        ptIdx.toFloat() / (samples.size - 1).toFloat() * size.width
                                    }
                                    val y = size.height - ((regVal - minVal) / span * size.height)
                                    if (!started) {
                                        path.moveTo(x, y)
                                        started = true
                                    } else {
                                        path.lineTo(x, y)
                                    }
                                    drawCircle(
                                        color = lineColors[sIdx % lineColors.size],
                                        radius = 4f,
                                        center = Offset(x, y),
                                    )
                                }
                                if (started && samples.size > 1) {
                                    drawPath(
                                        path = path,
                                        color = lineColors[sIdx % lineColors.size],
                                        style = Stroke(width = 3f),
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val csvText = CsvExporter.formatCsv(samples)
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_SUBJECT, "modbus_log.csv")
                                    putExtra(Intent.EXTRA_TEXT, csvText)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "CSV"))
                            },
                            modifier = Modifier.weight(1f).testTag("btn_export_csv"),
                        ) {
                            Text(stringResource(R.string.csv_export_share, samples.size))
                        }
                        OutlinedButton(
                            onClick = { session.clearDataSamples() },
                            modifier = Modifier.testTag("btn_clear_csv"),
                        ) {
                            Text(stringResource(R.string.csv_clear))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AboutScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen_about"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.about_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    stringResource(R.string.about_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("about_disclaimer_text"),
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.guide_rs485_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.guide_rs485_body), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.guide_address_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.guide_address_body), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.guide_endian_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.guide_endian_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
