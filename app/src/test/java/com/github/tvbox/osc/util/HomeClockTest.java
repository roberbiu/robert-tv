package com.github.tvbox.osc.util;

import org.junit.Test;

import java.time.Instant;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

public class HomeClockTest {
    @Test
    public void showsBeijingTimeRegardlessOfDeviceZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            long timestamp = Instant.parse("2026-10-29T01:53:00Z").toEpochMilli();
            for (String deviceZone : new String[]{"GMT", "America/Los_Angeles", "Asia/Tokyo"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(deviceZone));
                assertEquals("2026/10/29  周四  09:53", HomeClock.format(timestamp));
                assertEquals(deviceZone, TimeZone.getDefault().getID());
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void dateAndWeekdayRollOverAtBeijingMidnight() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
            assertEquals("2026/10/29  周四  00:05",
                    HomeClock.format(Instant.parse("2026-10-28T16:05:00Z").toEpochMilli()));
            assertEquals("2027/01/01  周五  00:05",
                    HomeClock.format(Instant.parse("2026-12-31T16:05:00Z").toEpochMilli()));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
