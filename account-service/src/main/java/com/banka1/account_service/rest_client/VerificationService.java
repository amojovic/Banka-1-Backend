package com.banka1.account_service.rest_client;


import com.banka1.account_service.dto.request.ValidateRequest;
import com.banka1.account_service.dto.response.ValidateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class VerificationService {

    private final RestClient restClient;

    public VerificationService(@Qualifier("verificationClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public ValidateResponse validate(ValidateRequest request)
    {
        return restClient.post()
                .uri("/validate")
                .body(request)
                .retrieve()
                .body(ValidateResponse.class);
    }
}
