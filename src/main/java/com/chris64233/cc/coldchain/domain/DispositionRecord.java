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
 * 处置事件：对包装单元执行报废或有条件放行。处置号全局唯一，支持幂等。
 */
@Entity
@Table(name = "disposition_records")
public class DispositionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "disposition_no", nullable = false, unique = true, length = 64)
    private String dispositionNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "investigation_id", nullable = false)
    private Investigation investigation;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private DispositionType type;

    @Column(name = "unit_code", nullable = false, length = 64)
    private String unitCode;

    @Column(name = "evidence_version", nullable = false)
    private long evidenceVersion;

    @Column(name = "decision_seq", nullable = false)
    private long decisionSeq;

    @Column(name = "acted_by", nullable = false, length = 128)
    private String actedBy;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "usage_restrictions", length = 1024)
    private String usageRestrictions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DispositionRecord() {
    }

    public DispositionRecord(String dispositionNo, Investigation investigation, DispositionType type,
                             String unitCode, long evidenceVersion, long decisionSeq, String actedBy,
                             Instant validUntil, String usageRestrictions) {
        this.dispositionNo = dispositionNo;
        this.investigation = investigation;
        this.type = type;
        this.unitCode = unitCode;
        this.evidenceVersion = evidenceVersion;
        this.decisionSeq = decisionSeq;
        this.actedBy = actedBy;
        this.validUntil = validUntil;
        this.usageRestrictions = usageRestrictions;
    }

    public Long getId() {
        return id;
    }

    public String getDispositionNo() {
        return dispositionNo;
    }

    public Investigation getInvestigation() {
        return investigation;
    }

    public DispositionType getType() {
        return type;
    }

    public String getUnitCode() {
        return unitCode;
    }

    public long getEvidenceVersion() {
        return evidenceVersion;
    }

    public long getDecisionSeq() {
        return decisionSeq;
    }

    public String getActedBy() {
        return actedBy;
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
