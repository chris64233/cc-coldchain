package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record TemperatureEventRequest(
        @NotBlank String deviceEventId,
        @NotNull Instant sampledAt,
        @NotNull @DecimalMin(value = "-273.15") Double temperature) {
}
