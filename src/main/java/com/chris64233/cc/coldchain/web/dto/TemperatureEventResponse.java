package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.TemperatureEvent;

import java.time.Instant;

public record TemperatureEventResponse(
        Long id,
        String deviceEventId,
        Instant sampledAt,
        double temperature) {

    public static TemperatureEventResponse from(TemperatureEvent event) {
        return new TemperatureEventResponse(
                event.getId(), event.getDeviceEventId(), event.getSampledAt(), event.getTemperature());
    }
}
