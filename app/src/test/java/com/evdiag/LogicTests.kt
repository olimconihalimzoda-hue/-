package com.evdiag

import com.evdiag.domain.Capacity
import com.evdiag.domain.Level
import com.evdiag.domain.Verdict
import com.evdiag.obd.Dtc
import com.evdiag.profile.CommandGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {
    @Test
    fun goodBattery() {
        val v = Verdict.evaluate(95.0, 12.0, emptyList(), true)
        assertEquals(Level.GREEN, v.level)
    }

    @Test
    fun noBatteryDataIsGray() {
        val v = Verdict.evaluate(null, null, emptyList(), false)
        assertEquals(Level.GRAY, v.level)
    }

    @Test
    fun noBatteryDataWithGenericDtcIsYellow() {
        val v = Verdict.evaluate(null, null, listOf("U0100"), true)
        assertEquals(Level.YELLOW, v.level)
    }

    @Test
    fun wornBatteryIsYellowThenRed() {
        assertEquals(Level.YELLOW, Verdict.evaluate(85.0, 10.0, emptyList(), true).level)
        assertEquals(Level.RED, Verdict.evaluate(75.0, 10.0, emptyList(), true).level)
    }

    @Test
    fun cellSpreadThresholds() {
        assertEquals(Level.GREEN, Verdict.evaluate(95.0, 30.0, emptyList(), true).level)
        assertEquals(Level.YELLOW, Verdict.evaluate(95.0, 50.0, emptyList(), true).level)
        assertEquals(Level.RED, Verdict.evaluate(95.0, 120.0, emptyList(), true).level)
    }

    @Test
    fun highVoltageDtcIsRedEvenIfBatteryLooksFine() {
        val v = Verdict.evaluate(98.0, 5.0, listOf("P0A80"), true)
        assertEquals(Level.RED, v.level)
        assertTrue(v.reasons.any { it.contains("P0A80") })
    }

    @Test
    fun duplicateUdsSuffixIsCountedOnce() {
        val v = Verdict.evaluate(98.0, 5.0, listOf("P0A80", "P0A80-00"), true)
        assertEquals(1, Regex("P0A80").findAll(v.reasons.joinToString(" ")).count())
        assertEquals("Ошибки высоковольтной системы", v.title)
    }

    @Test
    fun problemsComeBeforeGoodNews() {
        val v = Verdict.evaluate(95.0, 10.0, listOf("U0100"), true)
        assertTrue(v.reasons.first().contains("U0100"))
    }

    @Test
    fun reasonsAreNeverEmpty() {
        assertTrue(Verdict.evaluate(null, null, emptyList(), false).reasons.isNotEmpty())
    }
}

class CapacityTest {
    @Test
    fun estimatesCapacityAndSoh() {
        // 20 кВт·ч за подъём SOC с 20% до 70% (50 п.п.) => 40 кВт·ч полной ёмкости
        val e = Capacity.estimate(20.0, 20.0, 70.0, 50.0)
        assertNotNull(e)
        assertEquals(40.0, e!!.kwh, 1e-9)
        assertEquals(80.0, e.sohPercent!!, 1e-9)
    }

    @Test
    fun dischargeWorksToo() {
        val e = Capacity.estimate(-10.0, 80.0, 60.0, null)
        assertNotNull(e)
        assertEquals(50.0, e!!.kwh, 1e-9)
        assertNull(e.sohPercent)
    }

    @Test
    fun smallSocChangeGivesNoEstimate() {
        assertNull(Capacity.estimate(1.0, 50.0, 53.0, 40.0))
    }

    @Test
    fun signMismatchGivesNoEstimate() {
        // SOC вырос, а энергия «отрицательная»: данные противоречивы
        assertNull(Capacity.estimate(-5.0, 20.0, 40.0, 40.0))
    }
}

class CommandGuardTest {
    @Test
    fun readCommandsAreAllowed() {
        for (c in listOf("010D", "0902", "03", "07", "0A", "1902FF", "220101", "22F190", "2101")) {
            assertTrue("должна быть разрешена: $c", CommandGuard.isSafeRequest(c))
        }
    }

    @Test
    fun writeAndControlCommandsAreBlocked() {
        // 04 сброс ошибок, 10 сессия, 11 сброс блока, 14 очистка DTC, 27 доступ, 2E запись, 2F управление,
        // 31 запуск рутины, 34/36 загрузка, 3E, 85
        for (c in listOf("04", "1003", "1101", "14FFFFFF", "2701", "2E0101", "2F0101", "31010203", "3401", "3601", "3E00", "8502")) {
            assertFalse("должна быть заблокирована: $c", CommandGuard.isSafeRequest(c))
        }
    }

    @Test
    fun malformedCommandsAreBlocked() {
        for (c in listOf("", "0", "ZZ", "010", "22 01 01", "2201;2E01", "22" + "01".repeat(20))) {
            assertFalse("должна быть заблокирована: '$c'", CommandGuard.isSafeRequest(c))
        }
    }

    @Test
    fun onlyHarmlessAtCommandsAreAllowedInProfiles() {
        for (c in listOf("ATSH79B", "ATFCSH79B", "ATFCSD300000", "ATFCSM1", "ATCRA7BB", "atsh7e4")) {
            assertTrue("должна быть разрешена: $c", CommandGuard.isSafePre(c))
        }
        for (c in listOf("ATZ", "ATSP6", "ATWS", "ATPP FF SV 00", "2E0101", "ATSH" + "0".repeat(30))) {
            assertFalse("должна быть заблокирована: $c", CommandGuard.isSafePre(c))
        }
    }
}

class DtcDescribeTest {
    @Test
    fun knownAndUnknownCodes() {
        assertTrue(Dtc.describe("P0A80").contains("батаре"))
        assertEquals("Описание не найдено", Dtc.describe("P1234"))
    }
}
