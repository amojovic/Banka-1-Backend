package com.banka1.transaction_service.rabbitMQ;

/**
 * Enum koji definise tipove email notifikacija koje employee-service salje putem RabbitMQ-a.
 * Svaki tip nosi odgovarajuci RabbitMQ routing key.
 */
public enum EmailType {

    TRANSACTION_COMPLETED("transaction.completed"),

    TRANSACTION_DENIED("transaction.denied");


    private final String routingKey;

    EmailType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getRoutingKey() {
        return routingKey;
    }
}
