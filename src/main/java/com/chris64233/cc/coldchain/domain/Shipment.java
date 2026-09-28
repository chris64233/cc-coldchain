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

    @Column(name = "max_breach_seconds", nullable = false)
    private long maxBreachSeconds;

    @Column(name = "current_holder", nullable = false, length = 128)
    private String currentHolder;

    @Column(name = "departure_time", nullable = false)
    private Instant departureTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ShipmentStatus status = ShipmentStatus.IN_TRANSIT;

    /**
     * 异常证据版本：触发隔离或已确认的越界区间集合发生扩大时递增。
     * 调查持有创建时的版本号，晚到事件扩大异常区间后旧结论即失效。
     */
    @Column(name = "evidence_version", nullable = false)
    private int evidenceVersion = 0;

    /**
     * 当前已确认（达到允许连续越界时长）的异常区间快照，
     * 形如 {@code [startEpochMillis,endEpochMillis];...}，按开始时间排序。
     */
    @Column(name = "breach_snapshot", length = 4000)
    private String breachSnapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Shipment() {
    }

    public Shipment(String waybillNo, double tempMin, double tempMax, long maxBreachSeconds,
                    String currentHolder, Instant departureTime) {
        this.waybillNo = waybillNo;
        this.tempMin = tempMin;
        this.tempMax = tempMax;
        this.maxBreachSeconds = maxBreachSeconds;
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

    public long getMaxBreachSeconds() {
        return maxBreachSeconds;
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

    public int getEvidenceVersion() {
        return evidenceVersion;
    }

    public String getBreachSnapshot() {
        return breachSnapshot;
    }

    public void setEvidenceVersion(int evidenceVersion) {
        this.evidenceVersion = evidenceVersion;
    }

    public void setBreachSnapshot(String breachSnapshot) {
        this.breachSnapshot = breachSnapshot;
    }

    public void setCurrentHolder(String currentHolder) {
        this.currentHolder = currentHolder;
    }

    public void setStatus(ShipmentStatus status) {
        this.status = status;
    }
}
