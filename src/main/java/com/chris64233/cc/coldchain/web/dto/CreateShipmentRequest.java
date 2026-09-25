package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateShipmentRequest(
        @NotBlank(message = "运单号不能为空") String waybillNo,
        @NotNull(message = "温度下限不能为空") Double tempMin,
        @NotNull(message = "温度上限不能为空") Double tempMax,
        @NotNull(message = "允许的连续越界时长不能为空") Long maxBreachSeconds,
        @NotBlank(message = "初始持有人不能为空") String initialHolder,
        @NotNull(message = "启运时间不能为空") Instant departureTime) {
}
