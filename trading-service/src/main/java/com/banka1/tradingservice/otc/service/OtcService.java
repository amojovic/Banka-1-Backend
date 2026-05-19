package com.banka1.tradingservice.otc.service;

import com.banka1.order.client.ClientClient;
import com.banka1.order.client.StockClient;
import com.banka1.order.dto.CustomerDto;
import com.banka1.tradingservice.otc.client.UserServiceClient;
import com.banka1.order.dto.StockListingDto;
import com.banka1.order.entity.Portfolio;
import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.tradingservice.otc.domain.OptionContract;
import com.banka1.tradingservice.otc.domain.OptionContractStatus;
import com.banka1.tradingservice.otc.domain.OtcOffer;
import com.banka1.tradingservice.otc.domain.OtcOfferRevision;
import com.banka1.tradingservice.otc.domain.OtcOfferStatus;
import com.banka1.tradingservice.otc.domain.OtcRevisionAction;
import com.banka1.tradingservice.otc.dto.CounterOfferRequest;
import com.banka1.tradingservice.otc.dto.CreateOtcOfferRequest;
import com.banka1.tradingservice.otc.dto.CreateOtcPositionRequest;
import com.banka1.tradingservice.otc.dto.OtcOfferDto;
import com.banka1.tradingservice.otc.dto.OtcOfferRevisionDto;
import com.banka1.tradingservice.otc.dto.OtcPositionDto;
import com.banka1.tradingservice.otc.dto.PublicStockDto;
import com.banka1.tradingservice.otc.dto.PublicStockSellerDto;
import com.banka1.tradingservice.otc.dto.UpdateOtcPositionRequest;
import com.banka1.tradingservice.otc.exception.InsufficientPublicStockException;
import com.banka1.tradingservice.otc.notification.OtcNotificationProducer;
import com.banka1.tradingservice.otc.repository.OptionContractRepository;
import com.banka1.tradingservice.otc.repository.OtcOfferRepository;
import com.banka1.tradingservice.otc.repository.OtcOfferRevisionRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final OtcOfferRevisionRepository revisionRepository;
    private final PortfolioRepository portfolioRepository;
    private final StockClient stockClient;
    private final ClientClient clientClient;
    private final RabbitTemplate rabbitTemplate;
    private final OtcPortfolioService portfolioService;
    private final UserServiceClient userServiceClient;
    private final OtcNotificationProducer notificationProducer;

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

        // WP-16 (Celina 4.2): istorija pregovora — CREATE revizija.
        // Stara polja su null (ponuda jos nije postojala), nova = pocetne vrednosti.
        OtcOfferRevision revision = baseRevision(saved, OtcRevisionAction.CREATE, buyerId, buyerName, "BUYER");
        applyNewValues(revision, saved);
        revisionRepository.save(revision);

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

        // WP-16 (Celina 4.2): snapshot starih vrednosti PRE izmene reda u mestu.
        OtcOfferRevision revision = baseRevision(offer, OtcRevisionAction.COUNTER, actorId, actorName,
                isBuyerActing ? "BUYER" : "SELLER");
        applyOldValues(revision, offer);

        offer.setAmount(req.getAmount());
        offer.setPricePerStock(req.getPricePerStock());
        offer.setPremium(req.getPremium());
        offer.setSettlementDate(req.getSettlementDate());
        offer.setModifiedBy(actorName);

        // Status flip
        offer.setStatus(isBuyerActing ? OtcOfferStatus.PENDING_SELLER : OtcOfferStatus.PENDING_BUYER);

        // WP-16: nove vrednosti = stanje posle izmene; revizija se cuva u istoj transakciji.
        applyNewValues(revision, offer);
        revisionRepository.save(revision);

        // WP-15 (Celina 4.1): obavesti drugu stranu (onu na koju se sad ceka)
        // da je stigla protivponuda.
        Long counterpartyId = isBuyerActing ? offer.getSellerId() : offer.getBuyerId();
        Long notifyOfferId = offer.getId();
        registerAfterCommit(() -> notificationProducer.notifyCounterOffer(notifyOfferId, counterpartyId));

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
    public OtcOfferDto accept(Long offerId, Long actorId) {
        // Pessimistic lock — serijalizuje istovremene accept pozive za istu ponudu.
        OtcOffer offer = otcOfferRepository.findByIdForUpdate(offerId)
                .orElseThrow(() -> new IllegalArgumentException("OTC ponuda " + offerId + " ne postoji."));

        // Accept is allowed for whichever party is currently being asked:
        //   PENDING_SELLER → seller accepts a buyer's offer/counter
        //   PENDING_BUYER  → buyer accepts a seller's counter
        boolean isSeller = offer.getSellerId().equals(actorId);
        boolean isBuyer  = offer.getBuyerId().equals(actorId);
        if (!isSeller && !isBuyer) {
            throw new IllegalStateException("Korisnik " + actorId + " nije ucesnik OTC ponude " + offerId + ".");
        }
        if (offer.getStatus() == OtcOfferStatus.PENDING_SELLER && !isSeller) {
            throw new IllegalStateException("Na potezu je prodavac — samo prodavac moze prihvatiti ponudu.");
        }
        if (offer.getStatus() == OtcOfferStatus.PENDING_BUYER && !isBuyer) {
            throw new IllegalStateException("Na potezu je kupac — samo kupac moze prihvatiti kontraponudu.");
        }
        if (offer.getStatus() != OtcOfferStatus.PENDING_SELLER && offer.getStatus() != OtcOfferStatus.PENDING_BUYER) {
            throw new IllegalStateException("Ponuda nije aktivna (trenutno: " + offer.getStatus() + ").");
        }

        Long sellerId = offer.getSellerId();

        // Invariant: pendingNegotiations + newAmount <= publicQuantity (remaining OTC capacity).
        long otcCapacity         = portfolioService.getOtcCapacity(sellerId, offer.getStockTicker());
        long pendingNegotiations = otcOfferRepository.sumPendingBySellerAndTickerExcluding(sellerId, offer.getStockTicker(), offerId);
        long requested           = offer.getAmount() == null ? 0L : offer.getAmount().longValue();
        if (pendingNegotiations + requested > otcCapacity) {
            throw new InsufficientPublicStockException(
                    "Prodavac " + sellerId + " ima preostalih " + otcCapacity + " " + offer.getStockTicker()
                    + " za OTC; " + pendingNegotiations + " je u pregovorima; ne moze se rezervisati jos " + requested + ".");
        }

        offer.setStatus(OtcOfferStatus.ACCEPTED);
        offer.setModifiedBy("user#" + actorId);

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

        portfolioService.reserveForContract(sellerId, offer.getStockTicker(), offer.getAmount());

        // WP-16 (Celina 4.2): istorija pregovora — ACCEPT revizija (bez izmene vrednosti).
        OtcOfferRevision revision = baseRevision(offer, OtcRevisionAction.ACCEPT, actorId,
                resolveActorName(actorId), isSeller ? "SELLER" : "BUYER");
        applyNewValues(revision, offer);
        revisionRepository.save(revision);

        registerAfterCommit(() -> publishSagaPremiumTransfer(savedContract.getId(), offer));

        // WP-15 (Celina 4.1): obavesti drugu stranu (onu koja nije prihvatila)
        // da je ponuda prihvacena.
        Long acceptedOfferId = offer.getId();
        Long acceptCounterpartyId = counterpartyOf(offer, actorId);
        registerAfterCommit(() -> notificationProducer.notifyAccepted(acceptedOfferId, acceptCounterpartyId));

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
                if (listing != null && ticker.equalsIgnoreCase(listing.getTicker())) {
                    // Ako je prodavac izlozio poziciju za OTC, publicQuantity je gornja granica.
                    // Inace, koristi ukupnu kolicinu (privatni pregovor).
                    if (Boolean.TRUE.equals(p.getIsPublic())
                            && p.getPublicQuantity() != null && p.getPublicQuantity() > 0) {
                        total += p.getPublicQuantity().longValue();
                    } else if (p.getQuantity() != null) {
                        total += p.getQuantity().longValue();
                    }
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
        boolean rejectByBuyer = offer.getBuyerId().equals(actorId);
        if (!rejectByBuyer && !offer.getSellerId().equals(actorId)) {
            throw new IllegalStateException("Korisnik " + actorId + " nije ucesnik ponude.");
        }
        offer.setStatus(OtcOfferStatus.REJECTED);
        offer.setModifiedBy("user#" + actorId);

        // WP-16 (Celina 4.2): istorija pregovora — REJECT revizija (bez izmene vrednosti).
        OtcOfferRevision revision = baseRevision(offer, OtcRevisionAction.REJECT, actorId,
                resolveActorName(actorId), rejectByBuyer ? "BUYER" : "SELLER");
        applyNewValues(revision, offer);
        revisionRepository.save(revision);

        // WP-15 (Celina 4.1): obavesti drugu stranu da je ponuda odbijena.
        Long rejectedOfferId = offer.getId();
        Long rejectCounterpartyId = counterpartyOf(offer, actorId);
        registerAfterCommit(() -> notificationProducer.notifyRejected(rejectedOfferId, rejectCounterpartyId));

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
     * WP-16 (Celina 4.2): istorija pregovora — ponude u kojima je dati korisnik
     * ucestvovao (kao kupac ili prodavac), preko SVIH statusa ukljucujuci finalne
     * ({@code ACCEPTED}/{@code REJECTED}/{@code WITHDRAWN}/{@code EXPIRED}).
     *
     * <p>Komplementarno sa {@link #activeForUser(Long)} koji vraca samo aktivne
     * (ne-finalne) ponude. Filteri su opcioni — kad su {@code null} ne ogranicavaju.
     *
     * @param userId       korisnik ciju istoriju trazimo (kupac ili prodavac)
     * @param status       opcioni filter po statusu ponude
     * @param from         opcioni donji ogranicnik {@code lastModified} (inkluzivno)
     * @param to           opcioni gornji ogranicnik {@code lastModified} (inkluzivno)
     * @param counterparty opcioni filter po drugoj strani (ID ili ime)
     * @return ponude, najskorije izmenjene prvo
     */
    @Transactional(readOnly = true)
    public List<OtcOfferDto> historyForUser(Long userId, OtcOfferStatus status,
                                            LocalDateTime from, LocalDateTime to, String counterparty) {
        Long counterpartyId = parseCounterpartyId(counterparty);
        Specification<OtcOffer> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // Korisnik je ucesnik: kupac ILI prodavac.
            predicates.add(cb.or(
                    cb.equal(root.get("buyerId"), userId),
                    cb.equal(root.get("sellerId"), userId)));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("lastModified"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("lastModified"), to));
            }
            if (counterpartyId != null) {
                // Druga strana = onaj ucesnik koji nije pozivalac.
                predicates.add(cb.or(
                        cb.and(cb.equal(root.get("buyerId"), userId),
                                cb.equal(root.get("sellerId"), counterpartyId)),
                        cb.and(cb.equal(root.get("sellerId"), userId),
                                cb.equal(root.get("buyerId"), counterpartyId))));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        List<OtcOffer> offers = new ArrayList<>(otcOfferRepository.findAll(spec));
        // Najskorije izmenjene prvo.
        offers.sort((a, b) -> b.getLastModified().compareTo(a.getLastModified()));

        String counterpartyName = normalizeName(counterparty);
        return offers.stream()
                // Kad je counterparty filter bio ime (ne broj), suzeavamo po imenu druge strane.
                .filter(o -> counterpartyName == null
                        || counterpartyName.equalsIgnoreCase(
                                normalizeName(resolveClientName(counterpartyIdOf(o, userId)))))
                .map(this::toDto)
                .toList();
    }

    /**
     * WP-16 (Celina 4.2): kompletan revizioni trag jedne ponude (najstariji prvi).
     * Dostupno samo ucesniku pregovora; inace {@link IllegalArgumentException}
     * (kontroler ga mapira u 404 — ne otkrivamo postojanje tudje ponude).
     *
     * @param offerId  ID ponude
     * @param callerId korisnik koji trazi trag
     * @return uredjena lista {@link OtcOfferRevisionDto}
     */
    @Transactional(readOnly = true)
    public List<OtcOfferRevisionDto> revisionTrail(Long offerId, Long callerId) {
        OtcOffer offer = requireOffer(offerId);
        if (!offer.getBuyerId().equals(callerId) && !offer.getSellerId().equals(callerId)) {
            // Namerno ista poruka kao "ne postoji" — kontroler vraca 404.
            throw new IllegalArgumentException("OTC ponuda " + offerId + " ne postoji.");
        }
        return revisionRepository.findByOfferIdOrderByCreatedAtAscIdAsc(offerId).stream()
                .map(OtcService::toRevisionDto)
                .toList();
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

    /**
     * Povlacenje sopstvene ponude pre nego sto je druga strana odgovorila.
     * Kupac moze povuci dok je PENDING_SELLER; prodavac dok je PENDING_BUYER.
     */
    @Transactional
    public OtcOfferDto withdraw(Long offerId, Long actorId) {
        OtcOffer offer = requireOffer(offerId);
        boolean isBuyer  = offer.getBuyerId().equals(actorId);
        boolean isSeller = offer.getSellerId().equals(actorId);
        if (!isBuyer && !isSeller) {
            throw new IllegalStateException("Korisnik " + actorId + " nije ucesnik ponude.");
        }
        if (isBuyer && offer.getStatus() != OtcOfferStatus.PENDING_SELLER) {
            throw new IllegalStateException("Kupac moze povuci samo dok je ponuda PENDING_SELLER.");
        }
        if (isSeller && offer.getStatus() != OtcOfferStatus.PENDING_BUYER) {
            throw new IllegalStateException("Prodavac moze povuci samo dok je ponuda PENDING_BUYER.");
        }
        offer.setStatus(OtcOfferStatus.WITHDRAWN);
        offer.setModifiedBy("user#" + actorId);

        // WP-16 (Celina 4.2): istorija pregovora — WITHDRAW revizija (bez izmene vrednosti).
        OtcOfferRevision revision = baseRevision(offer, OtcRevisionAction.WITHDRAW, actorId,
                resolveActorName(actorId), isBuyer ? "BUYER" : "SELLER");
        applyNewValues(revision, offer);
        revisionRepository.save(revision);

        // WP-15 (Celina 4.1): obavesti drugu stranu da je ponuda povucena.
        Long withdrawnOfferId = offer.getId();
        Long withdrawCounterpartyId = counterpartyOf(offer, actorId);
        registerAfterCommit(() -> notificationProducer.notifyWithdrawn(withdrawnOfferId, withdrawCounterpartyId));

        return toDto(offer);
    }

    // ---- My OTC Positions ----

    @Transactional(readOnly = true)
    public List<OtcPositionDto> getMyPositions(Long userId) {
        return portfolioRepository.findByUserId(userId).stream()
                .filter(p -> ListingType.STOCK.equals(p.getListingType()) && Boolean.TRUE.equals(p.getIsPublic()))
                .map(this::toPositionDto)
                .toList();
    }

    @Transactional
    public OtcPositionDto addPosition(Long userId, CreateOtcPositionRequest req) {
        Portfolio p = portfolioRepository.findByUserIdAndListingId(userId, req.getListingId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Portfolio pozicija za listing " + req.getListingId() + " ne postoji."));
        if (!ListingType.STOCK.equals(p.getListingType())) {
            throw new IllegalStateException("Samo STOCK pozicije se mogu izloziti za OTC.");
        }
        int reserved = p.getReservedQuantity() == null ? 0 : p.getReservedQuantity();
        int maxAllowed = (p.getQuantity() == null ? 0 : p.getQuantity()) - reserved;
        if (req.getPublicQuantity() > maxAllowed) {
            throw new IllegalStateException(
                    "Nije moguce izloziti " + req.getPublicQuantity() + " akcija; "
                    + "posedujete " + p.getQuantity() + ", od toga " + reserved + " rezervisano, "
                    + "maksimum za OTC je " + maxAllowed + ".");
        }
        p.setIsPublic(true);
        p.setPublicQuantity(req.getPublicQuantity());
        return toPositionDto(portfolioRepository.save(p));
    }

    @Transactional
    public OtcPositionDto updatePosition(Long userId, Long positionId, UpdateOtcPositionRequest req) {
        Portfolio p = requireOwnedPosition(userId, positionId);
        int reserved = p.getReservedQuantity() == null ? 0 : p.getReservedQuantity();
        int maxAllowed = (p.getQuantity() == null ? 0 : p.getQuantity()) - reserved;
        if (req.getPublicQuantity() > maxAllowed) {
            throw new IllegalStateException(
                    "Nije moguce izloziti " + req.getPublicQuantity() + " akcija; "
                    + "posedujete " + p.getQuantity() + ", od toga " + reserved + " rezervisano, "
                    + "maksimum za OTC je " + maxAllowed + ".");
        }
        if (req.getPublicQuantity() < reserved) {
            throw new IllegalStateException(
                    "Nije moguce smanjiti izlozenu kolicinu ispod rezervisane kolicine " + reserved + ".");
        }
        p.setPublicQuantity(req.getPublicQuantity());
        return toPositionDto(portfolioRepository.save(p));
    }

    @Transactional
    public void removePosition(Long userId, Long positionId) {
        Portfolio p = requireOwnedPosition(userId, positionId);
        if (p.getReservedQuantity() != null && p.getReservedQuantity() > 0) {
            throw new IllegalStateException(
                    "Nije moguce ukloniti poziciju dok su akcije rezervisane (" + p.getReservedQuantity() + ").");
        }
        p.setIsPublic(false);
        p.setPublicQuantity(0);
        portfolioRepository.save(p);
    }

    @Transactional(readOnly = true)
    public List<PublicStockDto> getPublicStocks(Long excludeUserId, boolean supervisorView) {
        // Supervisors see only stocks put up by actuaries (AGENT employees mapped to their client IDs).
        java.util.Set<Long> allowedActuaryIds = null;
        if (supervisorView) {
            allowedActuaryIds = new java.util.HashSet<>(userServiceClient.getActuaryClientIds());
        }
        final java.util.Set<Long> actuaryClientIds = allowedActuaryIds;

        Map<String, List<PublicStockSellerDto>> byTicker = new LinkedHashMap<>();

        for (Portfolio p : portfolioRepository.findAllPublicStocks()) {
            // Supervisors are employees (not clients), so their numeric id can collide with
            // a client id — skip the own-stock exclusion entirely for supervisor view.
            if (!supervisorView && excludeUserId != null && excludeUserId.equals(p.getUserId())) continue;
            if (supervisorView && !actuaryClientIds.contains(p.getUserId())) continue;
            int qty = p.getPublicQuantity() == null ? 0 : p.getPublicQuantity();
            if (qty <= 0) continue;

            String ticker = resolveSellerOwnedTicker(p.getListingId());
            if (ticker == null) continue;

            String name = resolveClientName(p.getUserId());
            byTicker.computeIfAbsent(ticker, k -> new ArrayList<>())
                    .add(new PublicStockSellerDto(p.getUserId(), name, qty));
        }

        return byTicker.entrySet().stream()
                .map(e -> new PublicStockDto(e.getKey(), e.getValue()))
                .toList();
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

    /**
     * WP-15: vraca id druge strane u ponudi — onu koja NIJE {@code actorId}.
     * Koristi se da se notifikacija o accept/reject/withdraw posalje
     * suprotnoj strani od one koja je izvrsila akciju.
     */
    private Long counterpartyOf(OtcOffer offer, Long actorId) {
        return offer.getBuyerId().equals(actorId) ? offer.getSellerId() : offer.getBuyerId();
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

    private String resolveSellerOwnedTicker(Long listingId) {
        if (listingId == null) return null;
        try {
            StockListingDto listing = stockClient.getListing(listingId);
            return listing == null ? null : listing.getTicker();
        } catch (Exception e) {
            log.debug("Could not resolve ticker for listingId={}: {}", listingId, e.getMessage());
            return null;
        }
    }

    private String resolveClientName(Long userId) {
        if (userId == null) return null;
        try {
            CustomerDto customer = clientClient.getCustomer(userId);
            if (customer == null) return null;
            String first = customer.getFirstName() != null ? customer.getFirstName() : "";
            String last  = customer.getLastName()  != null ? customer.getLastName()  : "";
            return (first + " " + last).trim();
        } catch (Exception e) {
            log.debug("Could not resolve client name for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private OtcOffer requireOffer(Long id) {
        return otcOfferRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("OTC ponuda " + id + " ne postoji."));
    }

    // ---------------------- WP-16: revizije pregovora ----------------------

    /**
     * WP-16: pravi prazan {@link OtcOfferRevision} za datu ponudu i akciju —
     * value polja se popunjavaju odvojeno preko {@link #applyOldValues}/
     * {@link #applyNewValues}.
     */
    private OtcOfferRevision baseRevision(OtcOffer offer, OtcRevisionAction action,
                                          Long actorId, String actorName, String actorRole) {
        return OtcOfferRevision.builder()
                .offerId(offer.getId())
                .action(action)
                .actorUserId(actorId)
                .actorName(actorName)
                .actorRole(actorRole)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /** WP-16: kopira trenutne vrednosti ponude u {@code old*} polja revizije (snapshot pre izmene). */
    private void applyOldValues(OtcOfferRevision revision, OtcOffer offer) {
        revision.setOldAmount(offer.getAmount());
        revision.setOldPricePerStock(offer.getPricePerStock());
        revision.setOldPremium(offer.getPremium());
        revision.setOldSettlementDate(offer.getSettlementDate());
    }

    /** WP-16: kopira trenutne vrednosti ponude u {@code new*} polja revizije (stanje posle izmene). */
    private void applyNewValues(OtcOfferRevision revision, OtcOffer offer) {
        revision.setNewAmount(offer.getAmount());
        revision.setNewPricePerStock(offer.getPricePerStock());
        revision.setNewPremium(offer.getPremium());
        revision.setNewSettlementDate(offer.getSettlementDate());
    }

    /**
     * WP-16: razresava citljivo ime aktora za reviziju. {@code accept}/{@code reject}/
     * {@code withdraw} ne primaju ime, pa se best-effort razresava preko client-service-a;
     * {@code null} je prihvatljiv (aktor je uvek identifikovan preko {@code actorUserId}).
     */
    private String resolveActorName(Long actorId) {
        return resolveClientName(actorId);
    }

    /** WP-16: ID druge strane u ponudi (onaj ucesnik koji nije {@code selfId}). */
    private Long counterpartyIdOf(OtcOffer offer, Long selfId) {
        return offer.getBuyerId().equals(selfId) ? offer.getSellerId() : offer.getBuyerId();
    }

    /**
     * WP-16: ako je {@code counterparty} ceo broj tretira ga kao ID druge strane;
     * inace ({@code null} ili ime) vraca {@code null} — filtriranje po imenu radi
     * {@link #normalizeName}.
     */
    private Long parseCounterpartyId(String counterparty) {
        if (counterparty == null || counterparty.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(counterparty.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * WP-16: normalizuje string za poredjenje imena — vraca {@code null} kada je
     * {@code raw} prazan ili izgleda kao broj (broj se tretira kao ID, ne ime).
     */
    private String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            Long.parseLong(trimmed);
            return null;
        } catch (NumberFormatException ex) {
            return trimmed;
        }
    }

    private static OtcOfferRevisionDto toRevisionDto(OtcOfferRevision r) {
        return OtcOfferRevisionDto.builder()
                .id(r.getId())
                .offerId(r.getOfferId())
                .action(r.getAction())
                .actorUserId(r.getActorUserId())
                .actorName(r.getActorName())
                .actorRole(r.getActorRole())
                .oldAmount(r.getOldAmount())
                .newAmount(r.getNewAmount())
                .oldPricePerStock(r.getOldPricePerStock())
                .newPricePerStock(r.getNewPricePerStock())
                .oldPremium(r.getOldPremium())
                .newPremium(r.getNewPremium())
                .oldSettlementDate(r.getOldSettlementDate())
                .newSettlementDate(r.getNewSettlementDate())
                .createdAt(r.getCreatedAt())
                .build();
    }

    private Portfolio requireOwnedPosition(Long userId, Long positionId) {
        Portfolio p = portfolioRepository.findById(positionId)
                .orElseThrow(() -> new IllegalArgumentException("Portfolio pozicija " + positionId + " ne postoji."));
        if (!p.getUserId().equals(userId)) {
            throw new IllegalStateException("Pozicija " + positionId + " ne pripada korisniku " + userId + ".");
        }
        return p;
    }

    private OtcPositionDto toPositionDto(Portfolio p) {
        String ticker = resolveSellerOwnedTicker(p.getListingId());
        int reserved = p.getReservedQuantity() != null ? p.getReservedQuantity() : 0;
        int quantity = p.getQuantity() != null ? p.getQuantity() : 0;
        return OtcPositionDto.builder()
                .id(p.getId())
                .listingId(p.getListingId())
                .stockTicker(ticker)
                .totalQuantity(quantity)
                .reservedQuantity(reserved)
                .publicQuantity(p.getPublicQuantity() != null ? p.getPublicQuantity() : 0)
                .availableQuantity(quantity - reserved)
                .build();
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
