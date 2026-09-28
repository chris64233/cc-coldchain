package com.chris64233.cc.coldchain.domain;

/**
 * 包装单元（批次）当前状态。
 * IN_TRANSIT 可在货物隔离后被标记为 QUARANTINED；RELEASED 与 DESTROYED 均为终态。
 */
public enum UnitStatus {
    IN_TRANSIT,
    QUARANTINED,
    RELEASED,
    DESTROYED
}
