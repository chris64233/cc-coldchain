package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ReleaseRequest(
        @NotBlank(message = "处置事件号不能为空") String dispositionEventId,
        @NotBlank(message = "操作人不能为空") String operator) {
}
