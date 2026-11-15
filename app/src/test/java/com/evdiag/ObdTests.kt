package com.evdiag

import com.evdiag.obd.Dtc
import com.evdiag.obd.Elm327
import com.evdiag.obd.IsoTp
import com.evdiag.obd.Reply
import com.evdiag.profile.Formula
import com.evdiag.transport.SimulatorTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoTpTest {
    @Test
    fun singleFrame() {
        val d = IsoTp.parse("7E8 03 41 0D 32 00 00 00 00").single() as Reply.Data
        assertEquals("7E8", d.ecu)
        assertArrayEquals(intArrayOf(0x41, 0x0D, 0x32), d.bytes)
    }

    @Test
    fun multiFrameIsAssembledAndTruncated() {
        val text = "7EC 10 0A 62 01 01 11 22 33\r\n7EC 21 44 55 66 77 00 00 00"
        val d = IsoTp.parse(text).single() as Reply.Data
        assertArrayEquals(intArrayOf(0x62, 1, 1, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77), d.bytes)
    }

    @Test
    fun negativeResponse() {
        val n = IsoTp.parse("7E8 03 7F 22 31 00 00 00 00").single() as Reply.Negative
        assertEquals(0x22, n.sid)
        assertEquals(0x31, n.code)
    }

    @Test
    fun noDataIsError() {
        val e = IsoTp.parse("NO DATA").single() as Reply.Error
        assertEquals("NO DATA", e.text)
    }

    @Test
    fun searchingLineIsIgnored() {
        val r = IsoTp.parse("SEARCHING...\n7E8 03 41 0D 32")
        assertTrue(r.single() is Reply.Data)
    }

    @Test
    fun twoEcusGiveTwoReplies() {
        val r = IsoTp.parse("7E8 03 41 0D 32\n7E9 03 41 0D 33")
        assertEquals(2, r.size)
    }
}

class DtcTest {
    @Test
    fun decode() {
        assertEquals("P0A80", Dtc.decode(0x0A, 0x80))
        assertEquals("U0100", Dtc.decode(0xC1, 0x00))
        assertEquals("C0320", Dtc.decode(0x43, 0x20))
    }

    @Test
    fun obdModeWithCountByte() {
        val r = Dtc.fromObdMode(intArrayOf(0x43, 0x02, 0x0A, 0x80, 0xC1, 0x00))
        assertEquals(listOf("P0A80", "U0100"), r)
    }

    @Test
    fun obdModeWithoutCountByteAndEmpty() {
        assertEquals(listOf("P0A80"), Dtc.fromObdMode(intArrayOf(0x43, 0x0A, 0x80)))
        assertTrue(Dtc.fromObdMode(intArrayOf(0x43, 0x00)).isEmpty())
    }

    @Test
    fun uds() {
        val r = Dtc.fromUds(intArrayOf(0x59, 0x02, 0xFF, 0x0A, 0x80, 0x00, 0x09)).single()
        assertEquals("P0A80", r.code)
        assertEquals(0, r.ftb)
        assertEquals(9, r.status)
    }
}

class FormulaTest {
    @Test
    fun standardPids() {
        assertEquals(20.0, Formula("b(2)-40").eval(intArrayOf(0x41, 0x46, 0x3C))!!, 1e-9)
        assertEquals(12.6, Formula("u16(2)/1000").eval(intArrayOf(0x41, 0x42, 0x31, 0x38))!!, 1e-9)
    }

    @Test
    fun signedAndPrecedence() {
        assertEquals(-2.0, Formula("s16(0)").eval(intArrayOf(0xFF, 0xFE))!!, 1e-9)
        assertEquals(14.0, Formula("2+3*4").eval(intArrayOf())!!, 1e-9)
        assertEquals(20.0, Formula("(2+3)*4").eval(intArrayOf())!!, 1e-9)
    }

    @Test
    fun repeatVariable() {
        assertEquals(772.0, Formula("u16(1+i*2)").eval(intArrayOf(0, 1, 2, 3, 4), 1)!!, 1e-9)
    }

    @Test
    fun errorsGiveNull() {
        assertNull(Formula("b(9)").eval(intArrayOf(1, 2, 3)))
        assertNull(Formula("b(").eval(intArrayOf(1, 2, 3)))
        assertNull(Formula("foo(1)").eval(intArrayOf(1, 2, 3)))
    }
}

class SimulatorTest {
    private fun connected(): Elm327 = Elm327(SimulatorTransport()).also { runBlocking { it.init() } }

    @Test
    fun vin() {
        runBlocking {
            val e = connected()
            e.selectEcu("7DF", null)
            val d = e.query("0902").single() as Reply.Data
            val vin = d.bytes.drop(3).map { it.toChar() }.joinToString("")
            assertEquals(SimulatorTransport.VIN, vin)
        }
    }

    @Test
    fun standardPid() {
        runBlocking {
            val e = connected()
            e.selectEcu("7DF", null)
            val d = e.query("010D").single() as Reply.Data
            assertEquals(0x41, d.bytes[0])
            assertEquals(0x0D, d.bytes[1])
        }
    }

    @Test
    fun unsupportedPidIsNoData() {
        runBlocking {
            val e = connected()
            e.selectEcu("7DF", null)
            assertTrue(e.query("0100").single() is Reply.Error)
        }
    }

    @Test
    fun dtcReadAndClear() {
        runBlocking {
            val e = connected()
            e.selectEcu("7DF", null)
            val first = (e.query("03").single() as Reply.Data).bytes
            assertEquals(listOf("P0A80", "U0100"), Dtc.fromObdMode(first))
            e.query("04")
            val after = (e.query("03").single() as Reply.Data).bytes
            assertTrue(Dtc.fromObdMode(after).isEmpty())
        }
    }

    @Test
    fun batteryMultiFrameAndFormulas() {
        runBlocking {
            val e = connected()
            e.selectEcu("7E4", "7EC")
            val d = e.query("220101").single() as Reply.Data
            assertEquals(34, d.bytes.size)
            val soc = Formula("b(3)/2").eval(d.bytes)
            assertNotNull(soc)
            assertTrue(soc!! in 60.0..80.0)
            assertEquals(91.0, Formula("b(4)").eval(d.bytes)!!, 1e-9)
            assertEquals(3.7, Formula("u16(10+i*2)/1000").eval(d.bytes, 0)!!, 0.01)
            assertEquals(3.724, Formula("u16(10+i*2)/1000").eval(d.bytes, 11)!!, 0.01)
        }
    }

    @Test
    fun udsDtc() {
        runBlocking {
            val e = connected()
            e.selectEcu("7E4", "7EC")
            val d = e.query("1902FF").single() as Reply.Data
            assertEquals("P0A80", Dtc.fromUds(d.bytes).single().code)
        }
    }
}
