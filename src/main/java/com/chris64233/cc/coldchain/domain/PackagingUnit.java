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
 * 运单拆分后的包装单元。未显式拆分的运单在首次创建调查时自动生成一个默认单元，
 * 使所有处置都在单元级别完成，保证数量守恒。
 */
@Entity
@Table(name = "packaging_units",
        uniqueConstraints = @UniqueConstraint(name = "uk_packaging_unit", columnNames = {"shipment_id", "unit_code"}))
public class PackagingUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(name = "unit_code", nullable = false, length = 64)
    private String unitCode;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UnitStatus status = UnitStatus.HELD;

    /** 放行时适用的使用限制，仅 RELEASED 有值。 */
    @Column(name = "usage_restrictions", length = 1024)
    private String usageRestrictions;

    @Column(name = "released_at")
    private Instant releasedAt;

    /** 放行有效期截止时刻；过期后的放行不得继续使用。 */
    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PackagingUnit() {
    }

    public PackagingUnit(Shipment shipment, String unitCode, int quantity) {
        this.shipment = shipment;
        this.unitCode = unitCode;
        this.quantity = quantity;
    }

    public void release(Instant releasedAt, Instant validUntil, String usageRestrictions) {
        if (this.status != UnitStatus.HELD) {
            throw new IllegalStateException("单元 " + unitCode + " 已处置，不能重复放行");
        }
        this.status = UnitStatus.RELEASED;
        this.releasedAt = releasedAt;
        this.validUntil = validUntil;
        this.usageRestrictions = usageRestrictions;
    }

    public void scrap() {
        if (this.status != UnitStatus.HELD) {
            throw new IllegalStateException("单元 " + unitCode + " 已处置，不能重复报废");
        }
        this.status = UnitStatus.SCRAPPED;
    }

    public Long getId() {
        return id;
    }

    public Shipment getShipment() {
        return shipment;
    }

    public String getUnitCode() {
        return unitCode;
    }

    public int getQuantity() {
        return quantity;
    }

    public UnitStatus getStatus() {
        return status;
    }

    public String getUsageRestrictions() {
        return usageRestrictions;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
