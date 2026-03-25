package com.banka1.verificationService.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * HMAC-based hashing service for short-lived OTP codes.
 */
@Service
public class OtpHashingService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec secretKey;

    public OtpHashingService(@Value("${jwt.secret}") String secret) {
        this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String hash(String rawCode) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(secretKey);
            return Base64.getEncoder().encodeToString(mac.doFinal(rawCode.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to hash OTP code.", ex);
        }
    }

    public boolean matches(String rawCode, String expectedHash) {
        return hash(rawCode).equals(expectedHash);
    }
}
