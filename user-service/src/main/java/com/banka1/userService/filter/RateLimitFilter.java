package com.banka1.userService.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP filter koji primenjuje ogranicenje broja zahteva (rate limiting) po IP adresi.
 * Stiti osetljive auth endpoint-e od brute-force napada dozvoljavajuci maksimalno
 * {@value MAX_REQUESTS} zahteva po IP adresi u vremenskom prozoru od jedne minute.
 * Filter se primenjuje samo na putanje definisane u {@link #RATE_LIMITED_PATHS}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** Skup putanja na koje se primenjuje rate limiting. */
    private static final Set<String> RATE_LIMITED_PATHS = Set.of(
            "/auth/login", "/auth/forgot-password", "/auth/refresh"
    );

    /** Maksimalni broj zahteva po IP adresi unutar vremenskog prozora. */
    private static final int MAX_REQUESTS = 10;

    /** Trajanje vremenskog prozora u milisekundama (1 minuta). */
    private static final long WINDOW_MS = 60_000L;

    /**
     * Mapa koja cuva vremenske markice zahteva po IP adresi.
     * Kljuc je IP adresa, vrednost je deque vremenskih markica unutar tekuceg prozora.
     */
    private final ConcurrentHashMap<String, Deque<Long>> requestMap = new ConcurrentHashMap<>();

    /**
     * Proverava rate limit za svaki dolazeci zahtev na zasticenim putanjama.
     * Zahtevi na putanjama koje nisu u {@link #RATE_LIMITED_PATHS} se propustaju bez provere.
     * Prekoracenje limita rezultuje HTTP statusom 429 (Too Many Requests).
     *
     * @param request dolazeci HTTP zahtev
     * @param response HTTP odgovor
     * @param filterChain lanac filtera
     * @throws ServletException ako dodje do greske u obradi filtera
     * @throws IOException ako dodje do I/O greske
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getServletPath();
        if (!RATE_LIMITED_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = getClientIp(request);
        long now = System.currentTimeMillis();

        Deque<Long> timestamps = requestMap.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_MS) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= MAX_REQUESTS) {
                response.setStatus(429);
                response.getWriter().write("Too many requests");
                return;
            }
            timestamps.addLast(now);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Odredjuje IP adresu klijenta uzimajuci u obzir proxy zaglavlje {@code X-Forwarded-For}.
     * Ako zaglavlje nije prisutno, koristi se direktna adresa veze.
     *
     * @param request HTTP zahtev iz kog se cita IP adresa
     * @return IP adresa klijenta
     */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
