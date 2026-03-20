package com.banka1.employeeService.dto.rabbitmq;

/**
 * Enum koji definise tipove email notifikacija koje employee-service salje putem RabbitMQ-a.
 * Svaki tip nosi odgovarajuci RabbitMQ routing key.
 */
public enum EmailType {

    /** Aktivacioni mejl koji se salje novom zaposlenom kako bi postavio lozinku i aktivirao nalog. */
    EMPLOYEE_CREATED("employee.created"),

    /** Mejl sa linkom za reset zaboravljene lozinke. */
    EMPLOYEE_PASSWORD_RESET("employee.password_reset"),

    /** Obaveštenje o deaktivaciji korisnickog naloga. */
    EMPLOYEE_ACCOUNT_DEACTIVATED("employee.account_deactivated");

    private final String routingKey;

    EmailType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getRoutingKey() {
        return routingKey;
    }
}
