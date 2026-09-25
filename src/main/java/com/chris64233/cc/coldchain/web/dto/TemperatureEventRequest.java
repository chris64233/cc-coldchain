package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record TemperatureEventRequest(
        @NotBlank(message = "设备事件号不能为空") String eventId,
        @NotNull(message = "采样时间不能为空") Instant sampledAt,
        @NotNull(message = "温度不能为空") Double temperature) {
}
