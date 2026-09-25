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

@Entity
@Table(name = "handover_records")
public class HandoverRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(name = "from_holder", nullable = false, length = 128)
    private String fromHolder;

    @Column(name = "to_holder", nullable = false, length = 128)
    private String toHolder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private HandoverStatus status = HandoverStatus.PENDING;

    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected HandoverRecord() {
    }

    public HandoverRecord(Shipment shipment, String fromHolder, String toHolder) {
        this.shipment = shipment;
        this.fromHolder = fromHolder;
        this.toHolder = toHolder;
    }

    public Long getId() {
        return id;
    }

    public Shipment getShipment() {
        return shipment;
    }

    public String getFromHolder() {
        return fromHolder;
    }

    public String getToHolder() {
        return toHolder;
    }

    public HandoverStatus getStatus() {
        return status;
    }

    public Instant getInitiatedAt() {
        return initiatedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void confirm(Instant confirmedAt) {
        this.status = HandoverStatus.CONFIRMED;
        this.confirmedAt = confirmedAt;
    }
}
