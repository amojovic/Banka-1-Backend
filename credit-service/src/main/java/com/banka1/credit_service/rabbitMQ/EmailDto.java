package com.banka1.credit_service.rabbitMQ;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DTO sent to the RabbitMQ email service.
 * Contains all necessary data for generating and sending email notifications
 * related to credit/loan operations.
 * Fields with {@code null} values are excluded from JSON serialization.
 *
 * <p>The notification-service consumer renders the email body by substituting
 * {@code {{key}}} placeholders from {@code templateVariables}. The dedicated
 * fields ({@code creditId}, {@code approvedAmount}, {@code installmentAmount},
 * {@code hours}) are kept for backward compatibility, but every constructor
 * also mirrors the values the matching template needs into
 * {@code templateVariables} so the body renders correctly:
 * <ul>
 *     <li>{@code CREDIT_APPROVED} — {@code {{creditId}}}, {@code {{approvedAmount}}}</li>
 *     <li>{@code CREDIT_DECLINED} — {@code {{creditId}}}</li>
 *     <li>{@code CREDIT_INSTALLMENT_FAILED} — {@code {{creditId}}},
 *     {@code {{installmentAmount}}}, {@code {{hours}}}</li>
 *     <li>{@code CREDIT_CREATED} — {@code {{creditId}}}</li>
 * </ul>
 */
@NoArgsConstructor
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmailDto {

    /**
     * Email address of the notification recipient.
     */
    private String userEmail;

    /**
     * Name or username of the recipient (used in the email text).
     */
    private String username;

    /**
     * Type of email notification that determines the content and template of the email.
     */
    private EmailType emailType;


    /** The credit/loan ID associated with this notification. */
    private Long creditId;

    /** The approved credit amount (used for CREDIT_APPROVED notification). */
    private BigDecimal approvedAmount;

    /** The installment amount due (used for CREDIT_INSTALLMENT_FAILED notification). */
    private BigDecimal installmentAmount;

    /** Hours until payment deadline (used for CREDIT_INSTALLMENT_FAILED notification). */
    private Integer hours;

    /**
     * Template variables forwarded to the notification-service template engine.
     * Mapped onto {@code templateVariables} of the consumer-side payload.
     */
    private Map<String, String> templateVariables;

    /**
     * Identifier of the user the in-app notification belongs to. Mapped onto
     * {@code recipientUserId} on the consumer; when {@code null} the consumer
     * skips the in-app row and only the email is delivered.
     */
    private Long recipientUserId;

    /**
     * Recipient id-space discriminator — {@code CLIENT} or {@code EMPLOYEE}.
     * Mapped onto {@code recipientType} on the consumer; required together with
     * {@code recipientUserId} for an in-app row to be created.
     */
    private String recipientType;

    /**
     * Constructs an EmailDto for failed credit installment notification.
     * The {@code CREDIT_INSTALLMENT_FAILED} template renders
     * {@code {{creditId}}}, {@code {{installmentAmount}}} and {@code {{hours}}},
     * so all three are mirrored into {@code templateVariables}.
     *
     * @param userEmail the recipient's email address
     * @param username the recipient's username
     * @param creditId the credit ID
     * @param installmentAmount the amount of the failed installment
     * @param hours hours until payment deadline
     */
    public EmailDto(String userEmail, String username, Long creditId, BigDecimal installmentAmount, Integer hours) {
        this.userEmail = userEmail;
        this.username = username;
        this.creditId = creditId;
        this.installmentAmount = installmentAmount;
        this.emailType = EmailType.CREDIT_INSTALLMENT_FAILED;
        this.hours = hours;
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("creditId", String.valueOf(creditId));
        if (installmentAmount != null) {
            variables.put("installmentAmount", installmentAmount.toPlainString());
        }
        if (hours != null) {
            variables.put("hours", String.valueOf(hours));
        }
        this.templateVariables = variables;
    }

    /**
     * Constructs an EmailDto for denied credit notification.
     * The {@code CREDIT_DECLINED} template renders {@code {{creditId}}}, which
     * is mirrored into {@code templateVariables}.
     *
     * @param userEmail the recipient's email address
     * @param username the recipient's username
     * @param creditId the credit request id (NOT the client id)
     */
    public EmailDto(String userEmail, String username, Long creditId) {
        this.userEmail = userEmail;
        this.username = username;
        this.creditId = creditId;
        this.emailType = EmailType.CREDIT_DENIED;
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("creditId", String.valueOf(creditId));
        this.templateVariables = variables;
    }

    /**
     * Constructs an EmailDto for approved credit notification.
     * The {@code CREDIT_APPROVED} template renders {@code {{creditId}}} and
     * {@code {{approvedAmount}}}, both mirrored into {@code templateVariables}.
     *
     * @param userEmail the recipient's email address
     * @param username the recipient's username
     * @param approvedAmount the amount of approved credit
     * @param creditId the credit ID
     */
    public EmailDto(String userEmail, String username, BigDecimal approvedAmount, Long creditId) {
        this.userEmail = userEmail;
        this.username = username;
        this.creditId = creditId;
        this.emailType = EmailType.CREDIT_APPROVED;
        this.approvedAmount = approvedAmount;
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("creditId", String.valueOf(creditId));
        if (approvedAmount != null) {
            variables.put("approvedAmount", approvedAmount.toPlainString());
        }
        this.templateVariables = variables;
    }

    /**
     * Constructs an EmailDto for a newly created (pending) credit request.
     * The credit request id is forwarded through {@code templateVariables} so
     * the {@code CREDIT_CREATED} template can render the {@code {{creditId}}}
     * placeholder.
     *
     * @param userEmail the recipient's email address
     * @param username the recipient's username
     * @param creditId the credit request id
     * @param recipientUserId the owning client id for the in-app notification row
     */
    public EmailDto(String userEmail, String username, Long creditId, Long recipientUserId) {
        this.userEmail = userEmail;
        this.username = username;
        this.creditId = creditId;
        this.emailType = EmailType.CREDIT_CREATED;
        this.recipientUserId = recipientUserId;
        this.recipientType = "CLIENT";
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("creditId", String.valueOf(creditId));
        this.templateVariables = variables;
    }
}
