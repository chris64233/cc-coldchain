package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.DispositionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record ConcludeRequest(
        @NotBlank(message = "处置事件号不能为空") String dispositionEventId,
        @NotNull(message = "处置结论不能为空") DispositionType dispositionType,
        /** 仅 CONDITIONAL_RELEASE 必填：放行有效期截止时间。 */
        Instant validUntil,
        /** 仅 CONDITIONAL_RELEASE 必填：使用限制。 */
        String usageRestrictions,
        @NotBlank(message = "操作人不能为空") String operator) {
}
