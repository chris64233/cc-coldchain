package com.chris64233.cc.coldchain.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 温度异常调查。创建时即锁定触发隔离的温度事件集合、当时阈值配置与保管方，
 * 并记录异常证据版本；晚到历史事件扩大异常区间后，未完成的调查被置为 SUPERSEDED，
 * 已有结论与审批失效，必须重新评估。
 */
@Entity
@Table(name = "investigations",
        uniqueConstraints = @UniqueConstraint(name = "uk_investigation_no", columnNames = "investigation_no"))
public class Investigation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investigation_no", nullable = false, length = 64)
    private String investigationNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvestigationStatus status = InvestigationStatus.OPEN;

    // 锁定的阈值配置
    @Column(name = "locked_temp_min", nullable = false)
    private double lockedTempMin;

    @Column(name = "locked_temp_max", nullable = false)
    private double lockedTempMax;

    @Column(name = "locked_max_breach_seconds", nullable = false)
    private long lockedMaxBreachSeconds;

    // 锁定的保管方
    @Column(name = "locked_holder", nullable = false, length = 128)
    private String lockedHolder;

    // 锁定的异常证据版本与异常区间快照
    @Column(name = "evidence_version", nullable = false)
    private int evidenceVersion;

    @Column(name = "breach_snapshot", nullable = false, length = 4000)
    private String breachSnapshot;

    @Column(name = "reason", length = 1024)
    private String reason;

    // 结论
    @Enumerated(EnumType.STRING)
    @Column(name = "disposition_type", length = 24)
    private DispositionType dispositionType;

    @Column(name = "usage_restrictions", length = 1024)
    private String usageRestrictions;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "concluded_at")
    private Instant concludedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "supersede_reason", length = 1024)
    private String supersedeReason;

    @OneToMany(mappedBy = "investigation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<InvestigationEvidenceEvent> evidenceEvents = new ArrayList<>();

    @OneToMany(mappedBy = "investigation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<InvestigationUnit> units = new ArrayList<>();

    @OneToMany(mappedBy = "investigation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ApprovalRecord> approvals = new ArrayList<>();

    @OneToMany(mappedBy = "investigation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<DispositionEvent> dispositionEvents = new ArrayList<>();

    protected Investigation() {
    }

    public Investigation(String investigationNo, Shipment shipment, int evidenceVersion, String breachSnapshot,
                         String reason) {
        this.investigationNo = investigationNo;
        this.shipment = shipment;
        this.lockedTempMin = shipment.getTempMin();
        this.lockedTempMax = shipment.getTempMax();
        this.lockedMaxBreachSeconds = shipment.getMaxBreachSeconds();
        this.lockedHolder = shipment.getCurrentHolder();
        this.evidenceVersion = evidenceVersion;
        this.breachSnapshot = breachSnapshot;
        this.reason = reason;
    }

    public void addEvidenceEvent(TemperatureEvent event) {
        evidenceEvents.add(new InvestigationEvidenceEvent(this, event));
    }

    public void addUnit(PackingUnit unit) {
        units.add(new InvestigationUnit(this, unit));
    }

    public void conclude(DispositionType type, String usageRestrictions, Instant validUntil, Instant concludedAt) {
        this.dispositionType = type;
        this.usageRestrictions = usageRestrictions;
        this.validUntil = validUntil;
        this.concludedAt = concludedAt;
        this.status = type == DispositionType.CONDITIONAL_RELEASE
                ? InvestigationStatus.AWAITING_APPROVAL
                : InvestigationStatus.COMPLETED;
        if (this.status == InvestigationStatus.COMPLETED) {
            this.completedAt = concludedAt;
        }
    }

    public void complete(Instant completedAt) {
        this.status = InvestigationStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    public void supersede(String reason, Instant supersededAt) {
        this.status = InvestigationStatus.SUPERSEDED;
        this.supersedeReason = reason;
        this.supersededAt = supersededAt;
    }

    public boolean isFinished() {
        return status == InvestigationStatus.COMPLETED || status == InvestigationStatus.SUPERSEDED;
    }

    public Long getId() {
        return id;
    }

    public String getInvestigationNo() {
        return investigationNo;
    }

    public Shipment getShipment() {
        return shipment;
    }

    public InvestigationStatus getStatus() {
        return status;
    }

    public double getLockedTempMin() {
        return lockedTempMin;
    }

    public double getLockedTempMax() {
        return lockedTempMax;
    }

    public long getLockedMaxBreachSeconds() {
        return lockedMaxBreachSeconds;
    }

    public String getLockedHolder() {
        return lockedHolder;
    }

    public int getEvidenceVersion() {
        return evidenceVersion;
    }

    public String getBreachSnapshot() {
        return breachSnapshot;
    }

    public String getReason() {
        return reason;
    }

    public DispositionType getDispositionType() {
        return dispositionType;
    }

    public String getUsageRestrictions() {
        return usageRestrictions;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConcludedAt() {
        return concludedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public String getSupersedeReason() {
        return supersedeReason;
    }

    public List<InvestigationEvidenceEvent> getEvidenceEvents() {
        return evidenceEvents;
    }

    public List<InvestigationUnit> getUnits() {
        return units;
    }

    public List<ApprovalRecord> getApprovals() {
        return approvals;
    }

    public List<DispositionEvent> getDispositionEvents() {
        return dispositionEvents;
    }
}
