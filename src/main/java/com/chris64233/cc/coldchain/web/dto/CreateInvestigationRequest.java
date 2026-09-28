package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateInvestigationRequest(
        @NotBlank(message = "调查号不能为空") String investigationNo) {
}
