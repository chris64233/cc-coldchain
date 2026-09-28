package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.InvestigationDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 提交调查判定。unitCodes 为空或 ["*"] 表示覆盖全部包装单元。
 * CONDITIONAL_RELEASE 必须提供 validUntil 与 usageRestrictions。
 */
public record SubmitConclusionRequest(
        @NotNull(message = "判定不能为空") InvestigationDecision decision,
        List<String> unitCodes,
        Instant validUntil,
        String usageRestrictions,
        @NotBlank(message = "判定人不能为空") String decidedBy) {
}
