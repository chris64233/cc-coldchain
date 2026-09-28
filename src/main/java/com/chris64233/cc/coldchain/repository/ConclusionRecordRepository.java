package com.chris64233.cc.coldchain.repository;

import com.chris64233.cc.coldchain.domain.ConclusionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConclusionRecordRepository extends JpaRepository<ConclusionRecord, Long> {

    List<ConclusionRecord> findByInvestigationIdOrderByCreatedAtAscIdAsc(Long investigationId);
}
