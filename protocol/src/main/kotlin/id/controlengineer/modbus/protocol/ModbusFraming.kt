package id.controlengineer.modbus.protocol

import java.util.Locale
import kotlin.math.ceil

/**
 * Perhitungan CRC-16/MODBUS sesuai MODBUS over Serial Line Specification and Implementation Guide V1.02 §6.2.2:
 * - Polinomial terbalik (reflected): 0xA001 (bentuk normal x^16 + x^15 + x^2 + 1 = 0x8005)
 * - Nilai awal register: 0xFFFF
 * - Tanpa XOR akhir
 * - Urutan transmisi di kabel: byte rendah (LSB) dikirim lebih dulu, diikuti byte tinggi (MSB).
 */
object ModbusCrc16 {
    private const val INITIAL = 0xFFFF
    private const val POLYNOMIAL_REFLECTED = 0xA001

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "Rentang buffer CRC tidak valid" }
        var crc = INITIAL
        for (i in offset until (offset + length)) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor POLYNOMIAL_REFLECTED
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    /** Menambahkan 2 byte CRC (low byte lebih dulu, kemudian high byte) ke akhir [payload]. */
    fun append(payload: ByteArray): ByteArray {
        val crc = compute(payload)
        val out = ByteArray(payload.size + 2)
        payload.copyInto(out)
        out[payload.size] = (crc and 0xFF).toByte()
        out[payload.size + 1] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    /** Memeriksa apakah 2 byte terakhir pada [rtuFrame] cocok dengan CRC-16/MODBUS payload. */
    fun verify(rtuFrame: ByteArray): Boolean {
        if (rtuFrame.size < 4) return false
        val expected = compute(rtuFrame, 0, rtuFrame.size - 2)
        val rxLo = rtuFrame[rtuFrame.size - 2].toInt() and 0xFF
        val rxHi = rtuFrame[rtuFrame.size - 1].toInt() and 0xFF
        val received = rxLo or (rxHi shl 8)
        return expected == received
    }

    fun extractReceivedCrc(rtuFrame: ByteArray): Int {
        require(rtuFrame.size >= 2)
        val rxLo = rtuFrame[rtuFrame.size - 2].toInt() and 0xFF
        val rxHi = rtuFrame[rtuFrame.size - 1].toInt() and 0xFF
        return rxLo or (rxHi shl 8)
    }
}

/**
 * Perhitungan pewaktuan frame RTU sesuai MODBUS over Serial Line V1.02 §2.5.1.1:
 * - Untuk baud <= 19200 bps: jeda antarframe minimal 3,5 karakter (t3.5) dan jeda antarkarakter maksimal 1,5 karakter (t1.5).
 * - Untuk baud > 19200 bps: digunakan nilai tetap t3.5 = 1750 us (1,75 ms) dan t1.5 = 750 us.
 */
object RtuTiming {
    const val HIGH_BAUD_THRESHOLD = 19200
    const val FIXED_T35_MICROS = 1750L
    const val FIXED_T15_MICROS = 750L

    fun interFrameDelayMicros(baudRate: Int, bitsPerChar: Int = 11): Long {
        require(baudRate > 0) { "baudRate harus > 0" }
        require(bitsPerChar in 9..12) { "bitsPerChar harus 9..12" }
        if (baudRate > HIGH_BAUD_THRESHOLD) return FIXED_T35_MICROS
        return ceil(3.5 * bitsPerChar * 1_000_000.0 / baudRate).toLong()
    }

    fun interCharTimeoutMicros(baudRate: Int, bitsPerChar: Int = 11): Long {
        require(baudRate > 0) { "baudRate harus > 0" }
        if (baudRate > HIGH_BAUD_THRESHOLD) return FIXED_T15_MICROS
        return ceil(1.5 * bitsPerChar * 1_000_000.0 / baudRate).toLong()
    }

    /** Nilai milidetik dibulatkan ke atas (minimal 2 ms) untuk penjadwalan tidur thread/coroutine. */
    fun interFrameDelayMillis(baudRate: Int, bitsPerChar: Int = 11): Long {
        val micros = interFrameDelayMicros(baudRate, bitsPerChar)
        return ((micros + 999L) / 1000L).coerceAtLeast(2L)
    }
}

/**
 * Frame RTU lengkap: [Slave ID (1)] + [PDU (N)] + [CRC Lo (1)] + [CRC Hi (1)].
 */
data class RtuFrame(
    val slaveId: Int,
    val pdu: ByteArray,
    val crc: Int,
    val crcValid: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is RtuFrame &&
            slaveId == other.slaveId &&
            pdu.contentEquals(other.pdu) &&
            crc == other.crc &&
            crcValid == other.crcValid

    override fun hashCode(): Int =
        31 * (31 * (31 * slaveId + pdu.contentHashCode()) + crc) + crcValid.hashCode()
}

object RtuCodec {
    fun encode(slaveId: Int, pdu: ByteArray): ByteArray {
        require(slaveId in 0..247) { "Slave ID RTU harus 0..247" }
        require(pdu.isNotEmpty()) { "PDU tidak boleh kosong" }
        val body = ByteArray(1 + pdu.size)
        body[0] = (slaveId and 0xFF).toByte()
        pdu.copyInto(body, destinationOffset = 1)
        return ModbusCrc16.append(body)
    }

    fun decode(rawFrame: ByteArray): RtuFrame {
        if (rawFrame.size < 4) {
            throw ModbusProtocolException("Frame RTU terlalu pendek (${rawFrame.size} byte, minimal 4 byte)")
        }
        val slaveId = rawFrame[0].toInt() and 0xFF
        val pdu = rawFrame.copyOfRange(1, rawFrame.size - 2)
        val rxCrc = ModbusCrc16.extractReceivedCrc(rawFrame)
        val valid = ModbusCrc16.verify(rawFrame)
        return RtuFrame(slaveId = slaveId, pdu = pdu, crc = rxCrc, crcValid = valid)
    }

    /** Menghitung panjang byte total frame respons RTU yang diharapkan untuk request tertentu. */
    fun expectedResponseLength(request: ModbusRequest): Int = when (request) {
        is ModbusRequest.ReadCoils -> 1 + 2 + ((request.count + 7) / 8) + 2
        is ModbusRequest.ReadDiscreteInputs -> 1 + 2 + ((request.count + 7) / 8) + 2
        is ModbusRequest.ReadHoldingRegisters -> 1 + 2 + (request.count * 2) + 2
        is ModbusRequest.ReadInputRegisters -> 1 + 2 + (request.count * 2) + 2
        is ModbusRequest.WriteSingleCoil,
        is ModbusRequest.WriteSingleRegister,
        is ModbusRequest.WriteMultipleCoils,
        is ModbusRequest.WriteMultipleRegisters -> 1 + 5 + 2
    }
}

/**
 * Header MBAP (MODBUS Application Protocol Header) 7 byte untuk Modbus TCP/IP:
 * - Transaction Identifier (2 byte BE)
 * - Protocol Identifier (2 byte BE, selalu 0x0000 untuk Modbus)
 * - Length (2 byte BE, jumlah byte berikutnya = Unit ID 1 byte + panjang PDU)
 * - Unit Identifier (1 byte)
 */
data class MbapFrame(
    val transactionId: Int,
    val protocolId: Int = 0,
    val length: Int,
    val unitId: Int,
    val pdu: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is MbapFrame &&
            transactionId == other.transactionId &&
            protocolId == other.protocolId &&
            length == other.length &&
            unitId == other.unitId &&
            pdu.contentEquals(other.pdu)

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * transactionId + protocolId) + length) + unitId) + pdu.contentHashCode()
}

object MbapCodec {
    const val HEADER_LENGTH = 7
    const val MODBUS_PROTOCOL_ID = 0x0000

    fun encode(transactionId: Int, unitId: Int, pdu: ByteArray): ByteArray {
        require(pdu.isNotEmpty() && pdu.size <= 253) { "Panjang PDU Modbus harus 1..253 byte" }
        val lengthField = 1 + pdu.size
        val out = ByteArray(HEADER_LENGTH + pdu.size)
        ModbusPduCodec.putUInt16BE(out, 0, transactionId and 0xFFFF)
        ModbusPduCodec.putUInt16BE(out, 2, MODBUS_PROTOCOL_ID)
        ModbusPduCodec.putUInt16BE(out, 4, lengthField)
        out[6] = (unitId and 0xFF).toByte()
        pdu.copyInto(out, destinationOffset = HEADER_LENGTH)
        return out
    }

    fun decode(tcpFrame: ByteArray): MbapFrame {
        if (tcpFrame.size < HEADER_LENGTH + 1) {
            throw ModbusProtocolException("Frame Modbus TCP terlalu pendek (${tcpFrame.size} byte)")
        }
        val txId = ModbusPduCodec.getUInt16BE(tcpFrame, 0)
        val protoId = ModbusPduCodec.getUInt16BE(tcpFrame, 2)
        if (protoId != MODBUS_PROTOCOL_ID) {
            throw ModbusProtocolException("Protocol ID MBAP bukan 0x0000 (aktual=0x${protoId.toString(16)})")
        }
        val len = ModbusPduCodec.getUInt16BE(tcpFrame, 4)
        if (len < 2 || tcpFrame.size != 6 + len) {
            throw ModbusProtocolException("Field Length MBAP ($len) tidak cocok dengan ukuran paket (${tcpFrame.size})")
        }
        val unitId = tcpFrame[6].toInt() and 0xFF
        val pdu = tcpFrame.copyOfRange(HEADER_LENGTH, tcpFrame.size)
        return MbapFrame(
            transactionId = txId,
            protocolId = protoId,
            length = len,
            unitId = unitId,
            pdu = pdu,
        )
    }
}

object HexFormatUtil {
    fun toHexString(bytes: ByteArray, separator: String = " "): String =
        bytes.joinToString(separator) { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }

    fun fromHexString(hex: String): ByteArray {
        val cleaned = hex.replace(" ", "").replace(":", "").replace("-", "").trim()
        require(cleaned.length % 2 == 0) { "Panjang string hex harus genap" }
        return ByteArray(cleaned.length / 2) { idx ->
            cleaned.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }
}
