package com.chris64233.cc.coldchain.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "waybill_no", nullable = false, unique = true, length = 64)
    private String waybillNo;

    @Column(name = "temp_min", nullable = false)
    private double tempMin;

    @Column(name = "temp_max", nullable = false)
    private double tempMax;

    @Column(name = "max_breach_minutes", nullable = false)
    private long maxBreachMinutes;

    @Column(name = "current_holder", nullable = false, length = 128)
    private String currentHolder;

    @Column(name = "departure_time", nullable = false)
    private Instant departureTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ShipmentStatus status = ShipmentStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Shipment() {
    }

    public Shipment(String waybillNo, double tempMin, double tempMax, long maxBreachMinutes,
                    String currentHolder, Instant departureTime) {
        this.waybillNo = waybillNo;
        this.tempMin = tempMin;
        this.tempMax = tempMax;
        this.maxBreachMinutes = maxBreachMinutes;
        this.currentHolder = currentHolder;
        this.departureTime = departureTime;
    }

    public Long getId() {
        return id;
    }

    public String getWaybillNo() {
        return waybillNo;
    }

    public double getTempMin() {
        return tempMin;
    }

    public double getTempMax() {
        return tempMax;
    }

    public long getMaxBreachMinutes() {
        return maxBreachMinutes;
    }

    public String getCurrentHolder() {
        return currentHolder;
    }

    public Instant getDepartureTime() {
        return departureTime;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void quarantine() {
        this.status = ShipmentStatus.QUARANTINED;
    }

    public void transferTo(String newHolder) {
        this.currentHolder = newHolder;
    }
}
