package io.github.mikai233.asteria.game.numeric

import io.github.mikai233.asteria.game.numeric.Fraction.Companion.fromBinary
import io.github.mikai233.asteria.game.numeric.Fraction.Companion.fromDecimal
import io.github.mikai233.asteria.game.numeric.Fraction.Companion.of
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * Exact rational number for gameplay formulas that must not lose precision between operations.
 *
 * `Fraction.of(1, 2) * Fraction.of(1, 3) / 2` stays as `1/12` until the caller explicitly converts it to an integer or
 * decimal value.
 *
 * Decimal factories such as [of] and [fromDecimal] interpret `Double` and `Float` by their decimal text, so `0.1`
 * becomes `1/10`. Use [fromBinary] only when the exact IEEE-754 floating-point value is required.
 */
class Fraction private constructor(
    /**
     * Reduced numerator. The sign is always carried by the numerator.
     */
    val numerator: BigInteger,
    /**
     * Reduced positive denominator.
     */
    val denominator: BigInteger,
) : Comparable<Fraction> {
    init {
        require(denominator.signum() > 0) { "fraction denominator must be positive" }
    }

    /**
     * Adds two exact fractions and returns a reduced result.
     */
    operator fun plus(other: Fraction): Fraction {
        return of(
            numerator * other.denominator + other.numerator * denominator,
            denominator * other.denominator,
        )
    }

    /**
     * Subtracts [other] and returns a reduced result.
     */
    operator fun minus(other: Fraction): Fraction {
        return of(
            numerator * other.denominator - other.numerator * denominator,
            denominator * other.denominator,
        )
    }

    /**
     * Multiplies two exact fractions and returns a reduced result.
     */
    operator fun times(other: Fraction): Fraction {
        return of(numerator * other.numerator, denominator * other.denominator)
    }

    /**
     * Divides by [other] and returns a reduced result.
     */
    operator fun div(other: Fraction): Fraction {
        require(other.numerator != BigInteger.ZERO) { "fraction divisor must not be zero" }
        return of(numerator * other.denominator, denominator * other.numerator)
    }

    /**
     * Returns the negated fraction.
     */
    operator fun unaryMinus(): Fraction = of(-numerator, denominator)

    /**
     * Adds an integer without converting the fraction to a decimal value.
     */
    operator fun plus(value: Int): Fraction = plus(value.toLong())

    /**
     * Adds an integer without converting the fraction to a decimal value.
     */
    operator fun plus(value: Long): Fraction = this + of(value)

    /**
     * Subtracts an integer without converting the fraction to a decimal value.
     */
    operator fun minus(value: Int): Fraction = minus(value.toLong())

    /**
     * Subtracts an integer without converting the fraction to a decimal value.
     */
    operator fun minus(value: Long): Fraction = this - of(value)

    /**
     * Multiplies by an integer without converting the fraction to a decimal value.
     */
    operator fun times(value: Int): Fraction = times(value.toLong())

    /**
     * Multiplies by an integer without converting the fraction to a decimal value.
     */
    operator fun times(value: Long): Fraction = this * of(value)

    /**
     * Divides by an integer without converting the fraction to a decimal value.
     */
    operator fun div(value: Int): Fraction = div(value.toLong())

    /**
     * Divides by an integer without converting the fraction to a decimal value.
     */
    operator fun div(value: Long): Fraction = this / of(value)

    /**
     * Returns true when this value is exactly zero.
     */
    fun isZero(): Boolean = numerator == BigInteger.ZERO

    /**
     * Converts this fraction to [Long] only if it is already an integer.
     *
     * Use [toLong] with an explicit [RoundingMode] when fractional values are expected.
     */
    fun toLongExact(): Long {
        require(numerator.mod(denominator) == BigInteger.ZERO) { "fraction $this is not an integer" }
        return numerator.divide(denominator).longValueExact()
    }

    /**
     * Converts this fraction to [Long] with the requested [roundingMode].
     *
     * This is an output boundary: precision can be lost here by design.
     */
    fun toLong(roundingMode: RoundingMode): Long {
        return toBigDecimal(MathContext(40, roundingMode))
            .setScale(0, roundingMode)
            .longValueExact()
    }

    /**
     * Converts this fraction to [BigDecimal] using [mathContext].
     *
     * This is an output boundary. Repeating decimals, such as `1/3`, are rounded according to [mathContext].
     */
    fun toBigDecimal(mathContext: MathContext = MathContext.DECIMAL128): BigDecimal {
        return BigDecimal(numerator).divide(BigDecimal(denominator), mathContext)
    }

    override fun compareTo(other: Fraction): Int {
        return (numerator * other.denominator).compareTo(other.numerator * denominator)
    }

    override fun equals(other: Any?): Boolean {
        return other is Fraction &&
                numerator == other.numerator &&
                denominator == other.denominator
    }

    override fun hashCode(): Int {
        var result = numerator.hashCode()
        result = 31 * result + denominator.hashCode()
        return result
    }

    override fun toString(): String {
        return if (denominator == BigInteger.ONE) {
            numerator.toString()
        } else {
            "$numerator/$denominator"
        }
    }

    companion object {
        /**
         * Exact zero.
         */
        val ZERO: Fraction = Fraction(BigInteger.ZERO, BigInteger.ONE)

        /**
         * Exact one.
         */
        val ONE: Fraction = Fraction(BigInteger.ONE, BigInteger.ONE)

        /**
         * Creates a fraction from the decimal text of [value].
         */
        fun of(value: Float): Fraction = fromDecimal(value)

        /**
         * Creates a fraction from the decimal text of [value].
         */
        fun of(value: Double): Fraction = fromDecimal(value)

        /**
         * Creates a fraction from a decimal value without losing precision.
         */
        fun of(value: BigDecimal): Fraction = fromDecimal(value)

        /**
         * Creates an integer fraction.
         */
        fun of(value: Int): Fraction = of(value.toLong())

        /**
         * Creates an integer fraction.
         */
        fun of(value: Long): Fraction = Fraction(BigInteger.valueOf(value), BigInteger.ONE)

        /**
         * Creates and reduces `numerator / denominator`.
         */
        fun of(
            numerator: Int,
            denominator: Int,
        ): Fraction = of(numerator.toLong(), denominator.toLong())

        /**
         * Creates and reduces `numerator / denominator`.
         */
        fun of(
            numerator: Long,
            denominator: Long,
        ): Fraction = of(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator))

        /**
         * Creates and reduces `numerator / denominator`.
         *
         * The denominator must be non-zero. Negative denominators are normalized so the sign is stored on the numerator.
         */
        fun of(
            numerator: BigInteger,
            denominator: BigInteger,
        ): Fraction {
            require(denominator != BigInteger.ZERO) { "fraction denominator must not be zero" }
            if (numerator == BigInteger.ZERO) {
                return ZERO
            }
            val sign = denominator.signum().toBigInteger()
            val normalizedNumerator = numerator * sign
            val normalizedDenominator = denominator.abs()
            val gcd = normalizedNumerator.abs().gcd(normalizedDenominator)
            return Fraction(normalizedNumerator / gcd, normalizedDenominator / gcd)
        }

        /**
         * Converts a decimal value into the exact fraction represented by its decimal text.
         *
         * This is usually the right choice for gameplay configs, because `0.1` becomes `1/10` instead of the binary
         * floating-point value near `0.1`.
         */
        fun fromDecimal(value: Float): Fraction = fromDecimal(value.toString())

        /**
         * Converts a decimal value into the exact fraction represented by its decimal text.
         *
         * This is usually the right choice for gameplay configs, because `0.1` becomes `1/10` instead of the binary
         * floating-point value near `0.1`.
         */
        fun fromDecimal(value: Double): Fraction = fromDecimal(value.toString())

        /**
         * Converts decimal text into an exact fraction.
         *
         * Scientific notation is accepted by [BigDecimal], so values like `1.25e2` are valid.
         */
        fun fromDecimal(value: String): Fraction = fromDecimal(value.toBigDecimal())

        /**
         * Converts a [BigDecimal] into an exact fraction.
         */
        fun fromDecimal(value: BigDecimal): Fraction {
            val normalized = value.stripTrailingZeros()
            val scale = normalized.scale()
            return if (scale <= 0) {
                of(normalized.toBigIntegerExact(), BigInteger.ONE)
            } else {
                of(
                    normalized.unscaledValue(),
                    BigInteger.TEN.pow(scale),
                )
            }
        }

        /**
         * Converts a floating-point value into a fraction that preserves its real binary value.
         *
         * Prefer [fromDecimal] for config-like values. Use this when the exact IEEE-754 value matters.
         */
        fun fromBinary(value: Float): Fraction = fromBinary(value.toDouble())

        /**
         * Converts a floating-point value into a fraction that preserves its real binary value.
         *
         * Prefer [fromDecimal] for config-like values. Use this when the exact IEEE-754 value matters.
         */
        fun fromBinary(value: Double): Fraction {
            require(value.isFinite()) { "fraction floating-point value must be finite" }
            return fromDecimal(BigDecimal(value))
        }
    }
}

private fun Int.toBigInteger(): BigInteger = BigInteger.valueOf(toLong())
