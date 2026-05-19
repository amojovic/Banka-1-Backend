package com.banka1.credit_service.rabbitMQ;

/**
 * Enum defining types of email notifications sent by the credit-service via RabbitMQ.
 * Each type carries the corresponding RabbitMQ routing key for message routing.
 */
public enum EmailType {

    /** Email notification for a newly created (pending) credit request. */
    CREDIT_CREATED("credit.created"),

    /** Email notification for approved credit. */
    CREDIT_APPROVED("credit.approved"),

    /** Email notification for denied credit. */
    CREDIT_DENIED("credit.denied"),

    /** Email notification for failed credit installment payment. */
    CREDIT_INSTALLMENT_FAILED("credit.installment.failed");


    private final String routingKey;

    /**
     * Constructs EmailType with the specified routing key.
     *
     * @param routingKey the RabbitMQ routing key for this email type
     */
    EmailType(String routingKey) {
        this.routingKey = routingKey;
    }

    /**
     * Gets the RabbitMQ routing key for this email type.
     *
     * @return the routing key
     */
    public String getRoutingKey() {
        return routingKey;
    }
}
