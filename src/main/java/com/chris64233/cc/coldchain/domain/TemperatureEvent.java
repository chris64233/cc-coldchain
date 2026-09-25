package com.chris64233.cc.coldchain.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "temperature_events",
        uniqueConstraints = @UniqueConstraint(columnNames = {"shipment_id", "device_event_id"}))
public class TemperatureEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(name = "device_event_id", nullable = false, length = 128)
    private String deviceEventId;

    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;

    @Column(name = "temperature", nullable = false)
    private double temperature;

    protected TemperatureEvent() {
    }

    public TemperatureEvent(Shipment shipment, String deviceEventId, Instant sampledAt, double temperature) {
        this.shipment = shipment;
        this.deviceEventId = deviceEventId;
        this.sampledAt = sampledAt;
        this.temperature = temperature;
    }

    public Long getId() {
        return id;
    }

    public String getDeviceEventId() {
        return deviceEventId;
    }

    public Instant getSampledAt() {
        return sampledAt;
    }

    public double getTemperature() {
        return temperature;
    }

    public boolean sameContentAs(Instant sampledAt, double temperature) {
        return this.sampledAt.equals(sampledAt) && Double.compare(this.temperature, temperature) == 0;
    }
}
