package com.banka1.account_service.repository;

import com.banka1.account_service.domain.Currency;
import com.banka1.account_service.domain.enums.CurrencyCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CurrencyRepository extends JpaRepository<Currency,Long> {
    Optional<Currency> findByOznaka(CurrencyCode oznaka);
}
