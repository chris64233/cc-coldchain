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
 * 温度异常调查。创建时锁定：
 * - 触发隔离的温度事件集合（triggerEventIds）与异常区间快照；
 * - 当时的阈值配置（tempMin/tempMax/maxBreachSeconds）；
 * - 当时的保管方（custodian）。
 * evidenceVersion 为证据版本，每次后到历史事件扩大异常区间时递增，
 * 并使尚未完成的结论失效。
 */
@Entity
@Table(name = "investigations")
public class Investigation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investigation_no", nullable = false, unique = true, length = 64)
    private String investigationNo;

    /** 冗余运单号，便于审计与无会话场景下的序列化。 */
    @Column(name = "waybill_no", nullable = false, length = 64)
    private String waybillNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    // ---- 创建时锁定的阈值与保管方（之后运单阈值变化也不影响本调查） ----
    @Column(name = "threshold_temp_min", nullable = false)
    private double thresholdTempMin;

    @Column(name = "threshold_temp_max", nullable = false)
    private double thresholdTempMax;

    @Column(name = "threshold_max_breach_seconds", nullable = false)
    private long thresholdMaxBreachSeconds;

    @Column(name = "custodian", nullable = false, length = 128)
    private String custodian;

    // ---- 证据快照 ----
    @Column(name = "evidence_version", nullable = false)
    private long evidenceVersion = 1;

    /** 全部达阈异常区间的规范化签名，用于扩大检测与审计。 */
    @Column(name = "evidence_signature", nullable = false, length = 2048)
    private String evidenceSignature;

    /** 创建调查时锁定的异常区间签名，不可变，用于审计展示“证据是否较立案时扩大”。 */
    @Column(name = "locked_evidence_signature", nullable = false, length = 2048)
    private String lockedEvidenceSignature;

    /** 触发隔离的温度事件号（逗号分隔、有序）。 */
    @Column(name = "trigger_event_ids", nullable = false, length = 2048)
    private String triggerEventIds;

    @Column(name = "excursion_start", nullable = false)
    private Instant excursionStart;

    /** 区间终点：有回温样本时为回温时刻，否则为最后越界样本时刻。 */
    @Column(name = "excursion_end")
    private Instant excursionEnd;

    // ---- 当前有效结论（INVALIDATED/OPEN/COMPLETED 后可能为空） ----
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private InvestigationStatus status = InvestigationStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 24)
    private InvestigationDecision decision;

    /** 结论序号，每次提交结论递增；审批绑定该序号，重新评估后旧审批失效。 */
    @Column(name = "decision_seq", nullable = false)
    private long decisionSeq = 0;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "usage_restrictions", length = 1024)
    private String usageRestrictions;

    /** 结论覆盖的包装单元；"*" 表示创建结论时该运单下的全部单元。 */
    @Column(name = "decision_unit_codes", length = 2048)
    private String decisionUnitCodes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "invalidation_reason", length = 1024)
    private String invalidationReason;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Investigation() {
    }

    public Investigation(String investigationNo, Shipment shipment, String custodian,
                         String evidenceSignature, String triggerEventIds,
                         Instant excursionStart, Instant excursionEnd) {
        this.investigationNo = investigationNo;
        this.waybillNo = shipment.getWaybillNo();
        this.shipment = shipment;
        this.thresholdTempMin = shipment.getTempMin();
        this.thresholdTempMax = shipment.getTempMax();
        this.thresholdMaxBreachSeconds = shipment.getMaxBreachSeconds();
        this.custodian = custodian;
        this.evidenceSignature = evidenceSignature;
        this.lockedEvidenceSignature = evidenceSignature;
        this.triggerEventIds = triggerEventIds;
        this.excursionStart = excursionStart;
        this.excursionEnd = excursionEnd;
    }

    /**
     * 证据扩大：仅递增版本并刷新当前证据签名。
     * 创建时锁定的触发事件集与异常区间（triggerEventIds/excursionStart/excursionEnd）
     * 是不可变快照，不随后到事件改变；最新证据通过详情接口单独展示。
     */
    public void refreshEvidence(String signature) {
        this.evidenceVersion++;
        this.evidenceSignature = signature;
    }

    public long applyDecision(InvestigationDecision decision, String unitCodes, Instant validUntil,
                              String usageRestrictions, Instant now) {
        this.decisionSeq++;
        this.decision = decision;
        this.decisionUnitCodes = unitCodes;
        this.validUntil = validUntil;
        this.usageRestrictions = usageRestrictions;
        this.decidedAt = now;
        this.invalidatedAt = null;
        this.invalidationReason = null;
        this.completedAt = null;
        this.status = switch (decision) {
            case SCRAP -> InvestigationStatus.SCRAP_PENDING;
            case CONTINUE_QUARANTINE -> InvestigationStatus.CONTINUED;
            case CONDITIONAL_RELEASE -> InvestigationStatus.RELEASE_PENDING;
        };
        return this.decisionSeq;
    }

    public void invalidate(String reason, Instant now) {
        this.status = InvestigationStatus.INVALIDATED;
        this.invalidatedAt = now;
        this.invalidationReason = reason;
    }

    public void complete(Instant now) {
        this.status = InvestigationStatus.COMPLETED;
        this.completedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getInvestigationNo() {
        return investigationNo;
    }

    public String getWaybillNo() {
        return waybillNo;
    }

    public Shipment getShipment() {
        return shipment;
    }

    public double getThresholdTempMin() {
        return thresholdTempMin;
    }

    public double getThresholdTempMax() {
        return thresholdTempMax;
    }

    public long getThresholdMaxBreachSeconds() {
        return thresholdMaxBreachSeconds;
    }

    public String getCustodian() {
        return custodian;
    }

    public long getEvidenceVersion() {
        return evidenceVersion;
    }

    public String getEvidenceSignature() {
        return evidenceSignature;
    }

    public String getLockedEvidenceSignature() {
        return lockedEvidenceSignature;
    }

    public String getTriggerEventIds() {
        return triggerEventIds;
    }

    public Instant getExcursionStart() {
        return excursionStart;
    }

    public Instant getExcursionEnd() {
        return excursionEnd;
    }

    public InvestigationStatus getStatus() {
        return status;
    }

    public InvestigationDecision getDecision() {
        return decision;
    }

    public long getDecisionSeq() {
        return decisionSeq;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public String getUsageRestrictions() {
        return usageRestrictions;
    }

    public String getDecisionUnitCodes() {
        return decisionUnitCodes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getInvalidatedAt() {
        return invalidatedAt;
    }

    public String getInvalidationReason() {
        return invalidationReason;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
