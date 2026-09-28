package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record RegisterUnitRequest(
        @NotBlank(message = "单元编号不能为空") String unitCode,
        @Positive(message = "单元数量必须大于 0") Integer quantity) {
}
