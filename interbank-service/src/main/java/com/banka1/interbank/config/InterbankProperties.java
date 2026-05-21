package com.banka1.interbank.config;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PR_32 Phase 4: konfiguracija interbank protokola.
 *
 * <p>Cita iz {@code application.properties} sledece kljuceve:
 * <pre>
 * interbank.my-routing-number=111
 * interbank.my-bank-display-name=Banka 1
 * interbank.partners[0].routing-number=222
 * interbank.partners[0].display-name=Banka 2
 * interbank.partners[0].base-url=http://...
 * interbank.partners[0].inbound-token=...
 * interbank.partners[0].outbound-token=...
 * </pre>
 *
 * <p>{@link #findByInboundToken(String)} koristi {@link InterbankAuthFilter} da
 * mapira {@code X-Api-Key} header u partner banku.
 * {@link #partnerOrThrow(int)} koriste OUTBOUND klijenti kad spreme HTTP poziv.
 *
 * <p>NE koristimo Java {@code record} jer Spring Boot 4 binding za nested
 * {@code List<>} property-ja sa record-ima zahteva precizan match konstruktora
 * sto je krhko — koristimo mutable POJO sa Lombok-om.
 */
@ConfigurationProperties(prefix = "interbank")
public class InterbankProperties {

    private static final Logger log = LoggerFactory.getLogger(InterbankProperties.class);

    private int myRoutingNumber;
    private String myBankDisplayName;
    private List<Partner> partners = new ArrayList<>();

    /**
     * Tim 2 IMPORTANT-7: fail-loud (WARN log) ako partner config nedostaje ili
     * koristi dev-default token-e. Spring binding ne radi runtime validaciju, pa
     * silently empty list bi pustila servis u "operativan" stanje gde nijedan
     * partner ne moze da autentifikuje. Ako je {@code prod} profil aktivan a
     * lista je prazna, fail-fast kroz {@link IllegalStateException}.
     */
    @PostConstruct
    public void validateConfig() {
        if (partners == null || partners.isEmpty()) {
            log.warn("interbank.partners is empty — no incoming partner can authenticate; "
                    + "inter-bank protocol is effectively DISABLED.");
            return;
        }
        for (Partner p : partners) {
            if (p.getInboundToken() == null || p.getInboundToken().isBlank()) {
                log.warn("Partner routing={} has no inbound token configured.",
                        p.getRoutingNumber());
            } else if (p.getInboundToken().startsWith("dev-")) {
                log.warn("Partner routing={} koristi dev-default inbound token — "
                        + "DO NOT use in prod", p.getRoutingNumber());
            }
            if (p.getOutboundToken() == null || p.getOutboundToken().isBlank()) {
                log.warn("Partner routing={} has no outbound token configured.",
                        p.getRoutingNumber());
            }
            if (p.getBaseUrl() == null || p.getBaseUrl().isBlank()) {
                log.warn("Partner routing={} has no base-url configured.",
                        p.getRoutingNumber());
            }
        }
    }

    public int getMyRoutingNumber() {
        return myRoutingNumber;
    }

    public void setMyRoutingNumber(int myRoutingNumber) {
        this.myRoutingNumber = myRoutingNumber;
    }

    public String getMyBankDisplayName() {
        return myBankDisplayName;
    }

    public void setMyBankDisplayName(String myBankDisplayName) {
        this.myBankDisplayName = myBankDisplayName;
    }

    public List<Partner> getPartners() {
        return partners;
    }

    public void setPartners(List<Partner> partners) {
        this.partners = partners == null ? new ArrayList<>() : partners;
    }

    /**
     * Lookup partnera po inbound tokenu (X-Api-Key koji partner salje nama).
     *
     * @param inboundToken sirovi token iz {@code X-Api-Key} header-a; moze biti
     *                     {@code null} ili prazan
     * @return Optional sa partner-om ako token tacno matchuje neki od
     *         konfigurisanih, prazan Optional inace
     */
    public Optional<Partner> findByInboundToken(String inboundToken) {
        if (inboundToken == null || inboundToken.isEmpty()) {
            return Optional.empty();
        }
        // Tim 2 IMPORTANT-4: constant-time compare da bi se izbegao timing-based
        // byte-by-byte token guessing napad. {@code Objects.equals} bi short-circuit-ovao
        // na prvom razlicitom bajtu, eksponujuci duzinu prefiksa do tacke razlikovanja
        // kroz response-time razliku.
        byte[] candidate = inboundToken.getBytes(StandardCharsets.UTF_8);
        return partners.stream()
                .filter(p -> {
                    String stored = p.getInboundToken();
                    if (stored == null) {
                        return false;
                    }
                    byte[] storedBytes = stored.getBytes(StandardCharsets.UTF_8);
                    return MessageDigest.isEqual(storedBytes, candidate);
                })
                .findFirst();
    }

    /**
     * Lookup partnera po njegovom routing broju.
     *
     * @param routingNumber routing number trazenog partnera
     * @return Optional sa partner-om ako postoji, prazan inace
     */
    public Optional<Partner> findByRoutingNumber(int routingNumber) {
        return partners.stream()
                .filter(p -> p.getRoutingNumber() == routingNumber)
                .findFirst();
    }

    /**
     * Strict varijanta {@link #findByRoutingNumber} koja baca exception ako
     * partner nije konfigurisan. Koristi se u OUTBOUND klijentima gde missing
     * partner indikira programmer error / misconfig (nije runtime user error).
     *
     * @param routingNumber routing number trazenog partnera
     * @return Partner objekat
     * @throws IllegalArgumentException ako partner nije konfigurisan
     */
    public Partner partnerOrThrow(int routingNumber) {
        return findByRoutingNumber(routingNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nepoznat partner routingNumber=" + routingNumber
                                + "; provera interbank.partners[*].routing-number"));
    }

    /**
     * Konfiguracija jednog partnera. Polja moraju biti mutable za Spring Boot
     * @ConfigurationProperties bindovanje (setter-i).
     */
    public static class Partner {

        private int routingNumber;
        private String displayName;
        private String baseUrl;
        private String inboundToken;
        private String outboundToken;

        public int getRoutingNumber() {
            return routingNumber;
        }

        public void setRoutingNumber(int routingNumber) {
            this.routingNumber = routingNumber;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getInboundToken() {
            return inboundToken;
        }

        public void setInboundToken(String inboundToken) {
            this.inboundToken = inboundToken;
        }

        public String getOutboundToken() {
            return outboundToken;
        }

        public void setOutboundToken(String outboundToken) {
            this.outboundToken = outboundToken;
        }
    }
}
