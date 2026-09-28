package com.chris64233.cc.coldchain.repository;

import com.chris64233.cc.coldchain.domain.Investigation;
import com.chris64233.cc.coldchain.domain.InvestigationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvestigationRepository extends JpaRepository<Investigation, Long> {

    Optional<Investigation> findByInvestigationNo(String investigationNo);

    List<Investigation> findByShipmentIdOrderByIdAsc(Long shipmentId);

    List<Investigation> findByShipmentIdAndStatusInOrderByIdAsc(Long shipmentId, List<InvestigationStatus> statuses);
}
