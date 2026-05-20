package com.banka1.interbank.repository;

import com.banka1.interbank.model.InterbankNegotiationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * PR_32 Phase 3: Spring Data repo za {@link InterbankNegotiationEntity}.
 *
 * <p>Glavni use-case-ovi:
 * <ul>
 *   <li>{@link #findByBuyerRoutingNumberAndBuyerId} — buyer-side "moje
 *       ponude koje sam poslao".</li>
 *   <li>{@link #findBySellerRoutingNumberAndSellerIdAndIsOngoing} —
 *       seller-side "ongoing ponude koje cekaju moj odgovor".</li>
 *   <li>{@link #findByRemoteNegotiationId} — kada smo MI buyer-side i partner
 *       (seller-bank) je authoritative; PUT/GET/DELETE od partner-a stizu sa
 *       {@code {partnerRouting, partnerAuthId}} u URL-u. Mi cuvamo
 *       partnerAuthId u {@code remote_negotiation_id} polju, pa lookup ide
 *       preko ovog finder-a per spec §3.2.</li>
 * </ul>
 */
public interface InterbankNegotiationRepository extends JpaRepository<InterbankNegotiationEntity, String> {

    List<InterbankNegotiationEntity> findByBuyerRoutingNumberAndBuyerId(
        int buyerRoutingNumber,
        String buyerId
    );

    List<InterbankNegotiationEntity> findBySellerRoutingNumberAndSellerIdAndIsOngoing(
        int sellerRoutingNumber,
        String sellerId,
        boolean isOngoing
    );

    /**
     * Tim 2 spec §3.2: seller-bank generise authoritative neg-id. Mi kao
     * buyer-bank cuvamo taj id u {@code remote_negotiation_id} polju i koristimo
     * (rn=partnerRouting, id=remoteNegotiationId) za sve sledece PUT/GET/DELETE.
     */
    Optional<InterbankNegotiationEntity> findByRemoteNegotiationId(String remoteNegotiationId);
}
