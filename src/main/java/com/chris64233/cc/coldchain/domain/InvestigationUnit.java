package com.chris64233.cc.coldchain.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 调查结论覆盖的包装单元（创建调查时的隔离单元快照，支持全部或指定单元）。
 */
@Entity
@Table(name = "investigation_units")
public class InvestigationUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "investigation_id", nullable = false)
    private Investigation investigation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "packing_unit_id", nullable = false)
    private PackingUnit packingUnit;

    protected InvestigationUnit() {
    }

    public InvestigationUnit(Investigation investigation, PackingUnit packingUnit) {
        this.investigation = investigation;
        this.packingUnit = packingUnit;
    }

    public Long getId() {
        return id;
    }

    public PackingUnit getPackingUnit() {
        return packingUnit;
    }
}
