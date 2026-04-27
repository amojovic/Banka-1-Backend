package com.banka1.stock_service.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

/**
 * Preserves explicit HTTP statuses and messages raised inside stock-service.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StockServiceResponseStatusExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<StockServiceErrorResponse> handleResponseStatusException(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        HttpStatusCode statusCode = exception.getStatusCode();
        String error = statusCode instanceof HttpStatus httpStatus
                ? httpStatus.getReasonPhrase()
                : statusCode.toString();
        String message = exception.getReason() == null || exception.getReason().isBlank()
                ? error
                : exception.getReason();

        StockServiceErrorResponse response = new StockServiceErrorResponse(
                OffsetDateTime.now(),
                statusCode.value(),
                error,
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(statusCode).body(response);
    }
}

record StockServiceErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
