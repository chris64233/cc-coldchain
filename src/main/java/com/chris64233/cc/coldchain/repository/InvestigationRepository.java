package com.chris64233.cc.coldchain.repository;

import com.chris64233.cc.coldchain.domain.Investigation;
import com.chris64233.cc.coldchain.domain.InvestigationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InvestigationRepository extends JpaRepository<Investigation, Long> {

    Optional<Investigation> findByInvestigationNo(String investigationNo);

    List<Investigation> findByShipmentIdOrderByCreatedAtAscIdAsc(Long shipmentId);

    List<Investigation> findByShipmentIdAndStatusInOrderByIdAsc(Long shipmentId,
                                                                Collection<InvestigationStatus> statuses);

    boolean existsByShipmentIdAndStatusIn(Long shipmentId, Collection<InvestigationStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Investigation i where i.investigationNo = :investigationNo")
    Optional<Investigation> findByInvestigationNoForUpdate(@Param("investigationNo") String investigationNo);
}
