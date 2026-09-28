package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;

public record DispositionRequest(
        @NotBlank(message = "处置事件号不能为空") String dispositionNo,
        @NotBlank(message = "单元编号不能为空") String unitCode,
        @NotBlank(message = "处置人不能为空") String actedBy) {
}
