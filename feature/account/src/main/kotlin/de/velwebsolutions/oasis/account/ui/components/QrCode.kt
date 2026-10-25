package de.velwebsolutions.oasis.account.ui.components

import kotlin.math.abs
import kotlin.math.max

/**
 * A QR code (ISO/IEC 18004) of a text in byte mode with error correction level M – what Core Image's
 * CIQRCodeGenerator draws on iOS for the otpauth URL of the two-factor setup. Small enough to live here instead of
 * pulling in a library: versions 1–40, all eight masks tried, the one with the lowest penalty kept (after Project
 * Nayuki's reference implementation).
 */
class QrCode private constructor(val version: Int, val mask: Int, private val modules: Array<BooleanArray>) {
    /** Modules per side: 21 for version 1, four more per version. */
    val size: Int get() = modules.size

    /** Whether the module at column [x], row [y] is dark. */
    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    companion object {
        /** Encodes [text] as UTF-8 bytes; null when it does not fit into version 40. */
        fun encode(text: String): QrCode? {
            val data = text.toByteArray(Charsets.UTF_8)
            val version = (1..40).firstOrNull { dataCapacityBits(it) >= 4 + countBits(it) + data.size * 8 } ?: return null
            val codewords = addEccAndInterleave(dataCodewords(data, version), version)
            val builder = Builder(version)
            builder.drawFunctionPatterns()
            builder.drawCodewords(codewords)
            var best = -1
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                builder.applyMask(mask)
                builder.drawFormatBits(mask)
                val penalty = builder.penalty()
                if (penalty < bestPenalty) {
                    best = mask
                    bestPenalty = penalty
                }
                builder.applyMask(mask) // XOR again undoes it.
            }
            builder.applyMask(best)
            builder.drawFormatBits(best)
            return QrCode(version, best, builder.modules)
        }

        /** The 15 format bits of level M and [mask], BCH-protected and masked with 0x5412. */
        internal fun formatBits(mask: Int): Int {
            val data = (ECL_M_BITS shl 3) or mask
            var remainder = data
            repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
            return ((data shl 10) or remainder) xor 0x5412
        }

        /** The Reed–Solomon error correction bytes of [data] for [degree] ECC codewords. */
        internal fun reedSolomon(data: ByteArray, degree: Int): ByteArray {
            val divisor = reedSolomonDivisor(degree)
            val result = ByteArray(degree)
            for (byte in data) {
                val factor = (byte.toInt() xor result[0].toInt()) and 0xFF
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (index in result.indices) {
                    result[index] = (result[index].toInt() xor multiply(divisor[index].toInt() and 0xFF, factor)).toByte()
                }
            }
            return result
        }

        /** The data codewords of [version] for the byte segment of [data], terminated and padded. */
        internal fun dataCodewords(data: ByteArray, version: Int): ByteArray {
            val capacity = dataCapacityBits(version)
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(data.size, countBits(version))
            data.forEach { bits.append(it.toInt() and 0xFF, 8) }
            bits.append(0, minOf(4, capacity - bits.length))
            bits.append(0, (8 - bits.length % 8) % 8)
            var pad = 0xEC
            while (bits.length < capacity) {
                bits.append(pad, 8)
                pad = pad xor 0xEC xor 0x11
            }
            return bits.toBytes()
        }

        /** Splits the data into blocks, adds their error correction and interleaves them. */
        internal fun addEccAndInterleave(data: ByteArray, version: Int): ByteArray {
            val numBlocks = BLOCKS_M[version]
            val blockEccLength = ECC_PER_BLOCK_M[version]
            val rawCodewords = rawDataModules(version) / 8
            val numShortBlocks = numBlocks - rawCodewords % numBlocks
            val shortBlockLength = rawCodewords / numBlocks
            val blocks = ArrayList<ByteArray>()
            var offset = 0
            for (index in 0 until numBlocks) {
                val dataLength = shortBlockLength - blockEccLength + if (index < numShortBlocks) 0 else 1
                val blockData = data.copyOfRange(offset, offset + dataLength)
                offset += dataLength
                val ecc = reedSolomon(blockData, blockEccLength)
                val padded = if (index < numShortBlocks) blockData + 0.toByte() else blockData
                blocks += padded + ecc
            }
            val result = ByteArray(rawCodewords)
            var position = 0
            for (column in blocks[0].indices) {
                for ((index, block) in blocks.withIndex()) {
                    if (column != shortBlockLength - blockEccLength || index >= numShortBlocks) {
                        result[position++] = block[column]
                    }
                }
            }
            return result
        }

        internal fun rawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val numAlign = version / 7 + 2
                result -= (25 * numAlign - 10) * numAlign - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        internal fun dataCapacityBits(version: Int): Int = (rawDataModules(version) / 8 - ECC_PER_BLOCK_M[version] * BLOCKS_M[version]) * 8

        private fun countBits(version: Int): Int = if (version <= 9) 8 else 16

        private fun reedSolomonDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (index in result.indices) {
                    var value = multiply(result[index].toInt() and 0xFF, root)
                    if (index + 1 < result.size) value = value xor (result[index + 1].toInt() and 0xFF)
                    result[index] = value.toByte()
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        /** Multiplication in GF(2^8) with the QR polynomial 0x11D. */
        private fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (bit in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr bit) and 1) * x)
            }
            return z and 0xFF
        }

        /** Level M is encoded as 0 in the format bits (L = 1, M = 0, Q = 3, H = 2). */
        private const val ECL_M_BITS = 0

        private val ECC_PER_BLOCK_M = intArrayOf(
            -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
            26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28
        )
        private val BLOCKS_M = intArrayOf(
            -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
            17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49
        )
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val length: Int get() = bits.size

        fun append(value: Int, count: Int) {
            for (bit in count - 1 downTo 0) bits += ((value ushr bit) and 1) == 1
        }

        fun toBytes(): ByteArray {
            val result = ByteArray(bits.size / 8)
            bits.forEachIndexed { index, bit ->
                if (bit) result[index / 8] = (result[index / 8].toInt() or (0x80 ushr (index % 8))).toByte()
            }
            return result
        }
    }

    /** The module grid while it is drawn: function patterns are kept apart so data and masks leave them alone. */
    private class Builder(private val version: Int) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        private val isFunction = Array(size) { BooleanArray(size) }

        fun drawFunctionPatterns() {
            for (index in 0 until size) {
                setFunction(6, index, index % 2 == 0)
                setFunction(index, 6, index % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val positions = alignmentPositions()
            val last = positions.size - 1
            for (i in positions.indices) {
                for (j in positions.indices) {
                    val corner = (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)
                    if (!corner) drawAlignment(positions[i], positions[j])
                }
            }
            drawFormatBits(0)
            drawVersion()
        }

        fun drawFormatBits(mask: Int) {
            val bits = formatBits(mask)
            fun bit(index: Int) = ((bits ushr index) and 1) == 1
            for (index in 0..5) setFunction(8, index, bit(index))
            setFunction(8, 7, bit(6))
            setFunction(8, 8, bit(7))
            setFunction(7, 8, bit(8))
            for (index in 9 until 15) setFunction(14 - index, 8, bit(index))
            for (index in 0 until 8) setFunction(size - 1 - index, 8, bit(index))
            for (index in 8 until 15) setFunction(8, size - 15 + index, bit(index))
            setFunction(8, size - 8, true)
        }

        fun drawCodewords(data: ByteArray) {
            var index = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vertical in 0 until size) {
                    for (column in 0..1) {
                        val x = right - column
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vertical else vertical
                        if (!isFunction[y][x] && index < data.size * 8) {
                            modules[y][x] = ((data[index ushr 3].toInt() ushr (7 - (index and 7))) and 1) == 1
                            index++
                        }
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val invert = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
                }
            }
        }

        /** The penalty rules of the standard: runs, 2 × 2 blocks, finder-like patterns and the dark/light balance. */
        fun penalty(): Int {
            var result = 0
            for (line in 0 until size) {
                result += linePenalty { modules[line][it] }
                result += linePenalty { modules[it][line] }
            }
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val color = modules[y][x]
                    if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) result += 3
                }
            }
            val dark = modules.sumOf { row -> row.count { it } }
            val total = size * size
            val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += max(k, 0) * 10
            return result
        }

        private fun linePenalty(module: (Int) -> Boolean): Int {
            var result = 0
            var run = 1
            for (index in 1 until size) {
                if (module(index) == module(index - 1)) {
                    run++
                } else {
                    if (run >= 5) result += run - 2
                    run = 1
                }
            }
            if (run >= 5) result += run - 2
            // Finder-like 1:1:3:1:1 with four light modules on one side (outside the grid counts as light).
            fun at(index: Int) = if (index in 0 until size) module(index) else false
            for (start in -4 until size) {
                val core = at(start) && !at(start + 1) && at(start + 2) && at(start + 3) && at(start + 4) && !at(start + 5) && at(start + 6)
                if (!core) continue
                val lightBefore = (1..4).none { at(start - it) }
                val lightAfter = (1..4).none { at(start + 6 + it) }
                if (lightBefore || lightAfter) result += 40
            }
            return result
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var position = size - 7
            for (index in numAlign - 1 downTo 1) {
                result[index] = position
                position -= step
            }
            return result
        }

        private fun drawFinder(x: Int, y: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val distance = max(abs(dx), abs(dy))
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until size && yy in 0 until size) setFunction(xx, yy, distance != 2 && distance != 4)
                }
            }
        }

        private fun drawAlignment(x: Int, y: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) setFunction(x + dx, y + dy, max(abs(dx), abs(dy)) != 1)
            }
        }

        private fun drawVersion() {
            if (version < 7) return
            var remainder = version
            repeat(12) { remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25) }
            val bits = (version shl 12) or remainder
            for (index in 0 until 18) {
                val bit = ((bits ushr index) and 1) == 1
                val a = size - 11 + index % 3
                val b = index / 3
                setFunction(a, b, bit)
                setFunction(b, a, bit)
            }
        }

        private fun setFunction(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }
    }
}
