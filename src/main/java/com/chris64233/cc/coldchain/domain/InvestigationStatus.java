package com.chris64233.cc.coldchain.domain;

/**
 * 调查生命周期：
 * OPEN 调查已创建，尚无结论
 * CONTINUED 结论为继续隔离（未完成，证据扩大时失效）
 * SCRAP_PENDING 报废结论待处置执行（未完成，证据扩大时失效）
 * RELEASE_PENDING 有条件放行结论，审批/放行未完成（证据扩大时失效）
 * COMPLETED 结论覆盖的单元均已处置（放行/报废的终态）
 * INVALIDATED 证据版本扩大，原有未完成结论失效，必须重新评估
 */
public enum InvestigationStatus {
    OPEN,
    CONTINUED,
    SCRAP_PENDING,
    RELEASE_PENDING,
    COMPLETED,
    INVALIDATED
}
