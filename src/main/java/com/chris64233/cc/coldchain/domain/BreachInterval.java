package com.chris64233.cc.coldchain.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 已确认的越界区间：从首个越界样本开始，到回温样本为止（无回温样本时以最后一个越界样本为止）。
 * 以 epoch 毫秒序列化，多个区间用 {@code ;} 连接，存入运单与调查快照。
 */
public record BreachInterval(long startEpochMillis, long endEpochMillis) {

    public static BreachInterval of(Instant start, Instant end) {
        return new BreachInterval(start.toEpochMilli(), end.toEpochMilli());
    }

    public Instant getStart() {
        return Instant.ofEpochMilli(startEpochMillis);
    }

    public Instant getEnd() {
        return Instant.ofEpochMilli(endEpochMillis);
    }

    /**
     * 新区间集合是否相对旧集合发生了扩大：数量变化，或任一新区间未被某个旧区间完整覆盖
     * （例如区间起点提前、终点延后，或出现新的独立区间）。
     */
    public static boolean isExpanded(List<BreachInterval> previous, List<BreachInterval> current) {
        if (current.size() != previous.size()) {
            return true;
        }
        for (BreachInterval newInterval : current) {
            boolean coveredByOld = previous.stream()
                    .anyMatch(old -> old.startEpochMillis <= newInterval.startEpochMillis
                            && old.endEpochMillis >= newInterval.endEpochMillis);
            if (!coveredByOld) {
                return true;
            }
        }
        return false;
    }

    public static String format(List<BreachInterval> intervals) {
        if (intervals.isEmpty()) {
            return null;
        }
        List<BreachInterval> sorted = intervals.stream()
                .sorted(Comparator.comparingLong(BreachInterval::startEpochMillis))
                .toList();
        StringBuilder builder = new StringBuilder();
        for (BreachInterval interval : sorted) {
            if (!builder.isEmpty()) {
                builder.append(';');
            }
            builder.append(interval.startEpochMillis).append(',').append(interval.endEpochMillis);
        }
        return builder.toString();
    }

    public static List<BreachInterval> parse(String snapshot) {
        List<BreachInterval> intervals = new ArrayList<>();
        if (snapshot == null || snapshot.isBlank()) {
            return intervals;
        }
        for (String part : snapshot.split(";")) {
            String[] pair = part.split(",");
            intervals.add(new BreachInterval(Long.parseLong(pair[0]), Long.parseLong(pair[1])));
        }
        return intervals;
    }
}
