package com.banka1.tradingservice.funds.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generator za 16-cifrene racune fondova.
 */
@Component
public class FundAccountNumberGenerator {

    private final SecureRandom random = new SecureRandom();

    /** 15 random + 1 check-digit = 16 cifara. */
    public String generate() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 15; i++) sb.append(random.nextInt(10));
        sb.append(checkDigit(sb.toString()));
        return sb.toString();
    }

    private int checkDigit(String prefix) {
        long sum = 0;
        int weight = 2;
        for (int i = prefix.length() - 1; i >= 0; i--) {
            sum += (prefix.charAt(i) - '0') * weight;
            weight = (weight == 7) ? 2 : weight + 1;
        }
        int rem = (int) (sum % 11);
        int cd = 11 - rem;
        return cd >= 10 ? 0 : cd;
    }
}
