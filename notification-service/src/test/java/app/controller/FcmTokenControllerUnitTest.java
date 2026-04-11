package app.controller;

import app.dto.FcmTestRequest;
import app.dto.FcmTokenRequest;
import app.service.FcmPushService;
import app.service.FcmTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FcmTokenController}.
 *
 * <p>These tests use plain Mockito against the controller rather than the Spring MockMvc
 * stack because notification-service has no security filter chain and the controller
 * does nothing but parameter pass-through. The goal is to protect the narrow delegation
 * contracts: register and deregister must forward straight to
 * {@link FcmTokenService}, and the test-push endpoint must guard against missing token
 * registrations before invoking {@link FcmPushService}.
 */
@ExtendWith(MockitoExtension.class)
class FcmTokenControllerUnitTest {

    private static final Long CLIENT_ID = 1L;
    private static final String FCM_TOKEN = "fcm-token-value";

    @Mock
    private FcmTokenService fcmTokenService;

    @Mock
    private FcmPushService fcmPushService;

    @InjectMocks
    private FcmTokenController fcmTokenController;

    /**
     * Verifies that the register endpoint upserts the supplied token on the service and
     * returns a 200 response.
     *
     * <p>This protects the mobile-login flow that calls {@code PUT /notifications/fcm/token}
     * on every successful authentication.
     */
    @Test
    void registerTokenUpsertsAndReturnsOk() {
        FcmTokenRequest request = new FcmTokenRequest();
        request.setClientId(CLIENT_ID);
        request.setFcmToken(FCM_TOKEN);

        ResponseEntity<Void> response = fcmTokenController.registerToken(request);

        verify(fcmTokenService).upsertToken(CLIENT_ID, FCM_TOKEN);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    /**
     * Verifies that the deregister endpoint deletes the token for the given client id
     * and returns a 200 response.
     *
     * <p>This protects the mobile-logout flow that stops the service from targeting a
     * device the user has signed out of.
     */
    @Test
    void deregisterTokenDeletesAndReturnsOk() {
        ResponseEntity<Void> response = fcmTokenController.deregisterToken(CLIENT_ID);

        verify(fcmTokenService).deleteToken(CLIENT_ID);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    /**
     * Verifies that the test-push endpoint looks up the registered token, sends the push
     * with the supplied payload fields, and returns a 200 confirmation.
     *
     * <p>This protects the manual-testing path used from Postman and Swagger while
     * iterating on mobile display code.
     */
    @Test
    void testPushSendsPushWhenTokenIsRegistered() {
        FcmTestRequest request = new FcmTestRequest();
        request.setClientId(CLIENT_ID);
        request.setCode("123456");
        request.setOperationType("PAYMENT");
        request.setSessionId("42");
        when(fcmTokenService.findToken(CLIENT_ID)).thenReturn(Optional.of(FCM_TOKEN));

        ResponseEntity<String> response = fcmTokenController.testPush(request);

        verify(fcmPushService).sendVerificationPush(FCM_TOKEN, "123456", "PAYMENT", "42");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains(String.valueOf(CLIENT_ID)));
    }

    /**
     * Verifies that the test-push endpoint returns a 400 Bad Request and does not invoke
     * the push service when no FCM token is registered for the given client.
     *
     * <p>This protects against silently losing test pushes that would otherwise look
     * successful from Postman even though nothing was sent.
     */
    @Test
    void testPushReturnsBadRequestWhenClientHasNoRegisteredToken() {
        FcmTestRequest request = new FcmTestRequest();
        request.setClientId(CLIENT_ID);
        request.setCode("123456");
        when(fcmTokenService.findToken(CLIENT_ID)).thenReturn(Optional.empty());

        ResponseEntity<String> response = fcmTokenController.testPush(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains(String.valueOf(CLIENT_ID)));
        verify(fcmPushService, never()).sendVerificationPush(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }
}