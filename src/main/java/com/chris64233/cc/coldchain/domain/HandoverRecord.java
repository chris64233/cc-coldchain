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

    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private HandoverStatus status = HandoverStatus.PENDING;

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

    public String getFromHolder() {
        return fromHolder;
    }

    public String getToHolder() {
        return toHolder;
    }

    public Instant getInitiatedAt() {
        return initiatedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public HandoverStatus getStatus() {
        return status;
    }

    public void confirm() {
        this.status = HandoverStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
    }
}
