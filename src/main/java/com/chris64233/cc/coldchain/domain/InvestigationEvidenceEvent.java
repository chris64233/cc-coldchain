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

/**
 * 调查创建时锁定的触发温度事件（证据集合）。
 */
@Entity
@Table(name = "investigation_evidence_events")
public class InvestigationEvidenceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "investigation_id", nullable = false)
    private Investigation investigation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "temperature_event_id", nullable = false)
    private TemperatureEvent temperatureEvent;

    protected InvestigationEvidenceEvent() {
    }

    public InvestigationEvidenceEvent(Investigation investigation, TemperatureEvent temperatureEvent) {
        this.investigation = investigation;
        this.temperatureEvent = temperatureEvent;
    }

    public Long getId() {
        return id;
    }

    public TemperatureEvent getTemperatureEvent() {
        return temperatureEvent;
    }
}
