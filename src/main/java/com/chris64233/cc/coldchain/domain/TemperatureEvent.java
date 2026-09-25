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
        uniqueConstraints = @UniqueConstraint(name = "uk_temperature_event", columnNames = {"shipment_id", "event_id"}))
public class TemperatureEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;

    @Column(name = "temperature", nullable = false)
    private double temperature;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    protected TemperatureEvent() {
    }

    public TemperatureEvent(Shipment shipment, String eventId, Instant sampledAt, double temperature) {
        this.shipment = shipment;
        this.eventId = eventId;
        this.sampledAt = sampledAt;
        this.temperature = temperature;
    }

    public Long getId() {
        return id;
    }

    public Shipment getShipment() {
        return shipment;
    }

    public String getEventId() {
        return eventId;
    }

    public Instant getSampledAt() {
        return sampledAt;
    }

    public double getTemperature() {
        return temperature;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
