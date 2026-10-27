package id.controlengineer.modbus.protocol

import java.math.BigInteger
import java.util.Locale
import kotlin.math.abs

enum class Base(val radix: Int) { BIN(2), OCT(8), DEC(10), HEX(16) }

enum class BitWidth(val bits: Int) {
    B8(8), B16(16), B32(32), B64(64);

    val mask: ULong get() = if (bits == 64) ULong.MAX_VALUE else (1UL shl bits) - 1UL
    val bytes: Int get() = bits / 8
}

/**
 * Urutan byte gaya Modbus/PLC, dilihat dari urutan big-endian "ABCD".
 * swapBytes menukar byte di dalam tiap word 16-bit, swapWords membalik urutan word.
 */
enum class ByteOrder(val swapBytes: Boolean, val swapWords: Boolean) {
    ABCD(false, false),
    CDAB(false, true),
    BADC(true, false),
    DCBA(true, true),
}

class InvalidNumberException(message: String) : IllegalArgumentException(message)

/** Pola bit tanpa tanda pada lebar tertentu; semua tampilan diturunkan dari sini. */
data class Word(val bits: ULong, val width: BitWidth) {
    init {
        require(bits and width.mask.inv() == 0UL) { "bit di luar lebar ${width.bits}" }
    }

    val unsigned: ULong get() = bits

    val signed: Long
        get() {
            val shift = 64 - width.bits
            return (bits.toLong() shl shift) shr shift
        }

    fun format(base: Base): String = when (base) {
        Base.DEC -> unsigned.toString()
        else -> bits.toString(base.radix).uppercase().padStart(digitCount(base), '0')
    }

    private fun digitCount(base: Base): Int = when (base) {
        Base.BIN -> width.bits
        Base.OCT -> (width.bits + 2) / 3
        Base.HEX -> width.bits / 4
        Base.DEC -> 1
    }

    fun bit(index: Int): Boolean = (bits shr index) and 1UL == 1UL

    fun toggleBit(index: Int): Word {
        require(index in 0 until width.bits)
        return Word(bits xor (1UL shl index), width)
    }

    /** Byte big-endian, byte paling signifikan lebih dulu. */
    fun bytesBE(): List<Int> =
        (width.bytes - 1 downTo 0).map { ((bits shr (it * 8)) and 0xFFUL).toInt() }

    fun reorder(order: ByteOrder): Word {
        if (width == BitWidth.B8) return this
        var b = bytesBE()
        if (order.swapBytes) b = b.chunked(2).flatMap { it.reversed() }
        if (order.swapWords) b = b.chunked(2).reversed().flatten()
        return fromBytesBE(b, width)
    }

    fun toFloat32(): Float {
        require(width == BitWidth.B32)
        return Float.fromBits(bits.toInt())
    }

    fun toFloat64(): Double {
        require(width == BitWidth.B64)
        return Double.fromBits(bits.toLong())
    }

    /** BCD terkemas tanpa tanda; null bila ada nibble > 9. */
    fun bcdToDecimal(): Long? {
        var result = 0L
        for (i in width.bits / 4 - 1 downTo 0) {
            val nibble = ((bits shr (i * 4)) and 0xFUL).toInt()
            if (nibble > 9) return null
            result = result * 10 + nibble
        }
        return result
    }

    companion object {
        fun fromBytesBE(bytes: List<Int>, width: BitWidth): Word {
            require(bytes.size == width.bytes)
            var v = 0UL
            for (b in bytes) v = (v shl 8) or b.toULong()
            return Word(v, width)
        }

        fun float32(value: Float) = Word(value.toRawBits().toUInt().toULong(), BitWidth.B32)

        fun float64(value: Double) = Word(value.toRawBits().toULong(), BitWidth.B64)

        /** BCD terkemas tanpa tanda; null bila negatif atau digit melebihi lebar. */
        fun decimalToBcd(value: Long, width: BitWidth): Word? {
            if (value < 0) return null
            val digits = value.toString()
            if (digits.length > width.bits / 4) return null
            var v = 0UL
            for (c in digits) v = (v shl 4) or (c - '0').toULong()
            return Word(v, width)
        }

        /**
         * Membaca teks pada basis tertentu. Awalan 0x, 16#, 2#, 8# dan pemisah spasi/garis bawah diabaikan.
         * Desimal boleh negatif hanya bila [signed].
         */
        fun parse(text: String, base: Base, width: BitWidth, signed: Boolean): Word {
            var t = text.trim().replace("_", "").replace(" ", "")
            val prefixes = when (base) {
                Base.HEX -> listOf("0x", "0X", "16#")
                Base.BIN -> listOf("0b", "0B", "2#")
                Base.OCT -> listOf("8#")
                Base.DEC -> listOf("10#")
            }
            prefixes.firstOrNull { t.startsWith(it) }?.let { t = t.removePrefix(it) }
            if (t.isEmpty()) throw InvalidNumberException("kosong")
            val value = try {
                BigInteger(t, base.radix)
            } catch (e: NumberFormatException) {
                throw InvalidNumberException("karakter tidak valid")
            }
            val n = width.bits
            val two = BigInteger.TWO
            if (base == Base.DEC && signed) {
                val min = two.pow(n - 1).negate()
                val max = two.pow(n - 1) - BigInteger.ONE
                if (value < min || value > max) throw InvalidNumberException("di luar rentang")
                val wrapped = if (value.signum() < 0) value + two.pow(n) else value
                return Word(wrapped.toString().toULong(), width)
            }
            if (value.signum() < 0) throw InvalidNumberException("negatif")
            if (value.bitLength() > n) throw InvalidNumberException("di luar rentang")
            return Word(value.toString().toULong(), width)
        }
    }
}

/** Escape sederhana agar teks dengan tab, baris baru, atau backslash aman disimpan per baris. */
object TextCodec {
    fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    fun unescape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '\\' -> append('\\')
                    't' -> append('\t')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> {
                        append('\\')
                        append(next)
                    }
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}

/** Skala linear: memetakan nilai raw [rawMin..rawMax] ke satuan teknik [engMin..engMax]. */
data class LinearScale(
    val rawMin: Double = 0.0,
    val rawMax: Double = 27648.0,
    val engMin: Double = 0.0,
    val engMax: Double = 100.0,
    val unit: String = "",
) {
    init {
        require(rawMax != rawMin) { "rawMin dan rawMax tidak boleh sama" }
    }

    fun scale(raw: Double): Double =
        engMin + (raw - rawMin) * (engMax - engMin) / (rawMax - rawMin)

    fun unscale(eng: Double): Double {
        require(engMax != engMin) { "engMin dan engMax tidak boleh sama" }
        return rawMin + (eng - engMin) * (rawMax - rawMin) / (engMax - engMin)
    }

    fun formatScaled(raw: Double, decimals: Int = 3): String {
        val v = scale(raw)
        var s = String.format(Locale.ROOT, "%.${decimals}f", v)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        if (s == "-0") s = "0"
        return if (unit.isBlank()) s else "$s $unit"
    }
}

/** Hasil dekode satu register 16-bit ke berbagai representasi standar. */
data class DecodedRegister(
    val address: Int,
    val rawWord: Int,
    val scale: LinearScale? = null,
) {
    private val word16 = Word((rawWord and 0xFFFF).toULong(), BitWidth.B16)

    val uint16: Int get() = word16.unsigned.toInt()
    val int16: Int get() = word16.signed.toInt()
    val hex: String get() = "0x" + word16.format(Base.HEX)
    val binary: String get() = word16.format(Base.BIN).chunked(4).joinToString(" ")
    val bcd: Long? get() = word16.bcdToDecimal()
    val scaled: Double? get() = scale?.scale(int16.toDouble())
    val scaledText: String? get() = scale?.formatScaled(int16.toDouble())
}

/** Nilai 32-bit untuk satu urutan byte tertentu. */
data class OrderedValue32(
    val order: ByteOrder,
    val uint32: UInt,
    val int32: Int,
    val float32: Float,
    val hex: String,
) {
    val floatText: String
        get() = if (float32.isNaN() || float32.isInfinite()) {
            float32.toString()
        } else if (float32 != 0f && (abs(float32) >= 1e7f || abs(float32) < 1e-4f)) {
            // Nilai sangat besar/kecil (sering akibat urutan byte salah) pakai notasi ilmiah agar ringkas
            val (mant, exp) = String.format(Locale.ROOT, "%.6E", float32).split('E')
            val m = if (mant.contains('.')) mant.trimEnd('0').trimEnd('.') else mant
            "${m}E${exp.toInt()}"
        } else {
            var s = String.format(Locale.ROOT, "%.6f", float32)
            if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
            if (s == "-0") "0" else s
        }
}

/**
 * Dekoder pasangan register 16-bit berurutan (regHigh di alamat N, regLow di alamat N+1)
 * ke Int32, UInt32, dan Float32 dalam keempat urutan byte (ABCD, CDAB, BADC, DCBA).
 */
data class DecodedRegisterPair(
    val startAddress: Int,
    val regHigh: Int,
    val regLow: Int,
) {
    private val baseWord32: Word = Word(
        (((regHigh and 0xFFFF).toULong() shl 16) or (regLow and 0xFFFF).toULong()),
        BitWidth.B32,
    )

    fun forOrder(order: ByteOrder): OrderedValue32 {
        val reordered = baseWord32.reorder(order)
        return OrderedValue32(
            order = order,
            uint32 = reordered.unsigned.toUInt(),
            int32 = reordered.signed.toInt(),
            float32 = reordered.toFloat32(),
            hex = "0x" + reordered.format(Base.HEX),
        )
    }

    val allOrders: List<OrderedValue32>
        get() = ByteOrder.entries.map { forOrder(it) }
}

object RegisterDecoder {
    fun decodeRegisters(
        startAddress: Int,
        registers: List<Int>,
        scale: LinearScale? = null,
    ): List<DecodedRegister> =
        registers.mapIndexed { idx, raw ->
            DecodedRegister(address = startAddress + idx, rawWord = raw and 0xFFFF, scale = scale)
        }

    fun decodePairs(
        startAddress: Int,
        registers: List<Int>,
    ): List<DecodedRegisterPair> {
        if (registers.size < 2) return emptyList()
        return (0 until registers.size - 1).map { idx ->
            DecodedRegisterPair(
                startAddress = startAddress + idx,
                regHigh = registers[idx] and 0xFFFF,
                regLow = registers[idx + 1] and 0xFFFF,
            )
        }
    }

    /** Mengubah nilai Float32 menjadi 2 register 16-bit sesuai urutan byte yang dipilih. */
    fun encodeFloat32(value: Float, order: ByteOrder = ByteOrder.ABCD): List<Int> {
        val reordered = Word.float32(value).reorder(order)
        val bits = reordered.unsigned
        val reg0 = ((bits shr 16) and 0xFFFFUL).toInt()
        val reg1 = (bits and 0xFFFFUL).toInt()
        return listOf(reg0, reg1)
    }

    /** Mengubah nilai Int32 menjadi 2 register 16-bit sesuai urutan byte yang dipilih. */
    fun encodeInt32(value: Int, order: ByteOrder = ByteOrder.ABCD): List<Int> {
        val w = Word(value.toUInt().toULong(), BitWidth.B32).reorder(order)
        val bits = w.unsigned
        val reg0 = ((bits shr 16) and 0xFFFFUL).toInt()
        val reg1 = (bits and 0xFFFFUL).toInt()
        return listOf(reg0, reg1)
    }
}

/** Sumber label status Pro dengan urutan prioritas: lifetime > rewarded/trial yang berakhir paling lama. */
enum class ProLabelSource { LIFETIME, TRIAL, REWARDED, NONE }

/** Aturan perhitungan hak akses Pro (pembelian seumur hidup, rewarded 24 jam, atau trial 3 hari). */
object ProEntitlement {
    const val HOUR_MS: Long = 60L * 60L * 1000L
    const val DAY_MS: Long = 24L * HOUR_MS
    const val REWARD_DURATION_MS: Long = 24L * HOUR_MS
    const val TRIAL_DURATION_MS: Long = 3L * DAY_MS

    fun clampRewardedUntil(storedUntilMillis: Long, nowMillis: Long): Long {
        if (storedUntilMillis <= nowMillis) return 0L
        return minOf(storedUntilMillis, nowMillis + REWARD_DURATION_MS)
    }

    fun isTrialActive(trialStartMillis: Long, nowMillis: Long): Boolean {
        if (trialStartMillis <= 0L) return false
        if (nowMillis < trialStartMillis) return false
        return nowMillis < trialStartMillis + TRIAL_DURATION_MS
    }

    fun isTrialExpired(trialStartMillis: Long, nowMillis: Long): Boolean =
        trialStartMillis > 0L && !isTrialActive(trialStartMillis, nowMillis)

    fun trialEndMillis(trialStartMillis: Long, nowMillis: Long): Long =
        if (isTrialActive(trialStartMillis, nowMillis)) trialStartMillis + TRIAL_DURATION_MS else 0L

    fun trialRemainingMillis(trialStartMillis: Long, nowMillis: Long): Long =
        if (isTrialActive(trialStartMillis, nowMillis)) (trialStartMillis + TRIAL_DURATION_MS) - nowMillis else 0L

    fun trialDaysLeft(remainingMillis: Long): Int =
        if (remainingMillis <= 0L) 0 else ((remainingMillis + DAY_MS - 1L) / DAY_MS).toInt()

    fun trialHoursLeft(remainingMillis: Long): Int =
        if (remainingMillis <= 0L) 0 else ((remainingMillis + HOUR_MS - 1L) / HOUR_MS).coerceAtLeast(1L).toInt()

    fun isProActive(
        lifetime: Boolean,
        rewardedUntilMillis: Long,
        nowMillis: Long,
        trialStartMillis: Long = 0L,
    ): Boolean =
        lifetime ||
            clampRewardedUntil(rewardedUntilMillis, nowMillis) > nowMillis ||
            isTrialActive(trialStartMillis, nowMillis)

    fun labelSource(
        lifetime: Boolean,
        rewardedUntilMillis: Long,
        trialStartMillis: Long,
        nowMillis: Long,
    ): ProLabelSource {
        if (lifetime) return ProLabelSource.LIFETIME
        val rewardedEnd = clampRewardedUntil(rewardedUntilMillis, nowMillis)
        val trialEnd = trialEndMillis(trialStartMillis, nowMillis)
        return when {
            trialEnd > nowMillis && trialEnd >= rewardedEnd -> ProLabelSource.TRIAL
            rewardedEnd > nowMillis -> ProLabelSource.REWARDED
            else -> ProLabelSource.NONE
        }
    }

    fun nextExpiryMillis(
        lifetime: Boolean,
        rewardedUntilMillis: Long,
        trialStartMillis: Long,
        nowMillis: Long,
    ): Long {
        if (lifetime) return 0L
        val rewardedEnd = clampRewardedUntil(rewardedUntilMillis, nowMillis)
        val trialEnd = trialEndMillis(trialStartMillis, nowMillis)
        return when {
            rewardedEnd > nowMillis && trialEnd > nowMillis -> minOf(rewardedEnd, trialEnd)
            rewardedEnd > nowMillis -> rewardedEnd
            trialEnd > nowMillis -> trialEnd
            else -> 0L
        }
    }
}
