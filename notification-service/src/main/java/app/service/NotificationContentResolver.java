package app.service;

import app.dto.EmailTemplate;
import app.dto.NotificationRequest;
import app.dto.ResolvedEmail;
import app.exception.BusinessException;
import app.exception.ErrorCode;
import app.template.NotificationTemplateFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves rendered email content from request payload and notification templates.
 */
final class NotificationContentResolver {
    /** Error message when notification type is missing. */
    private static final String NOTIFICATION_TYPE_REQUIRED =
            "notificationType is required";

    /** Error message when notification payload is missing. */
    private static final String NOTIFICATION_PAYLOAD_REQUIRED =
            "Notification payload is required";

    /** Error message when user email is missing. */
    private static final String USER_EMAIL_REQUIRED =
            "userEmail is required";


    /** Template variable key for username. */
    private static final String USERNAME_KEY = "username";

    /** Template variable key for name placeholder. */
    private static final String NAME_KEY = "name";

    /** Prefix used for template tokens. */
    private static final String TEMPLATE_TOKEN_PREFIX = "{{";

    /** Suffix used for template tokens. */
    private static final String TEMPLATE_TOKEN_SUFFIX = "}}";

    private NotificationContentResolver() {
    }

    /**
     * Resolves a notification payload into the final `email recipient` + `subject` + `body`,
     * enforcing that a deliverable email address is present.
     *
     * <p>This is the email-delivery path: it is the same as {@link #render} but additionally
     * requires {@code userEmail}. The in-app channel must NOT go through here — it carries no
     * email address and must not be suppressed by a missing one; use {@link #render} instead.
     *
     * @param request incoming notification payload
     * @param notificationType resolved notification type used for template selection
     * @param templateFactory template source for the notification type
     * @return fully rendered email content ready for delivery
     */
    static ResolvedEmail resolve(
            NotificationRequest request,
            String notificationType,
            NotificationTemplateFactory templateFactory
    ) {
        requireUserEmail(request);
        return render(request, notificationType, templateFactory);
    }

    /**
     * Renders a notification payload into {@code subject} + {@code body} without requiring an
     * email address.
     *
     * <p>The template content is rendered purely from {@code templateVariables} (and the
     * {@code username} aliases) — it never needs the recipient email. Only the email-delivery
     * leg needs {@code userEmail}; the in-app notification channel uses this method so a
     * missing/blank email can never suppress the in-app row.
     *
     * <p>The notification {@code type} is still validated here: an absent type is a hard error
     * because no template can be selected without it.
     *
     * @param request incoming notification payload
     * @param notificationType resolved notification type used for template selection
     * @param templateFactory template source for the notification type
     * @return rendered content; {@link ResolvedEmail#recipientEmail()} mirrors the payload email
     *         and may be {@code null}/blank when no email was supplied
     */
    static ResolvedEmail render(
            NotificationRequest request,
            String notificationType,
            NotificationTemplateFactory templateFactory
    ) {
        validateRequest(request);
        requireNotificationType(notificationType);

        EmailTemplate emailTemplate = templateFactory.resolve(notificationType);
        Map<String, String> variables = createVariables(request);
        String subject = renderTemplate(emailTemplate.subject(), variables);
        String body = renderTemplate(emailTemplate.bodyTemplate(), variables);
        return new ResolvedEmail(request.getUserEmail(), subject, body);
    }

    /**
     * Builds the template variable map used for placeholder substitution.
     *
     * @param request incoming notification payload
     * @return mutable map of template variables with username aliases applied
     */
    private static Map<String, String> createVariables(NotificationRequest request) {
        Map<String, String> variables = request.getTemplateVariables() == null
                ? new HashMap<>()
                : new HashMap<>(request.getTemplateVariables());
        addUsernameAliases(request.getUsername(), variables);
        return variables;
    }

    /**
     * Adds standard username aliases when a username is present in the payload.
     *
     * @param username source username from the payload
     * @param variables mutable template variables map to enrich
     */
    private static void addUsernameAliases(String username, Map<String, String> variables) {
        if (username == null || username.isBlank()) {
            return;
        }
        variables.putIfAbsent(USERNAME_KEY, username);
        variables.putIfAbsent(NAME_KEY, username);
    }

    /**
     * Replaces template tokens such as {@code {{name}}} with provided values.
     *
     * @param template template text containing placeholders
     * @param userData variables used for substitution
     * @return rendered template text
     */
    private static String renderTemplate(String template, Map<String, String> userData) {
        if (userData == null || userData.isEmpty()) {
            return template;
        }
        String content = template;
        for (Map.Entry<String, String> param : userData.entrySet()) {
            content = content.replace(tokenFor(param.getKey()), escapeHtml(String.valueOf(param.getValue())));
        }
        return content;
    }

    /**
     * Escapes HTML special characters in a template variable value to prevent
     * injection when email clients render HTML content.
     *
     * @param value raw template variable value
     * @return HTML-escaped value
     */
    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Formats a placeholder key into the token syntax used by templates.
     *
     * @param key template variable name
     * @return token in {@code {{key}}} format
     */
    private static String tokenFor(String key) {
        return TEMPLATE_TOKEN_PREFIX + key + TEMPLATE_TOKEN_SUFFIX;
    }

    /**
     * Validates that a notification type was resolved before rendering.
     *
     * @param notificationType notification type to validate
     */
    private static void requireNotificationType(String notificationType) {
        if (notificationType == null || notificationType.isBlank()) {
            throw new BusinessException(ErrorCode.NOTIFICATION_TYPE_REQUIRED, NOTIFICATION_TYPE_REQUIRED);
        }
    }

    /**
     * Validates the minimum payload fields required for template rendering.
     *
     * <p>This intentionally does NOT validate the email address — rendering needs only the
     * payload itself and a notification type. Email-address validation is a separate concern
     * handled by {@link #requireUserEmail} and only gates the email-delivery leg.
     *
     * @param request incoming notification payload
     */
    private static void validateRequest(NotificationRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.NOTIFICATION_PAYLOAD_REQUIRED, NOTIFICATION_PAYLOAD_REQUIRED);
        }
    }

    /**
     * Validates that a deliverable recipient email address is present.
     *
     * <p>This gates only the email-delivery leg. It is deliberately kept out of
     * {@link #validateRequest} so a missing email never blocks template rendering or the
     * in-app notification channel.
     *
     * @param request incoming notification payload
     */
    private static void requireUserEmail(NotificationRequest request) {
        validateRequest(request);
        if (request.getUserEmail() == null || request.getUserEmail().isBlank()) {
            throw new BusinessException(ErrorCode.RECIPIENT_EMAIL_REQUIRED, USER_EMAIL_REQUIRED);
        }
    }
}
