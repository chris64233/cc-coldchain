package com.chris64233.cc.coldchain.repository;

import com.chris64233.cc.coldchain.domain.HandoverRecord;
import com.chris64233.cc.coldchain.domain.HandoverStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HandoverRecordRepository extends JpaRepository<HandoverRecord, Long> {

    List<HandoverRecord> findByShipmentIdOrderByInitiatedAtAscIdAsc(Long shipmentId);

    boolean existsByShipmentIdAndStatus(Long shipmentId, HandoverStatus status);
}
