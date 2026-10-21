package com.udata.harness.common.support;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleTimingTest {
    private Map<String, Object> spec(String kind) {
        Map<String, Object> spec = new LinkedHashMap<>(); spec.put("kind", kind); spec.put("timezone", "Asia/Shanghai");
        spec.put("time", "09:00"); spec.put("weekday", 1); spec.put("intervalMinutes", "5"); return spec;
    }
    @Test void usesStoredTimezoneAndAdvancesPastAnExactDailyBoundary() {
        Map<String, Object> daily = spec("daily");
        long now = Instant.parse("2026-10-21T01:00:00Z").toEpochMilli();
        ScheduleTiming.validate(daily, now);
        assertEquals(Instant.parse("2026-10-22T01:00:00Z").toEpochMilli(), ScheduleTiming.next(daily, now, 0));
        Map<String, Object> weekly = spec("weekly"); ScheduleTiming.validate(weekly, now);
        assertEquals(Instant.parse("2026-10-26T01:00:00Z").toEpochMilli(), ScheduleTiming.next(weekly, now, 0));
    }
    @Test void keepsIntervalCadenceWhileSkippingMissedOccurrences() {
        Map<String, Object> interval = spec("interval"); ScheduleTiming.validate(interval, 1000);
        assertEquals(301000, ScheduleTiming.next(interval, 1000, 0));
        assertEquals(1201000, ScheduleTiming.next(interval, 1001000, 301000));
    }
    @Test void onceHasNoFollowingOccurrenceAndBadValuesAreRejected() {
        Map<String, Object> once = spec("once"); once.put("at", "2030-01-01T09:00"); ScheduleTiming.validate(once, 0);
        long expected = Instant.parse("2030-01-01T01:00:00Z").toEpochMilli();
        assertEquals(expected, ScheduleTiming.next(once, 0, 0)); assertEquals(0, ScheduleTiming.next(once, expected, expected));
        assertThrows(IllegalArgumentException.class, () -> ScheduleTiming.validate(once, expected));
        Map<String, Object> invalid = spec("interval"); invalid.put("intervalMinutes", "1.5");
        assertThrows(IllegalArgumentException.class, () -> ScheduleTiming.validate(invalid, 0));
        invalid.put("kind", "daily"); invalid.put("timezone", "invalid");
        assertThrows(IllegalArgumentException.class, () -> ScheduleTiming.validate(invalid, 0));
    }
    @Test void calendarSchedulesKeepLocalHourAcrossDaylightSavingChanges() {
        Map<String, Object> daily = spec("daily"); daily.put("timezone", "America/New_York");
        long now = Instant.parse("2026-10-31T14:00:00Z").toEpochMilli(); ScheduleTiming.validate(daily, now);
        assertEquals(Instant.parse("2026-11-01T14:00:00Z").toEpochMilli(), ScheduleTiming.next(daily, now, 0));
    }
}
