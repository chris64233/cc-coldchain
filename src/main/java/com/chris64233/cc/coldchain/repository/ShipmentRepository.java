package com.chris64233.cc.coldchain.repository;

import com.chris64233.cc.coldchain.domain.Shipment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByWaybillNo(String waybillNo);

    boolean existsByWaybillNo(String waybillNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.waybillNo = :waybillNo")
    Optional<Shipment> findByWaybillNoForUpdate(@Param("waybillNo") String waybillNo);
}
