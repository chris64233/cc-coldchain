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
 * 运单拆分出的包装单元（影响批次追踪的最小单位）。
 */
@Entity
@Table(name = "packing_units",
        uniqueConstraints = @UniqueConstraint(name = "uk_packing_unit", columnNames = {"shipment_id", "unit_code"}))
public class PackingUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(name = "unit_code", nullable = false, length = 64)
    private String unitCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UnitStatus status = UnitStatus.IN_TRANSIT;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt = Instant.now();

    protected PackingUnit() {
    }

    public PackingUnit(Shipment shipment, String unitCode, UnitStatus status) {
        this.shipment = shipment;
        this.unitCode = unitCode;
        this.status = status;
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

    public UnitStatus getStatus() {
        return status;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setStatus(UnitStatus status) {
        this.status = status;
    }
}
