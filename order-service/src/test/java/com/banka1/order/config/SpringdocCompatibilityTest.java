package com.banka1.order.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.profiles.active=local",
        "server.port=0",
        "jwt.secret=01234567890123456789012345678901",
        "spring.liquibase.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "services.account.url=http://localhost:18084",
        "services.employee.url=http://localhost:18081",
        "services.client.url=http://localhost:18083",
        "services.exchange.url=http://localhost:18085",
        "services.stock.url=http://localhost:18090",
        // RabbitMQ properties: order-service runs as a library inside trading-service and
        // normally inherits these from the host service's application.properties. When the
        // OrderServiceApplication context is booted standalone for this test they must be
        // supplied explicitly so RabbitConfig/OrderNotificationProducer can resolve.
        "rabbitmq.exchange=order-events-test",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=5672",
        "spring.rabbitmq.username=guest",
        "spring.rabbitmq.password=guest",
        // security-lib's SecurityConfig.authChain calls http.securityMatcher(props.getPermitAll());
        // banka.security.permit-all has no default, so it must be supplied here (normally inherited
        // from the host service). The springdoc endpoints below are exercised without a JWT, so they
        // belong in the permit-all matcher.
        "banka.security.permit-all=/v3/api-docs/**,/swagger-ui/**,/swagger-ui.html"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringdocCompatibilityTest {

    @LocalServerPort
    private int port;

    @Test
    void apiDocsEndpointIsAvailable() throws Exception {
        HttpResponse<String> response = httpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"openapi\"");
        assertThat(response.body()).contains("\"Order Service API\"");
    }

    @Test
    void swaggerUiIndexIsAvailable() throws Exception {
        HttpResponse<String> response = httpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/swagger-ui/index.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Swagger UI");
    }

    private HttpClient httpClient() {
        return HttpClient.newHttpClient();
    }
}
