package com.app.quickpear.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders a standard QR code directly in Compose using Canvas.
 */
@Composable
fun QrCodeView(
    content: String,
    size: Dp = 200.dp,
    modifier: Modifier = Modifier,
    foreground: Color = Color(0xFF0F172A),
    background: Color = Color.White
) {
    val matrix = remember(content) {
        SimpleQrEncoder.encode(content)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size - 24.dp)) {
            val n = matrix.size
            if (n == 0) return@Canvas
            val cellSize = this.size.width / n

            for (r in 0 until n) {
                for (c in 0 until n) {
                    if (matrix[r][c]) {
                        drawRect(
                            color = foreground,
                            topLeft = Offset(c * cellSize, r * cellSize),
                            size = Size(cellSize + 0.5f, cellSize + 0.5f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Minimal self-contained ISO/IEC 18004 QR Code Model 2 encoder (Byte mode, Version 3..4).
 */
internal object SimpleQrEncoder {
    // Galois Field GF(256) tables for Reed-Solomon error correction
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            EXP[i + 255] = x
            LOG[x] = i
            x = (x shl 1) xor (if (x and 0x80 != 0) 0x11D else 0)
        }
        LOG[0] = 0
    }

    private fun gfMul(x: Int, y: Int): Int {
        if (x == 0 || y == 0) return 0
        return EXP[LOG[x] + LOG[y]]
    }

    private fun rsCompute(data: ByteArray, ecCount: Int): ByteArray {
        var gen = intArrayOf(1)
        for (i in 0 until ecCount) {
            val next = IntArray(gen.size + 1)
            for (j in gen.indices) {
                next[j] = next[j] xor gfMul(gen[j], EXP[i])
                next[j + 1] = next[j + 1] xor gen[j]
            }
            gen = next
        }

        val res = IntArray(ecCount)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor res[0]
            for (j in 0 until ecCount - 1) {
                res[j] = res[j + 1] xor gfMul(gen[ecCount - j], factor)
            }
            res[ecCount - 1] = gfMul(gen[1], factor)
        }
        return ByteArray(ecCount) { res[it].toByte() }
    }

    fun encode(text: String): Array<BooleanArray> {
        val dataBytes = text.encodeToByteArray()
        // Version 3: 29x29, total data codewords for L: 55 data bytes, 15 EC bytes
        // Version 4: 33x33, total data codewords for L: 80 data bytes, 20 EC bytes
        val version = if (dataBytes.size <= 53) 3 else 4
        val size = 17 + 4 * version
        val totalDataCodewords = if (version == 3) 55 else 80
        val ecCodewords = if (version == 3) 15 else 20

        // Bit stream: Mode (4 bits: 0100 for Byte) + Count (8 bits) + Data + Terminator + Pad
        val bitBuffer = mutableListOf<Int>()
        fun putBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bitBuffer.add((value ushr i) and 1)
            }
        }

        putBits(0x04, 4) // Byte mode
        putBits(dataBytes.size, 8)
        for (b in dataBytes) {
            putBits(b.toInt() and 0xFF, 8)
        }
        // Terminator
        val remainingBits = (totalDataCodewords * 8) - bitBuffer.size
        val terminatorLength = remainingBits.coerceAtMost(4).coerceAtLeast(0)
        putBits(0, terminatorLength)

        // Pad to byte
        while (bitBuffer.size % 8 != 0) {
            bitBuffer.add(0)
        }

        // Pad bytes (0xEC, 0x11)
        var padToggle = 0xEC
        while (bitBuffer.size < totalDataCodewords * 8) {
            putBits(padToggle, 8)
            padToggle = if (padToggle == 0xEC) 0x11 else 0xEC
        }

        val dataArr = ByteArray(totalDataCodewords)
        for (i in 0 until totalDataCodewords) {
            var b = 0
            for (j in 0 until 8) {
                b = (b shl 1) or bitBuffer[i * 8 + j]
            }
            dataArr[i] = b.toByte()
        }

        val ecArr = rsCompute(dataArr, ecCodewords)
        val finalCodewords = dataArr + ecArr

        // Build QR Grid
        val grid = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) }

        // Finder patterns
        fun placeFinder(top: Int, left: Int) {
            for (r in -1..7) {
                for (c in -1..7) {
                    val row = top + r
                    val col = left + c
                    if (row in 0 until size && col in 0 until size) {
                        reserved[row][col] = true
                        val isBlack = (r in 0..6 && (c == 0 || c == 6)) ||
                                (c in 0..6 && (r == 0 || r == 6)) ||
                                (r in 2..4 && c in 2..4)
                        grid[row][col] = isBlack
                    }
                }
            }
        }

        placeFinder(0, 0)
        placeFinder(0, size - 7)
        placeFinder(size - 7, 0)

        // Timing patterns
        for (i in 8 until size - 8) {
            val black = (i % 2 == 0)
            if (!reserved[6][i]) {
                reserved[6][i] = true
                grid[6][i] = black
            }
            if (!reserved[i][6]) {
                reserved[i][6] = true
                grid[i][6] = black
            }
        }

        // Dark module
        grid[4 * version + 9][8] = true
        reserved[4 * version + 9][8] = true

        // Alignment pattern
        val alignPos = if (version == 3) 22 else 26
        for (r in -2..2) {
            for (c in -2..2) {
                val row = alignPos + r
                val col = alignPos + c
                if (!reserved[row][col]) {
                    reserved[row][col] = true
                    grid[row][col] = (r == -2 || r == 2 || c == -2 || c == 2 || (r == 0 && c == 0))
                }
            }
        }

        // Reserve format info area
        for (i in 0..8) {
            reserved[8][i] = true
            reserved[i][8] = true
        }
        for (i in size - 8 until size) {
            reserved[8][i] = true
            reserved[i][8] = true
        }

        // Place data bits with Zig-Zag scan
        val totalBits = mutableListOf<Int>()
        for (b in finalCodewords) {
            for (i in 7 downTo 0) {
                totalBits.add((b.toInt() ushr i) and 1)
            }
        }

        var bitIndex = 0
        var col = size - 1
        var goingUp = true

        while (col > 0) {
            if (col == 6) col-- // skip vertical timing
            val rows = if (goingUp) (size - 1 downTo 0) else (0 until size)
            for (row in rows) {
                for (c in 0..1) {
                    val targetCol = col - c
                    if (!reserved[row][targetCol]) {
                        val bit = if (bitIndex < totalBits.size) totalBits[bitIndex++] else 0
                        // Mask 0: (row + col) % 2 == 0
                        val mask = (row + targetCol) % 2 == 0
                        grid[row][targetCol] = (bit == 1) xor mask
                    }
                }
            }
            col -= 2
            goingUp = !goingUp
        }

        // Format info bits for EC Level L, Mask 0 (Format info: 0x77C4)
        val formatBits = intArrayOf(1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0)
        // Draw format info around top-left finder
        for (i in 0..5) grid[8][i] = formatBits[i] == 1
        grid[8][7] = formatBits[6] == 1
        grid[8][8] = formatBits[7] == 1
        grid[7][8] = formatBits[8] == 1
        for (i in 9..14) grid[14 - i][8] = formatBits[i] == 1

        // Draw format info around top-right & bottom-left
        for (i in 0..7) grid[size - 1 - i][8] = formatBits[i] == 1
        for (i in 8..14) grid[8][size - 15 + i] = formatBits[i] == 1

        return grid
    }
}
