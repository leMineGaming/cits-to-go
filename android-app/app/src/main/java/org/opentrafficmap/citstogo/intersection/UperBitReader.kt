package org.opentrafficmap.citstogo.intersection

internal class UperBitReader(private val bytes: ByteArray, byteOffset: Int = 0) {
    private var bitOffset = byteOffset * 8

    val remainingBits: Int get() = bytes.size * 8 - bitOffset

    fun bit(): Boolean = bits(1) != 0L

    fun bits(width: Int): Long {
        require(width in 0..64)
        if (remainingBits < width) throw IntersectionDecodeException("PER payload ended early")
        var value = 0L
        repeat(width) {
            val byte = bytes[bitOffset / 8].toInt() and 0xff
            value = (value shl 1) or ((byte ushr (7 - bitOffset % 8)) and 1).toLong()
            bitOffset += 1
        }
        return value
    }

    fun constrained(minimum: Long, maximum: Long): Long {
        val range = maximum - minimum + 1
        val width = if (range <= 1) 0 else 64 - java.lang.Long.numberOfLeadingZeros(range - 1)
        return minimum + bits(width)
    }

    fun signedConstrained(minimum: Int, maximum: Int): Int =
        constrained(minimum.toLong(), maximum.toLong()).toInt()

    fun sequenceLength(minimum: Int, maximum: Int): Int =
        constrained(minimum.toLong(), maximum.toLong()).toInt()

    fun skipBits(width: Int) {
        require(width >= 0)
        if (remainingBits < width) throw IntersectionDecodeException("PER payload ended early")
        bitOffset += width
    }

    fun skipNormallySmallLength() {
        if (!bit()) {
            skipBits(6)
        } else {
            val octets = bits(7).toInt()
            skipBits(octets * 8)
        }
    }

    fun skipOctets(octets: Int) {
        skipBits(octets * 8)
    }

    /** An unconstrained length determinant of up to 16383; fragmented lengths are not supported. */
    fun lengthDeterminant(): Int = when {
        !bit() -> bits(7).toInt()
        !bit() -> bits(14).toInt()
        else -> throw IntersectionDecodeException("Fragmented PER lengths are not supported")
    }

    /**
     * Skips the extension additions of a SEQUENCE whose extension bit is set: the bitmap of the present additions
     * after its normally small length, then each present addition as an open type.
     */
    fun skipExtensionAdditions() {
        val count = if (!bit()) bits(6).toInt() + 1 else lengthDeterminant()
        val present = (1..count).count { bit() }
        repeat(present) { skipOctets(lengthDeterminant()) }
    }
}

class IntersectionDecodeException(message: String) : Exception(message)
