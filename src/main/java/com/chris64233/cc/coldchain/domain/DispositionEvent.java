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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 调查处置事件（审计）。
 * CONCLUSION - 判定结论（报废/继续隔离/有条件放行）时产生；
 * EXECUTION - 有条件放行经双角色审批后实际执行时产生。
 * 同一调查下处置事件号幂等。
 */
@Entity
@Table(name = "disposition_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_disposition_event",
                columnNames = {"investigation_id", "disposition_event_id"}))
public class DispositionEvent {

    public enum Stage {
        CONCLUSION,
        EXECUTION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "investigation_id", nullable = false)
    private Investigation investigation;

    @Column(name = "disposition_event_id", nullable = false, length = 64)
    private String dispositionEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 16)
    private Stage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition_type", nullable = false, length = 24)
    private DispositionType dispositionType;

    /** 本次处置覆盖的单元编码，逗号分隔。 */
    @Column(name = "unit_codes", length = 2000)
    private String unitCodes;

    @Column(name = "usage_restrictions", length = 1024)
    private String usageRestrictions;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "operator", nullable = false, length = 128)
    private String operator;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime = Instant.now();

    protected DispositionEvent() {
    }

    public DispositionEvent(Investigation investigation, String dispositionEventId, Stage stage,
                            DispositionType dispositionType, String unitCodes, String usageRestrictions,
                            Instant validUntil, String operator, Instant eventTime) {
        this.investigation = investigation;
        this.dispositionEventId = dispositionEventId;
        this.stage = stage;
        this.dispositionType = dispositionType;
        this.unitCodes = unitCodes;
        this.usageRestrictions = usageRestrictions;
        this.validUntil = validUntil;
        this.operator = operator;
        this.eventTime = eventTime;
    }

    public Long getId() {
        return id;
    }

    public String getDispositionEventId() {
        return dispositionEventId;
    }

    public Stage getStage() {
        return stage;
    }

    public DispositionType getDispositionType() {
        return dispositionType;
    }

    public String getUnitCodes() {
        return unitCodes;
    }

    public String getUsageRestrictions() {
        return usageRestrictions;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public String getOperator() {
        return operator;
    }

    public Instant getEventTime() {
        return eventTime;
    }
}
