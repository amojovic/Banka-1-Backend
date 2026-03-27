package com.company.observability.starter.web.filter;

import com.company.observability.starter.domain.RequestLogContext;
import com.company.observability.starter.service.RequestLoggingService;
import com.company.observability.starter.service.SensitiveDataMaskingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter za logovanje osnovnih informacija o HTTP zahtevima.
 * <p>
 * Filter meri trajanje obrade zahteva, maskira osetljive podatke iz query string-a
 * i Authorization zaglavlja i prosledjuje pripremljen kontekst servisu za logovanje.
 */
public class HttpRequestLoggingFilter extends OncePerRequestFilter {
    private final RequestLoggingService requestLoggingService;
    private final SensitiveDataMaskingService maskingService;

    /**
     * Kreira filter za logovanje HTTP zahteva.
     *
     * @param requestLoggingService servis koji loguje podatke o HTTP zahtevu
     * @param maskingService servis za maskiranje osetljivih podataka pre logovanja
     */
    public HttpRequestLoggingFilter(RequestLoggingService requestLoggingService, SensitiveDataMaskingService maskingService) {
        this.requestLoggingService = requestLoggingService;
        this.maskingService = maskingService;
    }

    /**
     * Obradjuje HTTP zahtev, meri trajanje njegove obrade i loguje osnovne podatke
     * po zavrsetku filter chain-a.
     *
     * @param request dolazni HTTP zahtev
     * @param response odlazni HTTP odgovor
     * @param filterChain lanac filtera kroz koji zahtev nastavlja obradu
     * @throws ServletException ako dodje do servlet greske tokom obrade
     * @throws IOException ako dodje do I/O greske tokom obrade
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        long startTime = System.nanoTime();
        int status = HttpServletResponse.SC_OK;
        try {
            filterChain.doFilter(request, response);
            status = response.getStatus();
        } catch (ServletException | IOException  | RuntimeException e) {
            status = response.getStatus() >= 400 ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            throw e;

        } finally {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            RequestLogContext logContext = new RequestLogContext(request.getMethod(), request.getRequestURI(), status, durationMs, maskingService.maskQuery(request.getQueryString()), maskingService.maskAuthorizationHeader(request.getHeader("Authorization")));
            requestLoggingService.logRequest(logContext);
        }
    }
}
