package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateShipmentRequest(
        @NotBlank String waybillNo,
        @NotNull @DecimalMin(value = "-273.15") Double tempMin,
        @NotNull @DecimalMin(value = "-273.15") Double tempMax,
        @NotNull @Min(1) Long maxBreachMinutes,
        @NotBlank String initialHolder,
        @NotNull Instant departureTime) {
}
