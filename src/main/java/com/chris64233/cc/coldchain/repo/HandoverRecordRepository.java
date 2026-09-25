package com.chris64233.cc.coldchain.repo;

import com.chris64233.cc.coldchain.domain.HandoverRecord;
import com.chris64233.cc.coldchain.domain.HandoverStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HandoverRecordRepository extends JpaRepository<HandoverRecord, Long> {

    boolean existsByShipmentIdAndStatus(Long shipmentId, HandoverStatus status);

    Optional<HandoverRecord> findByIdAndShipmentId(Long id, Long shipmentId);

    List<HandoverRecord> findByShipmentIdOrderByInitiatedAtAscIdAsc(Long shipmentId);
}
