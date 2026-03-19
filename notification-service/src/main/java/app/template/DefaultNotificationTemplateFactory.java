package app.template;

import app.dto.EmailTemplate;
import app.exception.BusinessException;
import app.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Default mapping from event type to email subject and template.
 */
@Component
public final class DefaultNotificationTemplateFactory implements NotificationTemplateFactory {

    private final Map<String, EmailTemplate> templates;

    public DefaultNotificationTemplateFactory(Map<String, EmailTemplate> templates) {
        this.templates = templates;
    }

    @Override
    public EmailTemplate resolve(String type) {
        EmailTemplate template = templates.get(type);
        if (template == null) {
            throw new BusinessException(ErrorCode.EMAIL_CONTENT_RESOLUTION_FAILED, "No template defined for notification type: " + type);
        }
        return template;
    }
}
