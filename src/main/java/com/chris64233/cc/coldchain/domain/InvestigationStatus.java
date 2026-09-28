package com.chris64233.cc.coldchain.domain;

/**
 * 调查状态：
 * OPEN - 已创建，尚未形成结论；
 * AWAITING_APPROVAL - 已判定有条件放行，等待两名不同角色审批；
 * COMPLETED - 结论已执行（继续隔离/报废/完成受控放行）；
 * SUPERSEDED - 晚到的历史温度事件扩大了异常区间，结论失效，需重新评估。
 */
public enum InvestigationStatus {
    OPEN,
    AWAITING_APPROVAL,
    COMPLETED,
    SUPERSEDED
}
