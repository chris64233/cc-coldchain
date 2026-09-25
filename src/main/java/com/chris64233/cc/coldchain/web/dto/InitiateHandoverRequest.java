package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;

public record InitiateHandoverRequest(
        @NotBlank(message = "发起方不能为空") String fromHolder,
        @NotBlank(message = "接收方不能为空") String toHolder) {
}
