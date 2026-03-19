package app.template;

import app.dto.EmailTemplate;
import app.exception.BusinessException;
import app.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DefaultNotificationTemplateFactory}.
 *
 * <p>This class verifies the map-backed variant of the template factory. The tests check that
 * each supported employee notification type resolves to the expected template and that unknown
 * types fail explicitly.
 */
class DefaultNotificationTemplateFactoryUnitTest {

    private DefaultNotificationTemplateFactory factory;

    @BeforeEach
    void setUp() {
        Map<String, EmailTemplate> templates = Map.of(
                "EMPLOYEE_CREATED", new EmailTemplate(
                        "Activation Email",
                        "Zdravo {{name}}, vas nalog je kreiran. Aktivirajte nalog klikom na link:\n{{activationLink}}"
                ),
                "EMPLOYEE_PASSWORD_RESET", new EmailTemplate(
                        "Password Reset Email",
                        "Zdravo {{name}}, resetujte lozinku klikom na link:\n{{resetLink}}"
                ),
                "EMPLOYEE_ACCOUNT_DEACTIVATED", new EmailTemplate(
                        "Account Deactivation Email",
                        "Zdravo {{name}}, vas nalog je deaktiviran."
                )
        );

        factory = new DefaultNotificationTemplateFactory(templates);
    }

    /**
     * Verifies that the activation template is returned for employee creation events.
     *
     * <p>This protects the subject/body mapping for the onboarding notification type.
     */
    @Test
    void resolveEmployeeCreatedReturnsActivationTemplate() {
        EmailTemplate template = factory.resolve("EMPLOYEE_CREATED");

        assertEquals("Activation Email", template.subject());
        assertTrue(template.bodyTemplate().contains("{{activationLink}}"));
        assertTrue(template.bodyTemplate().contains("{{name}}"));
    }

    /**
     * Verifies that the password-reset template is returned for employee reset events.
     *
     * <p>This ensures the reset-link placeholder and subject stay tied to the correct
     * notification type.
     */
    @Test
    void resolveEmployeePasswordResetReturnsResetTemplate() {
        EmailTemplate template = factory.resolve("EMPLOYEE_PASSWORD_RESET");

        assertEquals("Password Reset Email", template.subject());
        assertTrue(template.bodyTemplate().contains("{{resetLink}}"));
        assertTrue(template.bodyTemplate().contains("{{name}}"));
    }

    /**
     * Verifies that the deactivation template is returned for account-deactivation events.
     *
     * <p>This protects the final supported employee notification mapping in the template
     * factory.
     */
    @Test
    void resolveEmployeeAccountDeactivatedReturnsDeactivationTemplate() {
        EmailTemplate template = factory.resolve("EMPLOYEE_ACCOUNT_DEACTIVATED");

        assertEquals("Account Deactivation Email", template.subject());
        assertTrue(template.bodyTemplate().contains("{{name}}"));
    }

    /**
     * Verifies that requesting an unsupported notification type fails explicitly.
     *
     * <p>This prevents the factory from masking missing configuration for unexpected event
     * types.
     */
    @Test
    void resolveUnknownNotificationTypeThrows() {
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> factory.resolve("UNKNOWN")
        );
        assertEquals(ErrorCode.EMAIL_CONTENT_RESOLUTION_FAILED, ex.getErrorCode());
        assertTrue(ex.getDetails().contains("UNKNOWN"));
    }

    /**
     * Verifies that supported notification types do not accidentally share the same subject.
     *
     * <p>This is a compact regression check that the configured templates remain distinct per
     * event type.
     */
    @Test
    void allSupportedTypesReturnDistinctSubjects() {
        EmailTemplate created = factory.resolve("EMPLOYEE_CREATED");
        EmailTemplate reset = factory.resolve("EMPLOYEE_PASSWORD_RESET");
        EmailTemplate deactivated = factory.resolve("EMPLOYEE_ACCOUNT_DEACTIVATED");

        assertNotEquals(created.subject(), reset.subject());
        assertNotEquals(created.subject(), deactivated.subject());
        assertNotEquals(reset.subject(), deactivated.subject());
    }
}
