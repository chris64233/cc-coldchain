package com.chris64233.cc.coldchain.service;

import com.chris64233.cc.coldchain.domain.BreachInterval;
import com.chris64233.cc.coldchain.domain.TemperatureEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 按采样时间对运单全部事件重新计算连续越界区间。
 * 一段连续越界采样从首个越界样本开始，到下一个回温样本为止；
 * 若区间末尾没有回温样本，则以最后一个越界样本为终点。
 */
final class BreachCalculator {

    record BreachSegment(BreachInterval interval, List<TemperatureEvent> breachSamples, boolean quarantineTrigger) {
    }

    private BreachCalculator() {
    }

    static List<BreachSegment> compute(List<TemperatureEvent> events, double tempMin, double tempMax,
                                       long maxBreachSeconds) {
        List<BreachSegment> segments = new ArrayList<>();
        Instant breachStart = null;
        Instant lastBreachSample = null;
        List<TemperatureEvent> breachSamples = new ArrayList<>();
        Duration maxBreach = Duration.ofSeconds(maxBreachSeconds);
        for (TemperatureEvent event : events) {
            boolean outOfRange = event.getTemperature() < tempMin || event.getTemperature() > tempMax;
            if (outOfRange) {
                if (breachStart == null) {
                    breachStart = event.getSampledAt();
                    breachSamples = new ArrayList<>();
                }
                lastBreachSample = event.getSampledAt();
                breachSamples.add(event);
            } else if (breachStart != null) {
                boolean triggers = Duration.between(breachStart, event.getSampledAt()).compareTo(maxBreach) >= 0;
                segments.add(new BreachSegment(BreachInterval.of(breachStart, lastBreachSample),
                        List.copyOf(breachSamples), triggers));
                breachStart = null;
                lastBreachSample = null;
                breachSamples.clear();
            }
        }
        if (breachStart != null) {
            boolean triggers = Duration.between(breachStart, lastBreachSample).compareTo(maxBreach) >= 0;
            segments.add(new BreachSegment(BreachInterval.of(breachStart, lastBreachSample),
                    List.copyOf(breachSamples), triggers));
        }
        return segments;
    }

    /**
     * 当前事件集合下是否已达到隔离条件。
     */
    static boolean isQuarantined(List<BreachSegment> segments) {
        return segments.stream().anyMatch(BreachSegment::quarantineTrigger);
    }

    /**
     * 已确认（达到允许连续越界时长）的越界区间，即异常区间。
     */
    static List<BreachInterval> confirmedIntervals(List<BreachSegment> segments) {
        return segments.stream()
                .filter(BreachSegment::quarantineTrigger)
                .map(BreachSegment::interval)
                .toList();
    }
}
