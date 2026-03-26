package com.banka1.verificationService.service;

import com.banka1.verificationService.dto.request.GenerateRequest;
import com.banka1.verificationService.dto.request.ValidateRequest;
import com.banka1.verificationService.dto.response.GenerateResponse;
import com.banka1.verificationService.dto.response.StatusResponse;
import com.banka1.verificationService.dto.response.ValidateResponse;
import com.banka1.verificationService.exception.BusinessException;
import com.banka1.verificationService.exception.ErrorCode;
import com.banka1.verificationService.model.entity.VerificationSession;
import com.banka1.verificationService.model.enums.VerificationStatus;
import com.banka1.verificationService.repository.VerificationSessionRepository;
import com.company.observability.starter.domain.UserIdExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Servis za upravljanje sesijama verifikacije.
 */
@Service
@RequiredArgsConstructor
public class VerificationService {

    private final VerificationSessionRepository repository;
    private final OtpHashingService otpHashingService;
    private final RabbitTemplate rabbitTemplate;
    private final UserIdExtractor userIdExtractor;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.routing-key}")
    private String routingKey;

    @Value("${rabbitmq.routing-key.verification}")
    private String verificationRoutingKey;

    @Transactional
    public GenerateResponse generate(GenerateRequest request) {
        JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        Object idClaim = auth.getToken().getClaims().get("id");
        if (idClaim == null || !String.valueOf(idClaim).equals(String.valueOf(request.getClientId()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Cannot generate verification for other client");
        }

        List<VerificationSession> existingSessions = repository.findByClientIdAndOperationTypeAndRelatedEntityIdAndStatus(
                request.getClientId(), request.getOperationType(), request.getRelatedEntityId(), VerificationStatus.PENDING);
        for (VerificationSession existing : existingSessions) {
            existing.setStatus(VerificationStatus.CANCELLED);
            repository.save(existing);
        }

        String rawCode = generateCode();
        String hashedCode = otpHashingService.hash(rawCode);
        LocalDateTime now = LocalDateTime.now();

        VerificationSession session = VerificationSession.builder()
                .clientId(request.getClientId())
                .code(hashedCode)
                .operationType(request.getOperationType())
                .relatedEntityId(request.getRelatedEntityId())
                .createdAt(now)
                .expiresAt(now.plusMinutes(5))
                .attemptCount(0)
                .status(VerificationStatus.PENDING)
                .build();

        try {
            session = repository.saveAndFlush(session);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(
                    ErrorCode.VERIFICATION_SESSION_ALREADY_PENDING,
                    "Client ID: %s, operationType: %s, relatedEntityId: %s"
                            .formatted(request.getClientId(), request.getOperationType(), request.getRelatedEntityId())
            );
        }

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishGeneratedEvent(request, rawCode);
                }
            });
        } else {
            publishGeneratedEvent(request, rawCode);
        }

        return new GenerateResponse(session.getId());
    }

    @Transactional
    public ValidateResponse validate(ValidateRequest request) {
        VerificationSession session = repository.findById(request.getSessionId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VERIFICATION_SESSION_NOT_FOUND,
                        "Session ID: " + request.getSessionId()
                ));

        if (session.getStatus() == VerificationStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.VERIFICATION_SESSION_CANCELLED, "Session ID: " + request.getSessionId());
        }
        if (session.getStatus() == VerificationStatus.VERIFIED) {
            throw new BusinessException(ErrorCode.VERIFICATION_SESSION_ALREADY_VERIFIED, "Session ID: " + request.getSessionId());
        }
        if (session.getStatus() == VerificationStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_EXPIRED, "Session ID: " + request.getSessionId());
        }

        if (LocalDateTime.now().isAfter(session.getExpiresAt())) {
            session.setStatus(VerificationStatus.EXPIRED);
            repository.save(session);
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_EXPIRED, "Session ID: " + request.getSessionId());
        }

        boolean matches = otpHashingService.matches(request.getCode(), session.getCode());
        if (matches) {
            session.setStatus(VerificationStatus.VERIFIED);
            repository.save(session);
            return new ValidateResponse(true, session.getStatus(), 0);
        }

        session.setAttemptCount(session.getAttemptCount() + 1);
        if (session.getAttemptCount() >= 3) {
            session.setStatus(VerificationStatus.CANCELLED);
        }
        repository.save(session);

        return new ValidateResponse(false, session.getStatus(), 3 - session.getAttemptCount());
    }

    @Transactional
    public StatusResponse getStatus(Long sessionId) {
        VerificationSession session = repository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_SESSION_NOT_FOUND, "Session ID: " + sessionId));

        if (session.getStatus() == VerificationStatus.PENDING
                && LocalDateTime.now().isAfter(session.getExpiresAt())) {
            session.setStatus(VerificationStatus.EXPIRED);
            repository.save(session);
        }

        return new StatusResponse(sessionId, session.getStatus());
    }

    private void publishGeneratedEvent(GenerateRequest request, String rawCode) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userEmail", request.getClientEmail());
        Map<String, String> templateVariables = new HashMap<>();
        templateVariables.put("code", rawCode);
        payload.put("templateVariables", templateVariables);
        rabbitTemplate.convertAndSend(exchange, verificationRoutingKey, payload);
    }

    private String generateCode() {
        int code = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return String.valueOf(code);
    }
}
