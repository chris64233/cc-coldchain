package com.chris64233.cc.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ApprovalRequest(
        @NotBlank(message = "审批事件号不能为空") String approvalEventId,
        @NotBlank(message = "审批人不能为空") String approver,
        @NotBlank(message = "审批角色不能为空") String role) {
}
