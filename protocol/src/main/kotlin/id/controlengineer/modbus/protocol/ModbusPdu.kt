package id.controlengineer.modbus.protocol

import java.util.Locale

/**
 * Function Code standar sesuai MODBUS Application Protocol Specification V1.1b3 §5 & §6.
 */
enum class FunctionCode(
    val code: Int,
    val shortName: String,
    val isWrite: Boolean,
    val isBitType: Boolean,
) {
    FC01_READ_COILS(0x01, "FC01 Read Coils", isWrite = false, isBitType = true),
    FC02_READ_DISCRETE_INPUTS(0x02, "FC02 Read Discrete Inputs", isWrite = false, isBitType = true),
    FC03_READ_HOLDING_REGISTERS(0x03, "FC03 Read Holding Registers", isWrite = false, isBitType = false),
    FC04_READ_INPUT_REGISTERS(0x04, "FC04 Read Input Registers", isWrite = false, isBitType = false),
    FC05_WRITE_SINGLE_COIL(0x05, "FC05 Write Single Coil", isWrite = true, isBitType = true),
    FC06_WRITE_SINGLE_REGISTER(0x06, "FC06 Write Single Register", isWrite = true, isBitType = false),
    FC15_WRITE_MULTIPLE_COILS(0x0F, "FC15 Write Multiple Coils", isWrite = true, isBitType = true),
    FC16_WRITE_MULTIPLE_REGISTERS(0x10, "FC16 Write Multiple Registers", isWrite = true, isBitType = false);

    companion object {
        fun fromCode(code: Int): FunctionCode? = entries.firstOrNull { it.code == (code and 0x7F) }
    }
}

/**
 * Kode exception standar sesuai MODBUS Application Protocol Specification V1.1b3 §7.
 */
enum class ModbusExceptionCode(
    val code: Int,
    val nameEn: String,
    val descriptionId: String,
    val descriptionEn: String,
) {
    ILLEGAL_FUNCTION(
        0x01,
        "Illegal Function",
        "Kode fungsi (FC) tidak didukung oleh perangkat slave.",
        "Function code is not supported by the slave device.",
    ),
    ILLEGAL_DATA_ADDRESS(
        0x02,
        "Illegal Data Address",
        "Alamat register/coil atau rentang jumlah data tidak tersedia di perangkat.",
        "Register/coil address or quantity range is not valid in the device.",
    ),
    ILLEGAL_DATA_VALUE(
        0x03,
        "Illegal Data Value",
        "Nilai data yang dikirim tidak diizinkan atau struktur kueri salah.",
        "Data value in query field is not allowable or structure is invalid.",
    ),
    SERVER_DEVICE_FAILURE(
        0x04,
        "Server/Slave Device Failure",
        "Kegagalan tak terpulihkan saat perangkat slave mencoba menjalankan aksi.",
        "Unrecoverable error occurred while the slave was attempting the action.",
    ),
    ACKNOWLEDGE(
        0x05,
        "Acknowledge",
        "Permintaan diterima dan sedang diproses (butuh waktu lama).",
        "Request accepted and is being processed (long duration command).",
    ),
    SERVER_DEVICE_BUSY(
        0x06,
        "Server/Slave Device Busy",
        "Perangkat slave sedang sibuk memproses perintah berdurasi panjang.",
        "Slave is engaged in processing a long-duration program command.",
    ),
    MEMORY_PARITY_ERROR(
        0x08,
        "Memory Parity Error",
        "Galat paritas terdeteksi saat perangkat membaca memori perluasan.",
        "Parity error detected when reading extended memory file.",
    ),
    GATEWAY_PATH_UNAVAILABLE(
        0x0A,
        "Gateway Path Unavailable",
        "Gateway gagal mengalokasikan jalur komunikasi internal ke port target.",
        "Gateway was unable to allocate an internal communication path.",
    ),
    GATEWAY_TARGET_FAILED_TO_RESPOND(
        0x0B,
        "Gateway Target Failed to Respond",
        "Perangkat target di balik gateway tidak merespons (offline/salah ID).",
        "No response obtained from the target device behind the gateway.",
    );

    companion object {
        fun fromCode(code: Int): ModbusExceptionCode? = entries.firstOrNull { it.code == (code and 0xFF) }

        fun describeId(code: Int): String {
            val hex = String.format(Locale.ROOT, "0x%02X", code and 0xFF)
            val known = fromCode(code)
            return if (known != null) {
                "${known.nameEn} ($hex): ${known.descriptionId}"
            } else {
                "Unknown Exception ($hex): Kode exception tidak dikenal"
            }
        }

        fun describeEn(code: Int): String {
            val hex = String.format(Locale.ROOT, "0x%02X", code and 0xFF)
            val known = fromCode(code)
            return if (known != null) {
                "${known.nameEn} ($hex): ${known.descriptionEn}"
            } else {
                "Unknown Exception ($hex): Unrecognized Modbus exception code"
            }
        }
    }
}

sealed interface ModbusRequest {
    val functionCode: FunctionCode
    val startAddress: Int

    data class ReadCoils(
        override val startAddress: Int,
        val count: Int,
    ) : ModbusRequest {
        override val functionCode = FunctionCode.FC01_READ_COILS
        init {
            require(startAddress in 0..0xFFFF) { "startAddress di luar 0..65535" }
            require(count in 1..2000) { "jumlah coil FC01 harus 1..2000" }
        }
    }

    data class ReadDiscreteInputs(
        override val startAddress: Int,
        val count: Int,
    ) : ModbusRequest {
        override val functionCode = FunctionCode.FC02_READ_DISCRETE_INPUTS
        init {
            require(startAddress in 0..0xFFFF) { "startAddress di luar 0..65535" }
            require(count in 1..2000) { "jumlah discrete input FC02 harus 1..2000" }
        }
    }

    data class ReadHoldingRegisters(
        override val startAddress: Int,
        val count: Int,
    ) : ModbusRequest {
        override val functionCode = FunctionCode.FC03_READ_HOLDING_REGISTERS
        init {
            require(startAddress in 0..0xFFFF) { "startAddress di luar 0..65535" }
            require(count in 1..125) { "jumlah register FC03 harus 1..125" }
        }
    }

    data class ReadInputRegisters(
        override val startAddress: Int,
        val count: Int,
    ) : ModbusRequest {
        override val functionCode = FunctionCode.FC04_READ_INPUT_REGISTERS
        init {
            require(startAddress in 0..0xFFFF) { "startAddress di luar 0..65535" }
            require(count in 1..125) { "jumlah register FC04 harus 1..125" }
        }
    }

    data class WriteSingleCoil(
        override val startAddress: Int,
        val value: Boolean,
    ) : ModbusRequest {
        override val functionCode = FunctionCode.FC05_WRITE_SINGLE_COIL
        init {
            require(startAddress in 0..0xFFFF) { "startAddress di luar 0..65535" }
        }
    }

    data class WriteSingleRegister(
        override val startAddress: Int,
        val value: Int,
    ) : ModbusRequest {
        override val functionCode = FunctionCode.FC06_WRITE_SINGLE_REGISTER
        init {
            require(startAddress in 0..0xFFFF) { "startAddress di luar 0..65535" }
            require(value in 0..0xFFFF) { "nilai register 16-bit harus 0..65535" }
        }
    }

    data class WriteMultipleCoils(
        override val startAddress: Int,
        val values: List<Boolean>,
    ) : ModbusRequest {
        override val functionCode = FunctionCode.FC15_WRITE_MULTIPLE_COILS
        init {
            require(startAddress in 0..0xFFFF) { "startAddress di luar 0..65535" }
            require(values.size in 1..1968) { "jumlah coil FC15 harus 1..1968" }
        }
    }

    data class WriteMultipleRegisters(
        override val startAddress: Int,
        val values: List<Int>,
    ) : ModbusRequest {
        override val functionCode = FunctionCode.FC16_WRITE_MULTIPLE_REGISTERS
        init {
            require(startAddress in 0..0xFFFF) { "startAddress di luar 0..65535" }
            require(values.size in 1..123) { "jumlah register FC16 harus 1..123" }
            require(values.all { it in 0..0xFFFF }) { "semua nilai register harus 0..65535" }
        }
    }
}

sealed interface ModbusResponse {
    data class ReadBits(
        val functionCode: FunctionCode,
        val bits: List<Boolean>,
        val packedBytes: ByteArray,
    ) : ModbusResponse {
        override fun equals(other: Any?): Boolean =
            other is ReadBits &&
                functionCode == other.functionCode &&
                bits == other.bits &&
                packedBytes.contentEquals(other.packedBytes)

        override fun hashCode(): Int =
            31 * (31 * functionCode.hashCode() + bits.hashCode()) + packedBytes.contentHashCode()
    }

    data class ReadRegisters(
        val functionCode: FunctionCode,
        val registers: List<Int>,
    ) : ModbusResponse

    data class WriteSingleCoil(
        val address: Int,
        val value: Boolean,
    ) : ModbusResponse

    data class WriteSingleRegister(
        val address: Int,
        val value: Int,
    ) : ModbusResponse

    data class WriteMultipleCoils(
        val startAddress: Int,
        val count: Int,
    ) : ModbusResponse

    data class WriteMultipleRegisters(
        val startAddress: Int,
        val count: Int,
    ) : ModbusResponse

    data class Exception(
        val originalFunctionCode: Int,
        val exceptionCode: Int,
    ) : ModbusResponse {
        val knownCode: ModbusExceptionCode? get() = ModbusExceptionCode.fromCode(exceptionCode)
        val messageId: String get() = ModbusExceptionCode.describeId(exceptionCode)
        val messageEn: String get() = ModbusExceptionCode.describeEn(exceptionCode)
    }
}

class ModbusProtocolException(message: String) : IllegalArgumentException(message)

/**
 * Encoder & decoder PDU Modbus murni (tanpa pustaka pihak ketiga)
 * sesuai MODBUS Application Protocol Specification V1.1b3 §6.
 */
object ModbusPduCodec {

    fun encodeRequest(request: ModbusRequest): ByteArray = when (request) {
        is ModbusRequest.ReadCoils -> encodeReadFixed(0x01, request.startAddress, request.count)
        is ModbusRequest.ReadDiscreteInputs -> encodeReadFixed(0x02, request.startAddress, request.count)
        is ModbusRequest.ReadHoldingRegisters -> encodeReadFixed(0x03, request.startAddress, request.count)
        is ModbusRequest.ReadInputRegisters -> encodeReadFixed(0x04, request.startAddress, request.count)
        is ModbusRequest.WriteSingleCoil -> {
            val outVal = if (request.value) 0xFF00 else 0x0000
            encodeReadFixed(0x05, request.startAddress, outVal)
        }
        is ModbusRequest.WriteSingleRegister ->
            encodeReadFixed(0x06, request.startAddress, request.value and 0xFFFF)
        is ModbusRequest.WriteMultipleCoils -> {
            val packed = packBits(request.values)
            val out = ByteArray(6 + packed.size)
            out[0] = 0x0F.toByte()
            putUInt16BE(out, 1, request.startAddress)
            putUInt16BE(out, 3, request.values.size)
            out[5] = packed.size.toByte()
            packed.copyInto(out, destinationOffset = 6)
            out
        }
        is ModbusRequest.WriteMultipleRegisters -> {
            val byteCount = request.values.size * 2
            val out = ByteArray(6 + byteCount)
            out[0] = 0x10.toByte()
            putUInt16BE(out, 1, request.startAddress)
            putUInt16BE(out, 3, request.values.size)
            out[5] = byteCount.toByte()
            request.values.forEachIndexed { idx, reg ->
                putUInt16BE(out, 6 + idx * 2, reg and 0xFFFF)
            }
            out
        }
    }

    fun decodeRequest(pdu: ByteArray): ModbusRequest {
        if (pdu.isEmpty()) throw ModbusProtocolException("PDU kosong")
        val fc = pdu[0].toInt() and 0xFF
        return when (fc) {
            0x01, 0x02, 0x03, 0x04 -> {
                if (pdu.size != 5) throw ModbusProtocolException("Panjang PDU FC0$fc harus 5 byte, aktual=${pdu.size}")
                val addr = getUInt16BE(pdu, 1)
                val count = getUInt16BE(pdu, 3)
                try {
                    when (fc) {
                        0x01 -> ModbusRequest.ReadCoils(addr, count)
                        0x02 -> ModbusRequest.ReadDiscreteInputs(addr, count)
                        0x03 -> ModbusRequest.ReadHoldingRegisters(addr, count)
                        else -> ModbusRequest.ReadInputRegisters(addr, count)
                    }
                } catch (e: IllegalArgumentException) {
                    throw ModbusProtocolException(e.message ?: "Parameter FC0$fc tidak valid")
                }
            }
            0x05 -> {
                if (pdu.size != 5) throw ModbusProtocolException("Panjang PDU FC05 harus 5 byte")
                val addr = getUInt16BE(pdu, 1)
                val rawVal = getUInt16BE(pdu, 3)
                val boolVal = when (rawVal) {
                    0xFF00 -> true
                    0x0000 -> false
                    else -> throw ModbusProtocolException("Nilai FC05 harus 0xFF00 atau 0x0000, aktual=0x${rawVal.toString(16)}")
                }
                ModbusRequest.WriteSingleCoil(addr, boolVal)
            }
            0x06 -> {
                if (pdu.size != 5) throw ModbusProtocolException("Panjang PDU FC06 harus 5 byte")
                val addr = getUInt16BE(pdu, 1)
                val regVal = getUInt16BE(pdu, 3)
                ModbusRequest.WriteSingleRegister(addr, regVal)
            }
            0x0F -> {
                if (pdu.size < 7) throw ModbusProtocolException("PDU FC15 terlalu pendek")
                val addr = getUInt16BE(pdu, 1)
                val count = getUInt16BE(pdu, 3)
                val byteCount = pdu[5].toInt() and 0xFF
                val expectedBytes = (count + 7) / 8
                if (byteCount != expectedBytes || pdu.size != 6 + byteCount) {
                    throw ModbusProtocolException("Byte count FC15 tidak cocok: byteCount=$byteCount, pduSize=${pdu.size}")
                }
                val bits = unpackBits(pdu.copyOfRange(6, pdu.size), count)
                try {
                    ModbusRequest.WriteMultipleCoils(addr, bits)
                } catch (e: IllegalArgumentException) {
                    throw ModbusProtocolException(e.message ?: "Parameter FC15 tidak valid")
                }
            }
            0x10 -> {
                if (pdu.size < 8) throw ModbusProtocolException("PDU FC16 terlalu pendek")
                val addr = getUInt16BE(pdu, 1)
                val count = getUInt16BE(pdu, 3)
                val byteCount = pdu[5].toInt() and 0xFF
                if (byteCount != count * 2 || pdu.size != 6 + byteCount) {
                    throw ModbusProtocolException("Byte count FC16 tidak cocok: byteCount=$byteCount, pduSize=${pdu.size}")
                }
                val regs = (0 until count).map { idx -> getUInt16BE(pdu, 6 + idx * 2) }
                try {
                    ModbusRequest.WriteMultipleRegisters(addr, regs)
                } catch (e: IllegalArgumentException) {
                    throw ModbusProtocolException(e.message ?: "Parameter FC16 tidak valid")
                }
            }
            else -> throw ModbusProtocolException("Function code tidak didukung: 0x${fc.toString(16)}")
        }
    }

    fun encodeResponse(response: ModbusResponse): ByteArray = when (response) {
        is ModbusResponse.ReadBits -> {
            val out = ByteArray(2 + response.packedBytes.size)
            out[0] = response.functionCode.code.toByte()
            out[1] = response.packedBytes.size.toByte()
            response.packedBytes.copyInto(out, destinationOffset = 2)
            out
        }
        is ModbusResponse.ReadRegisters -> {
            val byteCount = response.registers.size * 2
            val out = ByteArray(2 + byteCount)
            out[0] = response.functionCode.code.toByte()
            out[1] = byteCount.toByte()
            response.registers.forEachIndexed { idx, reg ->
                putUInt16BE(out, 2 + idx * 2, reg and 0xFFFF)
            }
            out
        }
        is ModbusResponse.WriteSingleCoil -> {
            val outVal = if (response.value) 0xFF00 else 0x0000
            encodeReadFixed(0x05, response.address, outVal)
        }
        is ModbusResponse.WriteSingleRegister ->
            encodeReadFixed(0x06, response.address, response.value and 0xFFFF)
        is ModbusResponse.WriteMultipleCoils ->
            encodeReadFixed(0x0F, response.startAddress, response.count)
        is ModbusResponse.WriteMultipleRegisters ->
            encodeReadFixed(0x10, response.startAddress, response.count)
        is ModbusResponse.Exception -> byteArrayOf(
            ((response.originalFunctionCode and 0x7F) or 0x80).toByte(),
            (response.exceptionCode and 0xFF).toByte(),
        )
    }

    fun decodeResponse(pdu: ByteArray, expectedCount: Int? = null): ModbusResponse {
        if (pdu.size < 2) throw ModbusProtocolException("Respons PDU terlalu pendek (${pdu.size} byte)")
        val rawFc = pdu[0].toInt() and 0xFF
        if ((rawFc and 0x80) != 0) {
            if (pdu.size < 2) throw ModbusProtocolException("Respons exception tidak lengkap")
            val origFc = rawFc and 0x7F
            val exCode = pdu[1].toInt() and 0xFF
            return ModbusResponse.Exception(originalFunctionCode = origFc, exceptionCode = exCode)
        }
        return when (rawFc) {
            0x01, 0x02 -> {
                val byteCount = pdu[1].toInt() and 0xFF
                if (pdu.size != 2 + byteCount) {
                    throw ModbusProtocolException("Panjang PDU respons FC0$rawFc tidak sesuai byteCount ($byteCount)")
                }
                val packed = pdu.copyOfRange(2, pdu.size)
                val bitTotal = expectedCount ?: (byteCount * 8)
                if (expectedCount != null && (expectedCount + 7) / 8 != byteCount) {
                    throw ModbusProtocolException("Jumlah byte respons FC0$rawFc ($byteCount) tidak sesuai jumlah bit ($expectedCount)")
                }
                val fc = if (rawFc == 0x01) FunctionCode.FC01_READ_COILS else FunctionCode.FC02_READ_DISCRETE_INPUTS
                ModbusResponse.ReadBits(fc, unpackBits(packed, bitTotal), packed)
            }
            0x03, 0x04 -> {
                val byteCount = pdu[1].toInt() and 0xFF
                if (byteCount % 2 != 0 || pdu.size != 2 + byteCount) {
                    throw ModbusProtocolException("Panjang PDU respons FC0$rawFc tidak valid (byteCount=$byteCount, pduSize=${pdu.size})")
                }
                val regCount = byteCount / 2
                if (expectedCount != null && regCount != expectedCount) {
                    throw ModbusProtocolException("Jumlah register respons ($regCount) tidak sesuai permintaan ($expectedCount)")
                }
                val regs = (0 until regCount).map { idx -> getUInt16BE(pdu, 2 + idx * 2) }
                val fc = if (rawFc == 0x03) FunctionCode.FC03_READ_HOLDING_REGISTERS else FunctionCode.FC04_READ_INPUT_REGISTERS
                ModbusResponse.ReadRegisters(fc, regs)
            }
            0x05 -> {
                if (pdu.size != 5) throw ModbusProtocolException("Panjang respons FC05 harus 5 byte")
                val addr = getUInt16BE(pdu, 1)
                val rawVal = getUInt16BE(pdu, 3)
                val v = when (rawVal) {
                    0xFF00 -> true
                    0x0000 -> false
                    else -> throw ModbusProtocolException("Nilai respons FC05 tidak valid: 0x${rawVal.toString(16)}")
                }
                ModbusResponse.WriteSingleCoil(addr, v)
            }
            0x06 -> {
                if (pdu.size != 5) throw ModbusProtocolException("Panjang respons FC06 harus 5 byte")
                ModbusResponse.WriteSingleRegister(getUInt16BE(pdu, 1), getUInt16BE(pdu, 3))
            }
            0x0F -> {
                if (pdu.size != 5) throw ModbusProtocolException("Panjang respons FC15 harus 5 byte")
                ModbusResponse.WriteMultipleCoils(getUInt16BE(pdu, 1), getUInt16BE(pdu, 3))
            }
            0x10 -> {
                if (pdu.size != 5) throw ModbusProtocolException("Panjang respons FC16 harus 5 byte")
                ModbusResponse.WriteMultipleRegisters(getUInt16BE(pdu, 1), getUInt16BE(pdu, 3))
            }
            else -> throw ModbusProtocolException("Function code respons tidak dikenal: 0x${rawFc.toString(16)}")
        }
    }

    fun packBits(bits: List<Boolean>): ByteArray {
        val byteCount = (bits.size + 7) / 8
        val out = ByteArray(byteCount)
        for (i in bits.indices) {
            if (bits[i]) {
                out[i / 8] = (out[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        return out
    }

    fun unpackBits(packed: ByteArray, bitCount: Int): List<Boolean> {
        val maxBits = minOf(bitCount, packed.size * 8)
        return (0 until maxBits).map { i ->
            ((packed[i / 8].toInt() ushr (i % 8)) and 1) == 1
        }
    }

    private fun encodeReadFixed(fc: Int, word1: Int, word2: Int): ByteArray {
        val out = ByteArray(5)
        out[0] = fc.toByte()
        putUInt16BE(out, 1, word1)
        putUInt16BE(out, 3, word2)
        return out
    }

    internal fun putUInt16BE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    internal fun getUInt16BE(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
}
