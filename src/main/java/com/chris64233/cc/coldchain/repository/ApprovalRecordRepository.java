package com.chris64233.cc.coldchain.repository;

import com.chris64233.cc.coldchain.domain.ApprovalRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, Long> {

    Optional<ApprovalRecord> findByApprovalNo(String approvalNo);

    List<ApprovalRecord> findByInvestigationIdOrderByCreatedAtAscIdAsc(Long investigationId);

    List<ApprovalRecord> findByInvestigationIdAndDecisionSeqOrderByCreatedAtAscIdAsc(Long investigationId, long decisionSeq);

    boolean existsByInvestigationIdAndDecisionSeqAndRole(Long investigationId, long decisionSeq, String role);
}
