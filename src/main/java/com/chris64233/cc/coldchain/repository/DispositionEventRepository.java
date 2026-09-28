package com.chris64233.cc.coldchain.repository;

import com.chris64233.cc.coldchain.domain.DispositionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DispositionEventRepository extends JpaRepository<DispositionEvent, Long> {

    Optional<DispositionEvent> findByInvestigationIdAndDispositionEventId(Long investigationId,
                                                                          String dispositionEventId);
}
