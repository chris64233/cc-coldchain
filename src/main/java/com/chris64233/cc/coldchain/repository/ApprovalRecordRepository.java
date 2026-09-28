package com.chris64233.cc.coldchain.repository;

import com.chris64233.cc.coldchain.domain.ApprovalRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, Long> {

    Optional<ApprovalRecord> findByInvestigationIdAndApprovalEventId(Long investigationId, String approvalEventId);
}
