package com.udata.harness.common.support;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

/** Calendar schedules use their stored zone; interval schedules retain their original cadence. */
public final class ScheduleTiming {
    private ScheduleTiming() { }
    public static void validate(Map<String, Object> task, long now) {
        try {
            ZoneId zone = ZoneId.of(text(task, "timezone"));
            switch (text(task, "kind")) {
                case "once":
                    long at = LocalDateTime.parse(text(task, "at")).atZone(zone).toInstant().toEpochMilli();
                    task.put("onceAt", at);
                    if (at <= now && !Boolean.FALSE.equals(task.get("enabled"))) throw new IllegalArgumentException("单次执行时间须晚于当前时间");
                    break;
                case "weekly":
                    int day = Integer.parseInt(String.valueOf(task.get("weekday")));
                    DayOfWeek.of(day); task.put("weekday", day);
                    LocalTime.parse(text(task, "time")); break;
                case "daily": LocalTime.parse(text(task, "time")); break;
                case "interval":
                    long minutes = Long.parseLong(String.valueOf(task.get("intervalMinutes")));
                    if (minutes < 1 || minutes > 525600) throw new IllegalArgumentException("执行间隔须为 1～525600 分钟");
                    task.put("intervalMinutes", minutes); break;
                default: throw new IllegalArgumentException("不支持的定时类型");
            }
        } catch (DateTimeException | NumberFormatException e) { throw new IllegalArgumentException("请检查日期、时间、时区及执行间隔"); }
    }
    public static long next(Map<String, Object> task, long after, long previous) {
        String kind = text(task, "kind");
        if ("once".equals(kind)) return previous == 0 ? number(task, "onceAt") : 0;
        if ("interval".equals(kind)) {
            long period = number(task, "intervalMinutes") * 60000L;
            return previous == 0 ? after + period : previous + (Math.max(0, after - previous) / period + 1) * period;
        }
        ZonedDateTime local = Instant.ofEpochMilli(after).atZone(ZoneId.of(text(task, "timezone")));
        LocalDate date = local.toLocalDate();
        LocalTime time = LocalTime.parse(text(task, "time"));
        if ("weekly".equals(kind)) date = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.of((int) number(task, "weekday"))));
        ZonedDateTime target = ZonedDateTime.of(date, time, local.getZone());
        if (target.toInstant().toEpochMilli() <= after)
            target = ZonedDateTime.of(date.plusDays("weekly".equals(kind) ? 7 : 1), time, local.getZone());
        return target.toInstant().toEpochMilli();
    }
    public static String text(Map<String, Object> map, String key) { Object value = map.get(key); return value instanceof String ? ((String) value).trim() : ""; }
    public static long number(Map<String, Object> map, String key) { Object value = map.get(key); return value instanceof Number ? ((Number) value).longValue() : 0; }
}
