package com.banka1.tradingservice.otc.service;

import com.banka1.order.client.StockClient;
import com.banka1.order.dto.StockListingDto;
import com.banka1.order.entity.Portfolio;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.tradingservice.otc.domain.OptionContract;
import com.banka1.tradingservice.otc.domain.OptionContractStatus;
import com.banka1.tradingservice.otc.domain.OtcOffer;
import com.banka1.tradingservice.otc.domain.OtcOfferStatus;
import com.banka1.tradingservice.otc.dto.CounterOfferRequest;
import com.banka1.tradingservice.otc.dto.CreateOtcOfferRequest;
import com.banka1.tradingservice.otc.dto.OtcOfferDto;
import com.banka1.tradingservice.otc.exception.InsufficientPublicStockException;
import com.banka1.tradingservice.otc.repository.OptionContractRepository;
import com.banka1.tradingservice.otc.repository.OtcOfferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * OTC pregovor servis (PR_04 C4.x).
 *
 * <p>Pregovori traju "back and forth"; svaka strana moze da posalje kontraponudu, prihvati
 * ili odustane. Kada prodavac konacno prihvati (status ACCEPTED), inicira se SAGA
 * OTC_PREMIUM_TRANSFER kroz {@code saga.events} exchange — premija se prebacuje sa
 * kupcevog na prodavcev racun. Posle uspeha SAGA-e, kreira se OptionContract.
 *
 * <p>SAGA orchestracija je asinhrona — saga-orchestrator-service prima event, izvrsava
 * korake (rezervacija/transfer), i salje response event nazad. Ovaj servis registruje
 * afterCommit callback da publishuje event tek kada je DB transakcija uspesno commit-ovana
 * (sprecava ghost SAGA pokretanja na rolled-back transakcijama).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtcService {

    private final OtcOfferRepository otcOfferRepository;
    private final OptionContractRepository optionContractRepository;
    private final PortfolioRepository portfolioRepository;
    private final StockClient stockClient;
    private final RabbitTemplate rabbitTemplate;

    private static final String SAGA_EVENTS_EXCHANGE = "saga.events";
    private static final String SAGA_OTC_PREMIUM_RK = "otc.premium.transfer.requested";

    @Transactional
    public OtcOfferDto createOffer(Long buyerId, CreateOtcOfferRequest req, String buyerName) {
        OtcOffer offer = new OtcOffer();
        offer.setStockTicker(req.getStockTicker());
        offer.setBuyerId(buyerId);
        offer.setSellerId(req.getSellerId());
        offer.setAmount(req.getAmount());
        offer.setPricePerStock(req.getPricePerStock());
        offer.setPremium(req.getPremium());
        offer.setSettlementDate(req.getSettlementDate());
        offer.setStatus(OtcOfferStatus.PENDING_SELLER);
        offer.setModifiedBy(buyerName);

        OtcOffer saved = otcOfferRepository.save(offer);
        log.info("Created OTC offer {} ({} {} shares @ {}) by buyer {}",
                saved.getId(), saved.getStockTicker(), saved.getAmount(), saved.getPricePerStock(), buyerName);
        return toDto(saved);
    }

    /**
     * Counter-offer flip: prodavac pravi kontraponudu kupcu (status -> PENDING_BUYER) ili
     * obrnuto. Modifikovana polja se zamenjuju vrednostima iz request-a.
     */
    @Transactional
    public OtcOfferDto counterOffer(Long offerId, Long actorId, CounterOfferRequest req, String actorName) {
        OtcOffer offer = requireOffer(offerId);

        boolean isBuyerActing  = offer.getBuyerId().equals(actorId);
        boolean isSellerActing = offer.getSellerId().equals(actorId);
        if (!isBuyerActing && !isSellerActing) {
            throw new IllegalStateException("Korisnik " + actorId + " nije ucesnik OTC ponude " + offerId);
        }
        if (offer.getStatus() == OtcOfferStatus.ACCEPTED || offer.getStatus() == OtcOfferStatus.REJECTED
                || offer.getStatus() == OtcOfferStatus.EXPIRED) {
            throw new IllegalStateException("Ponuda je vec u finalnom statusu: " + offer.getStatus());
        }

        offer.setAmount(req.getAmount());
        offer.setPricePerStock(req.getPricePerStock());
        offer.setPremium(req.getPremium());
        offer.setSettlementDate(req.getSettlementDate());
        offer.setModifiedBy(actorName);

        // Status flip
        offer.setStatus(isBuyerActing ? OtcOfferStatus.PENDING_SELLER : OtcOfferStatus.PENDING_BUYER);

        return toDto(offer);
    }

    /**
     * Prodavac prihvata ponudu. Kreira se OptionContract sa statusom
     * {@code PENDING_PREMIUM} (KRIT #2 fix) i inicira SAGA premium transfer.
     *
     * <p>Pre kreiranja ugovora vrsi se reserved-stock invariant provera
     * (KRIT #3): {@code sum(active OptionContract.amount za seller+ticker) +
     * offer.amount <= portfolio.quantity}. Time se sprecava da prodavac
     * prihvati vise ponuda za istu poziciju nego sto akcija ima.
     */
    @Transactional
    public OtcOfferDto accept(Long offerId, Long sellerId) {
        OtcOffer offer = requireOffer(offerId);
        if (!offer.getSellerId().equals(sellerId)) {
            throw new IllegalStateException("Samo prodavac moze prihvatiti ponudu.");
        }
        if (offer.getStatus() != OtcOfferStatus.PENDING_SELLER) {
            throw new IllegalStateException(
                    "Ponuda nije u stanju PENDING_SELLER (trenutno: " + offer.getStatus() + ").");
        }

        // KRIT #3: reserved-stock invariant. Suma aktivnih + nova ponuda ne sme da
        // prelazi prodavcevu kolicinu akcija u portfoliu za taj ticker.
        long ownedQuantity = resolveSellerOwnedQuantity(sellerId, offer.getStockTicker());
        long activeSum = optionContractRepository.sumActiveBySellerAndTicker(sellerId, offer.getStockTicker());
        long requested = offer.getAmount() == null ? 0L : offer.getAmount().longValue();
        if (activeSum + requested > ownedQuantity) {
            throw new InsufficientPublicStockException(
                    "Reserved-stock invariant violated: seller " + sellerId + " has "
                            + ownedQuantity + " " + offer.getStockTicker()
                            + " shares but already " + activeSum
                            + " are committed; cannot reserve " + requested + " more.");
        }

        offer.setStatus(OtcOfferStatus.ACCEPTED);
        offer.setModifiedBy("seller#" + sellerId);

        // KRIT #2: status=PENDING_PREMIUM dok SAGA premium transfer ne uspe;
        // listener (OtcPremiumCompletedListener / FailedListener) flipuje
        // PENDING_PREMIUM -> ACTIVE ili PENDING_PREMIUM -> CANCELED.
        OptionContract contract = new OptionContract();
        contract.setOfferId(offer.getId());
        contract.setStockTicker(offer.getStockTicker());
        contract.setBuyerId(offer.getBuyerId());
        contract.setSellerId(sellerId);
        contract.setAmount(offer.getAmount());
        contract.setPricePerStock(offer.getPricePerStock());
        contract.setSettlementDate(offer.getSettlementDate());
        contract.setStatus(OptionContractStatus.PENDING_PREMIUM);
        OptionContract savedContract = optionContractRepository.save(contract);

        // Publish saga event TEK posle commit-a, da bismo izbegli ghost saga na rollback-u.
        registerAfterCommit(() -> publishSagaPremiumTransfer(savedContract.getId(), offer));

        return toDto(offer);
    }

    /**
     * Za KRIT #3 invariant: vraca ukupnu kolicinu STOCK akcija koje prodavac
     * poseduje za dati ticker. Portfolio drzi samo {@code listingId}, pa
     * resolve-ujemo ticker preko {@link StockClient}.
     *
     * <p>Defensive: bilo kakav stock-service hop koji baci exception se
     * tretira kao "nemamo listing info", i ta pozicija se preskace.
     * Ako prodavac nema poziciju za ticker, vraca 0 (invariant ce odmah
     * blokirati prihvatanje ponude).
     */
    private long resolveSellerOwnedQuantity(Long sellerId, String ticker) {
        if (sellerId == null || ticker == null || ticker.isBlank()) {
            return 0L;
        }
        long total = 0L;
        for (Portfolio p : portfolioRepository.findByUserId(sellerId)) {
            try {
                StockListingDto listing = stockClient.getListing(p.getListingId());
                if (listing != null
                        && ticker.equalsIgnoreCase(listing.getTicker())
                        && p.getQuantity() != null) {
                    total += p.getQuantity().longValue();
                }
            } catch (Exception ignored) {
                // Listing nije dostupan iz market-service-a; preskoci poziciju.
            }
        }
        return total;
    }

    @Transactional
    public OtcOfferDto reject(Long offerId, Long actorId) {
        OtcOffer offer = requireOffer(offerId);
        if (!offer.getBuyerId().equals(actorId) && !offer.getSellerId().equals(actorId)) {
            throw new IllegalStateException("Korisnik " + actorId + " nije ucesnik ponude.");
        }
        offer.setStatus(OtcOfferStatus.REJECTED);
        offer.setModifiedBy("user#" + actorId);
        return toDto(offer);
    }

    @Transactional(readOnly = true)
    public List<OtcOfferDto> activeForUser(Long userId) {
        List<OtcOfferStatus> active = List.of(OtcOfferStatus.PENDING_BUYER, OtcOfferStatus.PENDING_SELLER);
        return otcOfferRepository
                .findByBuyerIdAndStatusInOrSellerIdAndStatusIn(userId, active, userId, active)
                .stream().map(this::toDto).toList();
    }

    /**
     * PR_13 C13.3: vraca sklopljene opcione ugovore za current user-a (kupca ili prodavca)
     * sa opcionim filtriranjem po statusu (ACTIVE | EXERCISED | EXPIRED).
     * Frontend OtcContractsComponent (PR_11 C11.7) ovo poziva.
     */
    @Transactional(readOnly = true)
    public List<com.banka1.tradingservice.otc.dto.OptionContractDto> myContracts(Long userId,
            com.banka1.tradingservice.otc.domain.OptionContractStatus statusFilter) {
        java.util.stream.Stream<com.banka1.tradingservice.otc.domain.OptionContract> stream;
        if (statusFilter != null) {
            stream = java.util.stream.Stream.concat(
                    optionContractRepository.findByBuyerIdAndStatus(userId, statusFilter).stream(),
                    optionContractRepository.findBySellerIdAndStatus(userId, statusFilter).stream()
            );
        } else {
            // Bez filtera: dohvati sve preko 3 statusa.
            stream = java.util.Arrays.stream(com.banka1.tradingservice.otc.domain.OptionContractStatus.values())
                    .flatMap(s -> java.util.stream.Stream.concat(
                            optionContractRepository.findByBuyerIdAndStatus(userId, s).stream(),
                            optionContractRepository.findBySellerIdAndStatus(userId, s).stream()
                    ));
        }
        return stream.distinct().map(c -> com.banka1.tradingservice.otc.dto.OptionContractDto.builder()
                .id(c.getId())
                .offerId(c.getOfferId())
                .stockTicker(c.getStockTicker())
                .buyerId(c.getBuyerId())
                .sellerId(c.getSellerId())
                .amount(c.getAmount())
                .pricePerStock(c.getPricePerStock())
                .settlementDate(c.getSettlementDate())
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .exercisedAt(c.getExercisedAt())
                .build()
        ).toList();
    }

    /**
     * Spec: "klikom na 'Iskoristi'... pokrece se transakcija po SAGA pattern-u".
     * Salje saga event OTC_EXERCISE_REQUESTED; saga-orchestrator izvrsava 5 koraka.
     */
    @Transactional
    public void exerciseContract(Long contractId, Long buyerId) {
        OptionContract c = optionContractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Ugovor " + contractId + " ne postoji."));
        if (!c.getBuyerId().equals(buyerId)) {
            throw new IllegalStateException("Samo kupac moze iskoristiti opciju.");
        }
        if (c.getStatus() != OptionContractStatus.ACTIVE) {
            throw new IllegalStateException("Ugovor nije aktivan: " + c.getStatus());
        }
        // Marker exercised; saga ce verifikovati i potvrditi/rollback-ovati.
        c.setExercisedAt(LocalDateTime.now());

        registerAfterCommit(() -> rabbitTemplate.convertAndSend(
                SAGA_EVENTS_EXCHANGE,
                "otc.exercise.requested",
                new OtcExerciseRequestedEvent(c.getId(), c.getBuyerId(), c.getSellerId(),
                        c.getStockTicker(), c.getAmount(), c.getPricePerStock())
        ));
    }

    // ---------------------- internal ----------------------

    private void publishSagaPremiumTransfer(Long contractId, OtcOffer offer) {
        rabbitTemplate.convertAndSend(
                SAGA_EVENTS_EXCHANGE,
                SAGA_OTC_PREMIUM_RK,
                new OtcPremiumTransferRequestedEvent(
                        contractId, offer.getBuyerId(), offer.getSellerId(), offer.getPremium())
        );
        log.info("Published saga event otc.premium.transfer.requested for contract {}", contractId);
    }

    private void registerAfterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }

    private OtcOffer requireOffer(Long id) {
        return otcOfferRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("OTC ponuda " + id + " ne postoji."));
    }

    private OtcOfferDto toDto(OtcOffer o) {
        return OtcOfferDto.builder()
                .id(o.getId())
                .stockTicker(o.getStockTicker())
                .buyerId(o.getBuyerId())
                .sellerId(o.getSellerId())
                .amount(o.getAmount())
                .pricePerStock(o.getPricePerStock())
                .premium(o.getPremium())
                .settlementDate(o.getSettlementDate())
                .status(o.getStatus())
                .modifiedBy(o.getModifiedBy())
                .lastModified(o.getLastModified())
                .build();
    }

    public record OtcPremiumTransferRequestedEvent(
            Long contractId, Long buyerId, Long sellerId, java.math.BigDecimal premium) {}

    public record OtcExerciseRequestedEvent(
            Long contractId, Long buyerId, Long sellerId,
            String stockTicker, Integer amount, java.math.BigDecimal pricePerStock) {}
}
