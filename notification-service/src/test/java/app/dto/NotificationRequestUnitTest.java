package app.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NotificationRequest}.
 *
 * <p>This class verifies the DTO constructor behavior that notification-service relies on when
 * incoming RabbitMQ payloads are converted into request objects. The tests focus on correct
 * initialization and defensive copying of template variables.
 */
class NotificationRequestUnitTest {

    /**
     * Verifies that the constructor keeps an empty, non-null template-variable map
     * when the caller provides {@code null}.
     *
     * <p>This protects downstream code from null checks and ensures template rendering can safely
     * operate on the request even when no dynamic variables were provided.
     */
    @Test
    void constructorKeepsDefaultEmptyTemplateVariablesWhenNullIsProvided() {
        NotificationRequest request = new NotificationRequest("Dimitrije", "dimitrije@example.com", null);

        assertEquals("Dimitrije", request.getUsername());
        assertEquals("dimitrije@example.com", request.getUserEmail());
        assertNotNull(request.getTemplateVariables());
        assertTrue(request.getTemplateVariables().isEmpty());
    }

    /**
     * Verifies that constructor input variables are copied into the request object.
     *
     * <p>This test matters because notification rendering depends on these values being present
     * exactly as they were supplied by the publisher.
     */
    @Test
    void constructorCopiesProvidedTemplateVariables() {
        NotificationRequest request = new NotificationRequest(
                "Dimitrije",
                "dimitrije@example.com",
                Map.of("activationLink", "https://example.com/activate/123")
        );

        assertEquals("https://example.com/activate/123", request.getTemplateVariables().get("activationLink"));
    }
}
