package app.service;

import app.dto.EmailTemplate;
import app.dto.NotificationRequest;
import app.dto.ResolvedEmail;

import app.exception.BusinessException;
import app.exception.ErrorCode;
import app.template.NotificationTemplateFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationContentResolver}.
 *
 * <p>NotificationContentResolver is package-private; this test lives in the same package.
 * The class validates request preconditions, template-variable fallback behavior, and HTML
 * escaping rules so rendered email content stays both correct and safe.
 */
@ExtendWith(MockitoExtension.class)
class NotificationContentResolverUnitTest {

    @Mock
    private NotificationTemplateFactory templateFactory;

    @BeforeEach
    void setUp() {
        lenient().when(templateFactory.resolve(any())).thenReturn(
                new EmailTemplate("Subject", "Hello {{name}}")
        );
    }

    // --- Validation: request and email ---

    /**
     * Verifies that resolving content fails when the request itself is missing.
     *
     * <p>This protects the resolver from operating on invalid input before any template work
     * begins.
     */
    @Test
    void resolveThrowsWhenRequestIsNull() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> NotificationContentResolver.resolve(null, "EMPLOYEE_CREATED", templateFactory));
        assertEquals(ErrorCode.NOTIFICATION_PAYLOAD_REQUIRED, exception.getErrorCode());
    }

    /**
     * Verifies that resolving content fails when the recipient email is null.
     *
     * <p>The resolver must reject messages that cannot be delivered to any recipient.
     */
    @Test
    void resolveThrowsWhenEmailIsNull() {
        NotificationRequest request = new NotificationRequest("Alice", null, Map.of());
        BusinessException exception = assertThrows(BusinessException.class,
                () -> NotificationContentResolver.resolve(request, "EMPLOYEE_CREATED", templateFactory));
        assertEquals(ErrorCode.RECIPIENT_EMAIL_REQUIRED, exception.getErrorCode());
    }

    /**
     * Verifies that resolving content fails when the recipient email is blank.
     *
     * <p>This complements the null-email validation path and enforces the same delivery
     * precondition for whitespace-only values.
     */
    @Test
    void resolveThrowsWhenEmailIsBlank() {
        NotificationRequest request = new NotificationRequest("Alice", "   ", Map.of());
        BusinessException exception = assertThrows(BusinessException.class,
                () -> NotificationContentResolver.resolve(request, "EMPLOYEE_CREATED", templateFactory));
        assertEquals(ErrorCode.RECIPIENT_EMAIL_REQUIRED, exception.getErrorCode());
    }



    /**
     * Verifies that resolving content fails when no notification type is provided.
     *
     * <p>The resolver needs the type in order to choose the correct subject/body template.
     */
    @Test
    void resolveThrowsWhenNotificationTypeIsNull() {
        NotificationRequest request = new NotificationRequest("Alice", "alice@example.com", Map.of());
        BusinessException exception = assertThrows(BusinessException.class,
                () -> NotificationContentResolver.resolve(request, null, templateFactory));
        assertEquals(ErrorCode.NOTIFICATION_TYPE_REQUIRED, exception.getErrorCode());
    }

    // --- Happy path rendering ---

    /**
     * Verifies that explicit template variables are rendered into the body.
     *
     * <p>This is the primary success-path behavior for template substitution.
     */
    @Test
    void resolveRendersTemplateVariablesIntoBody() {
        NotificationRequest request = new NotificationRequest(
                "Alice", "alice@example.com", Map.of("name", "Alice")
        );
        ResolvedEmail resolved = NotificationContentResolver.resolve(
                request, "EMPLOYEE_CREATED", templateFactory
        );

        assertEquals("alice@example.com", resolved.recipientEmail());
        assertEquals("Subject", resolved.subject());
        assertEquals("Hello Alice", resolved.body());
    }

    /**
     * Verifies that {@code username} is reused as the {@code name} placeholder when no explicit
     * name variable exists.
     *
     * <p>This fallback keeps templates usable even when publishers provide only the username
     * field in the payload.
     */
    @Test
    void resolveUsesUsernameAsNameFallbackWhenNameNotInTemplateVariables() {
        NotificationRequest request = new NotificationRequest("Bob", "bob@example.com", new HashMap<>());
        ResolvedEmail resolved = NotificationContentResolver.resolve(
                request, "EMPLOYEE_CREATED", templateFactory
        );

        assertEquals("Hello Bob", resolved.body());
    }

    /**
     * Verifies that an explicitly provided {@code name} variable wins over the username fallback.
     *
     * <p>This protects callers that intentionally want a different display name in the final
     * rendered content.
     */
    @Test
    void resolveDoesNotOverrideExplicitNameVariableWithUsername() {
        NotificationRequest request = new NotificationRequest(
                "Bob", "bob@example.com", Map.of("name", "Robert")
        );
        ResolvedEmail resolved = NotificationContentResolver.resolve(
                request, "EMPLOYEE_CREATED", templateFactory
        );

        assertEquals("Hello Robert", resolved.body());
    }

    /**
     * Verifies that null template variables are handled as an empty map.
     *
     * <p>This avoids null-pointer behavior while still allowing username fallback to work.
     */
    @Test
    void resolveHandlesNullTemplateVariablesInRequest() {
        NotificationRequest request = new NotificationRequest("Alice", "alice@example.com", null);
        ResolvedEmail resolved = NotificationContentResolver.resolve(
                request, "EMPLOYEE_CREATED", templateFactory
        );

        assertEquals("Hello Alice", resolved.body());
    }

    /**
     * Verifies that a whitespace-only username does not replace the template placeholder.
     *
     * <p>This protects template output from being filled with meaningless values when username
     * data is present but effectively empty.
     */
    @Test
    void resolveWithWhitespaceOnlyUsernameAndNoNameVariableLeavesPlaceholderUnreplaced() {
        NotificationRequest request = new NotificationRequest("   ", "alice@example.com", new HashMap<>());
        ResolvedEmail resolved = NotificationContentResolver.resolve(
                request, "EMPLOYEE_CREATED", templateFactory
        );

        assertEquals("Hello {{name}}", resolved.body());
    }

    // --- HTML escaping ---

    /**
     * Verifies that script tags are escaped before insertion into the rendered body.
     *
     * <p>This protects HTML-capable email clients from script injection via template variables.
     */
    @Test
    void resolveEscapesScriptTagInTemplateVariable() {
        NotificationRequest request = new NotificationRequest(
                "Alice",
                "alice@example.com",
                Map.of("name", "<script>alert('xss')</script>")
        );
        ResolvedEmail resolved = NotificationContentResolver.resolve(
                request, "EMPLOYEE_CREATED", templateFactory
        );

        assertFalse(resolved.body().contains("<script>"));
        assertTrue(resolved.body().contains("&lt;script&gt;"));
    }

    /**
     * Verifies that ampersands are escaped in template variables.
     *
     * <p>This ensures special HTML characters are encoded consistently in rendered content.
     */
    @Test
    void resolveEscapesAmpersandInTemplateVariable() {
        NotificationRequest request = new NotificationRequest(
                "AT&T", "user@example.com", new HashMap<>()
        );
        ResolvedEmail resolved = NotificationContentResolver.resolve(
                request, "EMPLOYEE_CREATED", templateFactory
        );

        assertTrue(resolved.body().contains("AT&amp;T"));
        assertFalse(resolved.body().contains("AT&T"));
    }

    /**
     * Verifies that double quotes are escaped in template variables.
     *
     * <p>This protects HTML attributes and general content rendering from quote injection.
     */
    @Test
    void resolveEscapesDoubleQuotesInTemplateVariable() {
        NotificationRequest request = new NotificationRequest(
                "Alice", "alice@example.com", Map.of("name", "\"Alice\"")
        );
        ResolvedEmail resolved = NotificationContentResolver.resolve(
                request, "EMPLOYEE_CREATED", templateFactory
        );

        assertTrue(resolved.body().contains("&quot;Alice&quot;"));
        assertFalse(resolved.body().contains("\"Alice\""));
    }

    /**
     * Verifies that single quotes are escaped in template variables.
     *
     * <p>This complements the escaping rules for the rest of the high-risk HTML characters.
     */
    @Test
    void resolveEscapesSingleQuoteInTemplateVariable() {
        NotificationRequest request = new NotificationRequest(
                "Alice", "alice@example.com", Map.of("name", "O'Brien")
        );
        ResolvedEmail resolved = NotificationContentResolver.resolve(
                request, "EMPLOYEE_CREATED", templateFactory
        );

        assertTrue(resolved.body().contains("O&#39;Brien"));
        assertFalse(resolved.body().contains("O'Brien"));
    }

    /**
     * Verifies that the greater-than character is escaped in template variables.
     *
     * <p>This is part of the output-sanitization contract for rendered notification content.
     */
    @Test
    void resolveEscapesGreaterThanInTemplateVariable() {
        NotificationRequest request = new NotificationRequest(
                "Alice", "alice@example.com", Map.of("name", "a>b")
        );
        ResolvedEmail resolved = NotificationContentResolver.resolve(
                request, "EMPLOYEE_CREATED", templateFactory
        );

        assertTrue(resolved.body().contains("a&gt;b"));
    }
}
