package com.chris64233.cc.coldchain.web.dto;

import com.chris64233.cc.coldchain.domain.ApprovalRecord;

import java.time.Instant;

public record ApprovalResponse(
        Long id,
        String approvalEventId,
        String approver,
        String role,
        Instant approvedAt) {

    public static ApprovalResponse from(ApprovalRecord record) {
        return new ApprovalResponse(
                record.getId(),
                record.getApprovalEventId(),
                record.getApprover(),
                record.getRole(),
                record.getApprovedAt());
    }
}
