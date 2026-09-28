package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.BreachInterval;

import java.time.Instant;

public record BreachIntervalResponse(
        Instant start,
        Instant end) {

    public static BreachIntervalResponse from(BreachInterval interval) {
        return new BreachIntervalResponse(interval.getStart(), interval.getEnd());
    }
}
