package com.vantserg.psacanlab.can

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CanLineParserTest {
    @Test
    fun parsesFrameWithoutLeadingZeros() {
        val frame = CanLineParser.parse("1A1\t8\t80\tB\t80\t0\t0\t0\t0\t0")
        assertNotNull(frame)
        assertEquals(0x1A1, frame!!.id)
        assertArrayEquals(intArrayOf(0x80, 0x0B, 0x80, 0, 0, 0, 0, 0), frame.data)
    }

    @Test
    fun parsesShortFrameAndEmptyFrame() {
        assertArrayEquals(intArrayOf(0xEF), CanLineParser.parse("1A5\t1\tEF")!!.data)
        assertEquals(0, CanLineParser.parse("36\t0")!!.dlc)
    }

    @Test
    fun rejectsTextLines() {
        assertNull(CanLineParser.parse("MODE SNIFF"))
        assertNull(CanLineParser.parse("STATE IGN=1 REV=0"))
        assertNull(CanLineParser.parse("ERR BSI_PRESENT"))
        assertNull(CanLineParser.parse("# message 5"))
    }

    @Test
    fun rejectsBrokenFrames() {
        assertNull(CanLineParser.parse("1A1\t8\t80\tB"))          // байтов меньше, чем DLC
        assertNull(CanLineParser.parse("1A1\t2\t80\tB\t1"))       // байтов больше, чем DLC
        assertNull(CanLineParser.parse("800\t1\t0"))              // ID больше 0x7FF
        assertNull(CanLineParser.parse("1A1\t9\t0\t0\t0\t0\t0\t0\t0\t0\t0"))
        assertNull(CanLineParser.parse("1A1\t1\t100"))            // байт больше 0xFF
        assertNull(CanLineParser.parse("1G1\t1\t0"))              // не HEX
    }
}

class FrameTableTest {
    private fun frame(id: Int, vararg data: Int) = CanFrame(id, data)

    @Test
    fun countsFramesAndMeasuresPeriod() {
        val table = FrameTable()
        table.onFrame(frame(0x0F6, 1, 2), 1000)
        table.onFrame(frame(0x0F6, 1, 2), 1500)
        val stat = table.snapshot().single()
        assertEquals(2, stat.count)
        assertEquals(500, stat.period)
        assertEquals(2, stat.dlc)
    }

    @Test
    fun marksOnlyChangedBytes() {
        val table = FrameTable()
        table.onFrame(frame(0x128, 0x10, 0x20), 1000)
        table.onFrame(frame(0x128, 0x10, 0x21), 1200)
        val stat = table.snapshot().single()
        assertEquals(0, stat.changedAt[0])
        assertEquals(1200, stat.changedAt[1])
    }

    @Test
    fun firstFrameIsNotAChange() {
        val table = FrameTable()
        table.onFrame(frame(0x036, 0x0E), 1000)
        assertEquals(0, table.snapshot().single().changedAt[0])
    }

    @Test
    fun snapshotIsSortedAndIndependent() {
        val table = FrameTable()
        table.onFrame(frame(0x221, 1), 10)
        table.onFrame(frame(0x036, 2), 20)
        val snapshot = table.snapshot()
        assertEquals(listOf(0x036, 0x221), snapshot.map { it.id })
        table.onFrame(frame(0x036, 3), 30)
        assertEquals(2, snapshot[0].data[0])
        table.clear()
        assertEquals(0, table.snapshot().size)
    }
}
