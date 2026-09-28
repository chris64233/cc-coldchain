package com.chris64233.cc.coldchain.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 有条件放行的审批事件。同一调查下审批事件号幂等；
 * 两名审批人必须来自不同角色（role），且不能是同一人。
 */
@Entity
@Table(name = "approval_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_approval_event",
                columnNames = {"investigation_id", "approval_event_id"}))
public class ApprovalRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "investigation_id", nullable = false)
    private Investigation investigation;

    @Column(name = "approval_event_id", nullable = false, length = 64)
    private String approvalEventId;

    @Column(name = "approver", nullable = false, length = 128)
    private String approver;

    @Column(name = "role", nullable = false, length = 64)
    private String role;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt = Instant.now();

    protected ApprovalRecord() {
    }

    public ApprovalRecord(Investigation investigation, String approvalEventId, String approver, String role,
                          Instant approvedAt) {
        this.investigation = investigation;
        this.approvalEventId = approvalEventId;
        this.approver = approver;
        this.role = role;
        this.approvedAt = approvedAt;
    }

    public Long getId() {
        return id;
    }

    public String getApprovalEventId() {
        return approvalEventId;
    }

    public String getApprover() {
        return approver;
    }

    public String getRole() {
        return role;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }
}
