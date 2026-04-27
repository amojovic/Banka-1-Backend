package com.company.observability.starter.web;

import com.company.observability.starter.web.filter.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;

/**
 * Mapira standardne Spring MVC izuzetke na odgovarajuce HTTP statuse za sve servise.
 * <p>
 * Ovaj handler ima najvisi prioritet ({@link Ordered#HIGHEST_PRECEDENCE}) kako bi
 * uskocio pre {@code Exception.class} catch-all handler-a u servisno-specificnim
 * advice klasama, koji bi inace pretvarao tipicne klijentske greske u 500 odgovor.
 * Generisi greske se vracaju u istom {@link ErrorResponse} formatu kao i ostali
 * odgovori starter-a, sa correlation ID-om iz MDC-a.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StandardSpringMvcExceptionHandler {

    /**
     * Obradjuje zahteve ka URI putanjama koje nijedan kontroler ne pokriva.
     *
     * @param e izuzetak koji baca Spring MVC kada ne nadje odgovarajuci handler
     * @param request HTTP zahtev u kome je greska nastala
     * @return HTTP 404 odgovor sa standardizovanim telom greske
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    /**
     * Obradjuje odbijanje pristupa metodama zasticenim {@code @PreAuthorize} izrazima.
     * <p>
     * {@link AuthorizationDeniedException} se baca u Spring Security 6+ kada metod-level
     * provera autorizacije ne prodje, te se mapira na 403 umesto na genericku 500.
     *
     * @param e izuzetak odbijenog pristupa
     * @param request HTTP zahtev u kome je greska nastala
     * @return HTTP 403 odgovor sa standardizovanim telom greske
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<Object> handleAuthorizationDenied(AuthorizationDeniedException e, HttpServletRequest request) {
        return buildResponse(HttpStatus.FORBIDDEN, "Access denied", request);
    }

    /**
     * Obradjuje neispravno serijalizovana tela zahteva (npr. nepoznata enum vrednost ili neparsabilan JSON).
     *
     * @param e izuzetak koji baca Spring kada ne uspe da deserijalizuje telo zahteva
     * @param request HTTP zahtev u kome je greska nastala
     * @return HTTP 400 odgovor sa standardizovanim telom greske
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Malformed request body", request);
    }

    /**
     * Obradjuje zahteve kojima nedostaje obavezan {@code @RequestParam} parametar.
     *
     * @param e izuzetak nastao kada zahtev nema obavezan query parametar
     * @param request HTTP zahtev u kome je greska nastala
     * @return HTTP 400 odgovor sa standardizovanim telom greske
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> handleMissingRequestParameter(MissingServletRequestParameterException e, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Missing required request parameter: " + e.getParameterName(), request);
    }

    private ResponseEntity<Object> buildResponse(HttpStatus status, String message, HttpServletRequest request) {
        ErrorResponse response = new ErrorResponse(
                OffsetDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                MDC.get(CorrelationIdFilter.MDC_KEY),
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(response);
    }
}