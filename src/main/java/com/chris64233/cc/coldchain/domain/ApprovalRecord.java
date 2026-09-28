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

import java.time.Instant;

/**
 * 有条件放行的审批事件。一次放行结论需要两名不同角色、不同人员的审批。
 * 审批绑定提交结论时的证据版本（evidenceVersion）与结论序号（decisionSeq）：
 * 结论失效或重新评估后，旧审批不再计入新的审批链。
 */
@Entity
@Table(name = "approval_records")
public class ApprovalRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "approval_no", nullable = false, unique = true, length = 64)
    private String approvalNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "investigation_id", nullable = false)
    private Investigation investigation;

    @Column(name = "approver", nullable = false, length = 128)
    private String approver;

    @Column(name = "role", nullable = false, length = 64)
    private String role;

    @Column(name = "decision_seq", nullable = false)
    private long decisionSeq;

    @Column(name = "evidence_version", nullable = false)
    private long evidenceVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ApprovalRecord() {
    }

    public ApprovalRecord(String approvalNo, Investigation investigation,
                          String approver, String role, long decisionSeq, long evidenceVersion) {
        this.approvalNo = approvalNo;
        this.investigation = investigation;
        this.approver = approver;
        this.role = role;
        this.decisionSeq = decisionSeq;
        this.evidenceVersion = evidenceVersion;
    }

    public Long getId() {
        return id;
    }

    public String getApprovalNo() {
        return approvalNo;
    }

    public Investigation getInvestigation() {
        return investigation;
    }

    public String getApprover() {
        return approver;
    }

    public String getRole() {
        return role;
    }

    public long getDecisionSeq() {
        return decisionSeq;
    }

    public long getEvidenceVersion() {
        return evidenceVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
