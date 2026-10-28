package id.controlengineer.modbus.protocol

enum class ConnectionMode {
    TCP,
    RTU_USB,
}

enum class SerialParity(val code: Int, val shortLabel: String) {
    NONE(0, "N"),
    ODD(1, "O"),
    EVEN(2, "E");

    companion object {
        fun fromCode(code: Int): SerialParity = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/**
 * Profil koneksi Modbus (TCP atau RTU USB) yang disimpan di DataStore.
 * Versi gratis dibatasi 1 profil; versi Pro mendukung profil tak terbatas.
 */
data class ConnectionProfile(
    val name: String,
    val mode: ConnectionMode = ConnectionMode.TCP,
    val host: String = "192.168.1.10",
    val port: Int = 502,
    val unitId: Int = 1,
    val timeoutMs: Int = 1000,
    val baudRate: Int = 9600,
    val dataBits: Int = 8,
    val parity: SerialParity = SerialParity.NONE,
    val stopBits: Int = 1,
    val rtsRs485: Boolean = false,
) {
    fun encode(): String = listOf(
        TextCodec.escape(name),
        mode.name,
        TextCodec.escape(host),
        port.toString(),
        unitId.toString(),
        timeoutMs.toString(),
        baudRate.toString(),
        dataBits.toString(),
        parity.name,
        stopBits.toString(),
        rtsRs485.toString(),
    ).joinToString("\t")

    companion object {
        const val FREE_LIMIT = 1
        val STANDARD_BAUD_RATES = listOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200)

        fun decode(raw: String): ConnectionProfile? {
            if (raw.isBlank()) return null
            val p = raw.split('\t')
            if (p.size != 11) return null
            return try {
                ConnectionProfile(
                    name = TextCodec.unescape(p[0]),
                    mode = ConnectionMode.valueOf(p[1]),
                    host = TextCodec.unescape(p[2]),
                    port = p[3].toInt().coerceIn(1, 65535),
                    unitId = p[4].toInt().coerceIn(0, 255),
                    timeoutMs = p[5].toInt().coerceIn(100, 30000),
                    baudRate = p[6].toInt(),
                    dataBits = p[7].toInt().coerceIn(7, 8),
                    parity = SerialParity.valueOf(p[8]),
                    stopBits = p[9].toInt().coerceIn(1, 2),
                    rtsRs485 = p[10].toBooleanStrict(),
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        fun encodeList(profiles: List<ConnectionProfile>): String =
            profiles.joinToString("\n") { it.encode() }

        fun decodeList(raw: String): List<ConnectionProfile> =
            if (raw.isBlank()) emptyList() else raw.split('\n').mapNotNull(::decode)
    }
}

/** Hasil temuan pemindai (Scanner) pada Slave/Unit ID tertentu. */
data class ScanHit(
    val unitId: Int,
    val baudRate: Int? = null,
    val parity: SerialParity? = null,
    val detail: String,
    val responseTimeMs: Long? = null,
)

/** Isian kop laporan pemeriksaan Modbus yang disimpan di DataStore. */
data class ReportMeta(
    val technician: String = "",
    val company: String = "",
    val location: String = "",
    val notes: String = "",
    val deviceName: String = "",
) {
    fun encode(): String = listOf(
        TextCodec.escape(technician),
        TextCodec.escape(company),
        TextCodec.escape(location),
        TextCodec.escape(notes),
        TextCodec.escape(deviceName),
    ).joinToString("\t")

    companion object {
        fun decode(raw: String): ReportMeta {
            if (raw.isBlank()) return ReportMeta()
            val p = raw.split('\t')
            return ReportMeta(
                technician = p.getOrNull(0)?.let(TextCodec::unescape) ?: "",
                company = p.getOrNull(1)?.let(TextCodec::unescape) ?: "",
                location = p.getOrNull(2)?.let(TextCodec::unescape) ?: "",
                notes = p.getOrNull(3)?.let(TextCodec::unescape) ?: "",
                deviceName = p.getOrNull(4)?.let(TextCodec::unescape) ?: "",
            )
        }
    }
}

/** Satu baris rekaman log data untuk ekspor CSV dan grafik tren. */
data class DataSample(
    val timestampMillis: Long,
    val startAddress: Int,
    val registers: List<Int>,
)

object CsvExporter {
    fun formatCsv(samples: List<DataSample>, maxColumns: Int = 4): String {
        if (samples.isEmpty()) return "timestamp_ms,start_address,reg0,reg1,reg2,reg3,float32_abcd\n"
        val sb = StringBuilder()
        sb.append("timestamp_ms,start_address")
        for (i in 0 until maxColumns) {
            sb.append(",reg").append(i)
        }
        sb.append(",float32_abcd\n")
        for (s in samples) {
            sb.append(s.timestampMillis).append(',').append(s.startAddress)
            for (i in 0 until maxColumns) {
                val v = s.registers.getOrNull(i)
                sb.append(',').append(v?.toString() ?: "")
            }
            val floatStr = if (s.registers.size >= 2) {
                DecodedRegisterPair(s.startAddress, s.registers[0], s.registers[1])
                    .forOrder(ByteOrder.ABCD).floatText
            } else {
                ""
            }
            sb.append(',').append(floatStr).append('\n')
        }
        return sb.toString()
    }
}
