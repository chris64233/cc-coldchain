package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;

public record InitiateHandoverRequest(
        @NotBlank String fromHolder,
        @NotBlank String toHolder) {
}
