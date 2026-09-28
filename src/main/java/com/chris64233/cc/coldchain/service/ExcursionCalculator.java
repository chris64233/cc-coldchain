package com.chris64233.cc.coldchain.service;

import com.chris64233.cc.coldchain.domain.TemperatureEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 按采样时间计算连续越界区间。一段连续越界采样从首个越界样本开始，
 * 到下一个回温（范围内）样本为止；区间末尾没有回温样本时以最后一个越界样本为终点。
 * 区间时长（大于等于允许的连续越界时长）即达阈异常区间。
 */
public final class ExcursionCalculator {

    private ExcursionCalculator() {
    }

    public record Interval(Instant start, Instant end, List<String> eventIds) {
        public String encode() {
            return start + ".." + end + ":" + String.join(",", eventIds);
        }
    }

    public record Snapshot(List<Interval> intervals, String signature) {

        public boolean isEmpty() {
            return intervals.isEmpty();
        }

        public Instant earliestStart() {
            return intervals.isEmpty() ? null : intervals.get(0).start();
        }

        public Instant latestEnd() {
            return intervals.isEmpty() ? null : intervals.get(intervals.size() - 1).end();
        }

        public List<String> triggerEventIds() {
            return intervals.stream().flatMap(i -> i.eventIds().stream()).toList();
        }
    }

    /**
     * @return 全部达阈异常区间（按开始时间排序）；无达阈区间时 signature 为空串
     */
    public static Snapshot analyze(List<TemperatureEvent> events, double tempMin, double tempMax,
                                   long maxBreachSeconds) {
        Duration maxBreach = Duration.ofSeconds(maxBreachSeconds);
        List<Interval> qualifying = new ArrayList<>();
        Instant breachStart = null;
        Instant lastBreachSample = null;
        List<String> breachEventIds = new ArrayList<>();
        for (TemperatureEvent event : events) {
            boolean outOfRange = event.getTemperature() < tempMin || event.getTemperature() > tempMax;
            if (outOfRange) {
                if (breachStart == null) {
                    breachStart = event.getSampledAt();
                }
                lastBreachSample = event.getSampledAt();
                breachEventIds.add(event.getEventId());
            } else if (breachStart != null) {
                if (Duration.between(breachStart, event.getSampledAt()).compareTo(maxBreach) >= 0) {
                    qualifying.add(new Interval(breachStart, event.getSampledAt(), List.copyOf(breachEventIds)));
                }
                breachStart = null;
                lastBreachSample = null;
                breachEventIds.clear();
            }
        }
        if (breachStart != null
                && Duration.between(breachStart, lastBreachSample).compareTo(maxBreach) >= 0) {
            qualifying.add(new Interval(breachStart, lastBreachSample, List.copyOf(breachEventIds)));
        }
        String signature = qualifying.stream().map(Interval::encode).reduce((a, b) -> a + ";" + b).orElse("");
        return new Snapshot(List.copyOf(qualifying), signature);
    }

    /** 解析 {@link Snapshot#signature()}，用于查询接口展示异常区间。 */
    public static List<Interval> parseSignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return List.of();
        }
        List<Interval> intervals = new ArrayList<>();
        for (String part : signature.split(";")) {
            int colon = part.lastIndexOf(':');
            String[] range = part.substring(0, colon).split("\\.\\.", -1);
            List<String> ids = colon + 1 < part.length()
                    ? List.of(part.substring(colon + 1).split(","))
                    : List.of();
            intervals.add(new Interval(Instant.parse(range[0]), Instant.parse(range[1]), ids));
        }
        return intervals;
    }
}
