package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.TemperatureEvent;

import java.time.Instant;

public record TemperatureEventResponse(
        Long id,
        String eventId,
        Instant sampledAt,
        double temperature,
        Instant receivedAt) {

    public static TemperatureEventResponse from(TemperatureEvent event) {
        return new TemperatureEventResponse(
                event.getId(),
                event.getEventId(),
                event.getSampledAt(),
                event.getTemperature(),
                event.getReceivedAt());
    }
}
