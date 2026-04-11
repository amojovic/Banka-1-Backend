package app.service;

import app.entities.FcmToken;
import app.repository.FcmTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FcmTokenService}.
 *
 * <p>These tests cover the upsert, lookup, and delete lifecycle of FCM device
 * tokens without touching a real database. The goal is to protect the narrow
 * contract relied on by {@link NotificationDeliveryService} when mirroring
 * verification OTP events to mobile pushes.
 */
@ExtendWith(MockitoExtension.class)
class FcmTokenServiceUnitTest {

    private static final Long CLIENT_ID = 42L;
    private static final String NEW_TOKEN = "fcm-token-new";
    private static final String OLD_TOKEN = "fcm-token-old";

    @Mock
    private FcmTokenRepository fcmTokenRepository;

    @InjectMocks
    private FcmTokenService fcmTokenService;

    /**
     * Verifies that calling upsert for a client that has no existing row inserts
     * a fresh {@link FcmToken} with the supplied token and a populated
     * {@code updatedAt} timestamp.
     *
     * <p>This protects the first-login path on the mobile app.
     */
    @Test
    void upsertTokenInsertsNewRowWhenClientHasNoExistingRegistration() {
        when(fcmTokenRepository.findByClientId(CLIENT_ID)).thenReturn(Optional.empty());

        fcmTokenService.upsertToken(CLIENT_ID, NEW_TOKEN);

        ArgumentCaptor<FcmToken> captor = ArgumentCaptor.forClass(FcmToken.class);
        verify(fcmTokenRepository).save(captor.capture());
        FcmToken saved = captor.getValue();

        assertEquals(CLIENT_ID, saved.getClientId());
        assertEquals(NEW_TOKEN, saved.getFcmToken());
        assertNotNull(saved.getUpdatedAt());
    }

    /**
     * Verifies that calling upsert for a client that already has a registration
     * overwrites the token on the existing row rather than inserting a second
     * one.
     *
     * <p>This protects the token-refresh path the mobile app hits on every
     * login and whenever the Firebase SDK rotates the device token.
     */
    @Test
    void upsertTokenOverwritesExistingRowInPlace() {
        FcmToken existing = FcmToken.builder()
                .id(1L)
                .clientId(CLIENT_ID)
                .fcmToken(OLD_TOKEN)
                .updatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(fcmTokenRepository.findByClientId(CLIENT_ID)).thenReturn(Optional.of(existing));

        fcmTokenService.upsertToken(CLIENT_ID, NEW_TOKEN);

        ArgumentCaptor<FcmToken> captor = ArgumentCaptor.forClass(FcmToken.class);
        verify(fcmTokenRepository).save(captor.capture());
        FcmToken saved = captor.getValue();

        assertEquals(1L, saved.getId());
        assertEquals(NEW_TOKEN, saved.getFcmToken());
        assertTrue(saved.getUpdatedAt().isAfter(java.time.Instant.parse("2026-01-01T00:00:00Z")));
    }

    /**
     * Verifies that findToken returns the stored token when a row exists.
     *
     * <p>This is the lookup path used by {@link NotificationDeliveryService}
     * before dispatching an FCM push.
     */
    @Test
    void findTokenReturnsStoredTokenWhenPresent() {
        FcmToken entity = FcmToken.builder()
                .clientId(CLIENT_ID)
                .fcmToken(NEW_TOKEN)
                .updatedAt(java.time.Instant.now())
                .build();
        when(fcmTokenRepository.findByClientId(CLIENT_ID)).thenReturn(Optional.of(entity));

        Optional<String> result = fcmTokenService.findToken(CLIENT_ID);

        assertTrue(result.isPresent());
        assertEquals(NEW_TOKEN, result.get());
    }

    /**
     * Verifies that findToken returns an empty {@link Optional} when the
     * client has no registered device.
     *
     * <p>This is the short-circuit signal that tells the delivery pipeline to
     * skip the FCM branch without treating it as an error.
     */
    @Test
    void findTokenReturnsEmptyWhenNoRegistrationExists() {
        when(fcmTokenRepository.findByClientId(CLIENT_ID)).thenReturn(Optional.empty());

        Optional<String> result = fcmTokenService.findToken(CLIENT_ID);

        assertFalse(result.isPresent());
        verify(fcmTokenRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /**
     * Verifies that deleteToken delegates directly to the repository.
     *
     * <p>This protects the logout flow on the mobile app.
     */
    @Test
    void deleteTokenDelegatesToRepository() {
        fcmTokenService.deleteToken(CLIENT_ID);

        verify(fcmTokenRepository).deleteByClientId(CLIENT_ID);
    }
}