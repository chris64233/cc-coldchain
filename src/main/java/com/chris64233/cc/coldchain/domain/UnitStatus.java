package com.chris64233.cc.coldchain.domain;

/**
 * 包装单元的处置状态。状态机只能从 HELD 单向迁移到 RELEASED / SCRAPPED，
 * 同一单元不可能同时处于已放行和已报废状态。
 */
public enum UnitStatus {
    HELD,
    RELEASED,
    SCRAPPED
}
