package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateInvestigationRequest(
        @NotBlank(message = "调查号不能为空") String investigationNo,
        /** 为空表示覆盖当前全部隔离单元。 */
        List<String> unitCodes,
        String reason) {
}
