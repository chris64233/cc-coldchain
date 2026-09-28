package com.chris64233.cc.coldchain.domain;

/**
 * 调查处置结论：
 * DESTROY - 报废；
 * CONTINUE_QUARANTINE - 继续隔离；
 * CONDITIONAL_RELEASE - 有条件放行（需两名不同角色审批并显式放行）。
 */
public enum DispositionType {
    DESTROY,
    CONTINUE_QUARANTINE,
    CONDITIONAL_RELEASE
}
