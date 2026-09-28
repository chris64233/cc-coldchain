package com.chris64233.cc.coldchain.repository;

import com.chris64233.cc.coldchain.domain.DispositionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DispositionRecordRepository extends JpaRepository<DispositionRecord, Long> {

    Optional<DispositionRecord> findByDispositionNo(String dispositionNo);

    List<DispositionRecord> findByInvestigationIdOrderByCreatedAtAscIdAsc(Long investigationId);
}
