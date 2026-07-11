package com.deatrg.dnsfilter.data.worker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BlocklistUpdateAlarmSchedulerTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun beforeNoonSchedulesSameDay() {
        val now = ZonedDateTime.of(2026, 7, 11, 9, 30, 0, 0, zone)

        val next = BlocklistUpdateAlarmScheduler.nextUpdateTime(now)

        assertEquals(ZonedDateTime.of(2026, 7, 11, 12, 0, 0, 0, zone), next)
    }

    @Test
    fun afterNoonSchedulesNextDay() {
        val now = ZonedDateTime.of(2026, 7, 11, 18, 0, 0, 0, zone)

        val next = BlocklistUpdateAlarmScheduler.nextUpdateTime(now)

        assertEquals(ZonedDateTime.of(2026, 7, 12, 12, 0, 0, 0, zone), next)
    }

    @Test
    fun exactlyAtNoonSchedulesNextDay() {
        val now = ZonedDateTime.of(2026, 7, 11, 12, 0, 0, 0, zone)

        val next = BlocklistUpdateAlarmScheduler.nextUpdateTime(now)

        assertEquals(ZonedDateTime.of(2026, 7, 12, 12, 0, 0, 0, zone), next)
    }
}
