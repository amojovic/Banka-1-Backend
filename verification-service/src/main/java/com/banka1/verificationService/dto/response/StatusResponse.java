package com.banka1.verificationService.dto.response;

import com.banka1.verificationService.model.enums.VerificationStatus;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StatusResponse {
    private Long sessionId;
    private VerificationStatus status;

    public StatusResponse(Long sessionId, VerificationStatus status) {
        this.sessionId = sessionId;
        this.status = status;
    }
}
