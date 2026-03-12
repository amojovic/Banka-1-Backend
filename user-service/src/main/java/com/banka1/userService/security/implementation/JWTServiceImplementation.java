package com.banka1.userService.security.implementation;

import com.banka1.userService.domain.Zaposlen;
import com.banka1.userService.domain.enums.Permission;
import com.banka1.userService.security.JWTService;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Implementacija {@link JWTService} koja koristi Nimbus JOSE biblioteku za potpisivanje
 * JWT tokena algoritmom HS256 i {@link SecureRandom} za generisanje jednokratnih tokena.
 */
@Service
@Getter
public class JWTServiceImplementation implements JWTService {

    /** Signer koji potpisuje JWT tokene HMAC-SHA256 algoritmom. */
    private final JWSSigner signer;

    /** Bezbedan izvor slucajnosti za generisanje jednokratnih tokena. */
    private final SecureRandom random = new SecureRandom();

    /** Bafer za generisanje nasumicnih bajtova (32 bajta = 256 bita entropije). */
    private final byte[] bytes = new byte[32];

    /** Naziv claim-a u JWT-u koji nosi ime uloge korisnika. */
    @Value("${banka.security.roles-claim}")
    private String role;

    /** Naziv claim-a u JWT-u koji nosi listu permisija korisnika. */
    @Value("${banka.security.permissions-claim}")
    private String permission;

    /** Naziv claim-a u JWT-u koji nosi identifikator korisnika. */
    @Value("${banka.security.id}")
    private String id;

    /** Issuer vrednost koja se upisuje u JWT token. */
    @Value("${banka.security.issuer}")
    private String issuer;

    /** Vreme trajanja JWT tokena u milisekundama. */
    @Value("${banka.security.expiration-time}")
    private Long expirationTime;

    /**
     * Inicijalizuje servis za potpisivanje JWT tokena ucitavanjem HMAC tajne.
     *
     * @param secret HMAC tajna za potpisivanje tokena
     * @throws KeyLengthException ako je tajna neodgovarajuce duzine za HS256
     */
    public JWTServiceImplementation(@Value("${jwt.secret}") String secret) throws KeyLengthException {
        this.signer = new MACSigner(secret);
    }

    /**
     * Generise JWT pristupni token za zadatog zaposlenog.
     * Token sadrzi email (subject), identifikator, ulogu, permisije i vreme isteka.
     *
     * @param zaposlen zaposleni za kog se token izdaje
     * @return serijalizovan potpisani JWT token
     */
    @Override
    public String generateJwtToken(Zaposlen zaposlen) {
        List<String> permissions = new ArrayList<>();
        for (Permission x : zaposlen.getPermissionSet()) {
            permissions.add(x.name());
        }

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(zaposlen.getEmail())
                .issuer(issuer)
                .claim(id, zaposlen.getId())
                .claim(role, zaposlen.getRole().name())
                .claim(permission, permissions)
                .expirationTime(new Date(System.currentTimeMillis() + expirationTime))
                .build();

        JWSHeader header = new JWSHeader(JWSAlgorithm.HS256);
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(signer);
        } catch (Exception e) {
            throw new IllegalStateException("Greska sa generisanjem JWT-a");
        }
        return jwt.serialize();
    }

    /**
     * Hesira prosledjenu vrednost koristeci SHA-256 algoritam.
     *
     * @param value vrednost koja se hesira
     * @return SHA-256 hash u hex formatu
     */
    @Override
    public String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 nije dostupan", e);
        }
    }

    /**
     * Pretvara niz bajtova u heksadecimalni string.
     *
     * @param bytes niz bajtova za konverziju
     * @return string u lowercase hex formatu
     */
    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Generise nasumican URL-safe Base64 token bez padding-a.
     * Koristi 32 nasumicna bajta (256 bita entropije) sto rezultuje tokenom duzine 43 znaka.
     *
     * @return URL-safe nasumicni token pogodan za slanje u linkovima
     */
    @Override
    public String generateRandomToken() {
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
