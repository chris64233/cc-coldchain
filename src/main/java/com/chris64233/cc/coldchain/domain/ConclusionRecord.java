package com.chris64233.cc.coldchain.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 调查结论审计记录：每次提交判定都追加一行，不可变，形成完整决策历史。
 */
@Entity
@Table(name = "conclusion_records")
public class ConclusionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "investigation_id", nullable = false)
    private Investigation investigation;

    @Column(name = "decision_seq", nullable = false)
    private long decisionSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 24)
    private InvestigationDecision decision;

    @Column(name = "unit_codes", nullable = false, length = 2048)
    private String unitCodes;

    @Column(name = "evidence_version", nullable = false)
    private long evidenceVersion;

    @Column(name = "decided_by", nullable = false, length = 128)
    private String decidedBy;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "usage_restrictions", length = 1024)
    private String usageRestrictions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ConclusionRecord() {
    }

    public ConclusionRecord(Investigation investigation, long decisionSeq, InvestigationDecision decision,
                            String unitCodes, long evidenceVersion, String decidedBy,
                            Instant validUntil, String usageRestrictions) {
        this.investigation = investigation;
        this.decisionSeq = decisionSeq;
        this.decision = decision;
        this.unitCodes = unitCodes;
        this.evidenceVersion = evidenceVersion;
        this.decidedBy = decidedBy;
        this.validUntil = validUntil;
        this.usageRestrictions = usageRestrictions;
    }

    public Long getId() {
        return id;
    }

    public Investigation getInvestigation() {
        return investigation;
    }

    public long getDecisionSeq() {
        return decisionSeq;
    }

    public InvestigationDecision getDecision() {
        return decision;
    }

    public String getUnitCodes() {
        return unitCodes;
    }

    public long getEvidenceVersion() {
        return evidenceVersion;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public String getUsageRestrictions() {
        return usageRestrictions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
