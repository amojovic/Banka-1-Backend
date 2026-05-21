package com.banka1.tradingservice.interbank.repository;

import com.banka1.tradingservice.interbank.model.InterbankOptionReservation;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository za {@link InterbankOptionReservation}. Negotiation ID je PK pa
 * standardni JpaRepository signature zadovoljava lookup.
 */
public interface InterbankOptionReservationRepository
        extends JpaRepository<InterbankOptionReservation, String> {
}
