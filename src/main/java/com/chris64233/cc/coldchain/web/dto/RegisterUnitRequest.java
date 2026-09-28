package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterUnitRequest(
        @NotBlank(message = "包装单元编码不能为空") String unitCode) {
}
