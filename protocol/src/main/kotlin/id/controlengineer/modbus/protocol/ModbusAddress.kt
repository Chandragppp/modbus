package id.controlengineer.modbus.protocol

import java.util.Locale

enum class AddressNotation {
    AUTO,
    ZERO_BASED,
    MODICON,
}

/**
 * Hasil pemetaan input alamat (0-based desimal/hex maupun notasi Modicon 5/6 digit)
 * menjadi alamat protokol PDU 0-based (0..65535) dan saran Function Code.
 */
data class ResolvedAddress(
    val protocolAddress: Int,
    val suggestedFunctionCode: FunctionCode?,
    val isModicon: Boolean,
    val modiconEquivalent: String,
) {
    val hexAddress: String
        get() = String.format(Locale.ROOT, "0x%04X", protocolAddress and 0xFFFF)

    /** Pratinjau bahasa Indonesia, contoh: "FC03, alamat protokol 0". */
    fun previewId(selectedFc: FunctionCode): String {
        val fcCode = String.format(Locale.ROOT, "FC%02d", selectedFc.code)
        return "$fcCode, alamat protokol $protocolAddress"
    }

    /** Pratinjau bahasa Inggris, contoh: "FC03, protocol address 0". */
    fun previewEn(selectedFc: FunctionCode): String {
        val fcCode = String.format(Locale.ROOT, "FC%02d", selectedFc.code)
        return "$fcCode, protocol address $protocolAddress"
    }

    /** Pratinjau lengkap dengan heksadesimal dan padanan Modicon. */
    fun fullPreviewId(selectedFc: FunctionCode): String =
        "${previewId(selectedFc)} ($hexAddress · Modicon $modiconEquivalent)"

    fun fullPreviewEn(selectedFc: FunctionCode): String =
        "${previewEn(selectedFc)} ($hexAddress · Modicon $modiconEquivalent)"
}

object ModbusAddress {
    const val MIN_ADDRESS = 0
    const val MAX_ADDRESS = 65535

    /**
     * Mengurai input alamat:
     * - Hex 0-based: "0x0000", "16#006B"
     * - Notasi Modicon 5 digit / 6 digit:
     *   - 00001..09999 / 000001..065536 -> Coil (FC01), alamat = N - 1
     *   - 10001..19999 / 100001..165536 -> Discrete Input (FC02), alamat = offset - 1
     *   - 30001..39999 / 300001..365536 -> Input Register (FC04), alamat = offset - 1
     *   - 40001..49999 / 400001..465536 -> Holding Register (FC03), alamat = offset - 1
     * - Desimal 0-based: "0", "107", dst.
     */
    fun resolve(
        rawInput: String,
        notation: AddressNotation = AddressNotation.AUTO,
        currentFc: FunctionCode = FunctionCode.FC03_READ_HOLDING_REGISTERS,
    ): ResolvedAddress? {
        val cleaned = rawInput.trim().replace("_", "").replace(" ", "")
        if (cleaned.isEmpty()) return null

        val hexPrefixes = listOf("0x", "0X", "16#")
        val matchedPrefix = hexPrefixes.firstOrNull { cleaned.startsWith(it) }
        if (matchedPrefix != null) {
            if (notation == AddressNotation.MODICON) return null
            val hexPart = cleaned.removePrefix(matchedPrefix)
            val addr = hexPart.toIntOrNull(16) ?: return null
            if (addr !in MIN_ADDRESS..MAX_ADDRESS) return null
            return ResolvedAddress(
                protocolAddress = addr,
                suggestedFunctionCode = null,
                isModicon = false,
                modiconEquivalent = formatModicon(addr, currentFc),
            )
        }

        if (!cleaned.all { it.isDigit() }) return null

        val isLikelyModicon = when (notation) {
            AddressNotation.ZERO_BASED -> false
            AddressNotation.MODICON -> true
            AddressNotation.AUTO -> cleaned.length in 5..6 && cleaned[0] in charArrayOf('0', '1', '3', '4')
        }

        if (isLikelyModicon) {
            parseModicon(cleaned)?.let { return it }
            if (notation == AddressNotation.MODICON) return null
        }

        val addr = cleaned.toIntOrNull() ?: return null
        if (addr !in MIN_ADDRESS..MAX_ADDRESS) return null
        return ResolvedAddress(
            protocolAddress = addr,
            suggestedFunctionCode = null,
            isModicon = false,
            modiconEquivalent = formatModicon(addr, currentFc),
        )
    }

    private fun parseModicon(digits: String): ResolvedAddress? {
        if (digits.length !in 5..6) return null
        val prefix = digits[0]
        val offsetPart = digits.substring(1).toIntOrNull() ?: return null
        if (offsetPart < 1) return null
        val protocolAddr = offsetPart - 1
        if (protocolAddr !in MIN_ADDRESS..MAX_ADDRESS) return null

        val suggestedFc = when (prefix) {
            '0' -> FunctionCode.FC01_READ_COILS
            '1' -> FunctionCode.FC02_READ_DISCRETE_INPUTS
            '3' -> FunctionCode.FC04_READ_INPUT_REGISTERS
            '4' -> FunctionCode.FC03_READ_HOLDING_REGISTERS
            else -> return null
        }
        return ResolvedAddress(
            protocolAddress = protocolAddr,
            suggestedFunctionCode = suggestedFc,
            isModicon = true,
            modiconEquivalent = digits,
        )
    }

    fun formatModicon(protocolAddress: Int, fc: FunctionCode): String {
        val oneBased = (protocolAddress.coerceIn(MIN_ADDRESS, MAX_ADDRESS)) + 1
        val prefix = when (fc) {
            FunctionCode.FC01_READ_COILS,
            FunctionCode.FC05_WRITE_SINGLE_COIL,
            FunctionCode.FC15_WRITE_MULTIPLE_COILS -> '0'
            FunctionCode.FC02_READ_DISCRETE_INPUTS -> '1'
            FunctionCode.FC04_READ_INPUT_REGISTERS -> '3'
            FunctionCode.FC03_READ_HOLDING_REGISTERS,
            FunctionCode.FC06_WRITE_SINGLE_REGISTER,
            FunctionCode.FC16_WRITE_MULTIPLE_REGISTERS -> '4'
        }
        return if (oneBased <= 9999) {
            String.format(Locale.ROOT, "%c%04d", prefix, oneBased)
        } else {
            String.format(Locale.ROOT, "%c%05d", prefix, oneBased)
        }
    }
}
