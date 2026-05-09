package com.banka1.saga_orchestrator.repository;

import com.banka1.saga_orchestrator.domain.SagaInstance;
import com.banka1.saga_orchestrator.domain.SagaState;
import com.banka1.saga_orchestrator.domain.SagaType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {

    Page<SagaInstance> findByState(SagaState state, Pageable pageable);

    Page<SagaInstance> findBySagaType(SagaType sagaType, Pageable pageable);

    Page<SagaInstance> findByStateAndSagaType(SagaState state, SagaType sagaType, Pageable pageable);

    List<SagaInstance> findByStateIn(List<SagaState> states);
}
