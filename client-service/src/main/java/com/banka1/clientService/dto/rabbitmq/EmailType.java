package com.banka1.clientService.dto.rabbitmq;

/**
 * Enum koji definise tipove email notifikacija koje client-service salje putem RabbitMQ-a.
 * Svaki tip nosi odgovarajuci RabbitMQ routing key.
 */
public enum EmailType {

    /** Notifikacioni mejl koji se salje kada zaposleni kreira novi profil klijenta. */
    CLIENT_CREATED("client.created"),

    /** Mejl sa linkom za reset zaboravljene lozinke klijenta. */
    CLIENT_PASSWORD_RESET("client.password_reset");

    private final String routingKey;

    EmailType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getRoutingKey() {
        return routingKey;
    }
}
