package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmHandoverRequest(
        @NotBlank(message = "确认方不能为空") String confirmedBy) {
}
