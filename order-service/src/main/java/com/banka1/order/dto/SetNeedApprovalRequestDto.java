package com.banka1.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request DTO for toggling an agent's {@code needApproval} flag.
 * When {@code true}, every order placed by the agent requires supervisor approval
 * regardless of whether the agent is under their daily limit.
 */
@Data
public class SetNeedApprovalRequestDto {

    /** New value for the agent's {@code needApproval} flag. Must be non-null. */
    @NotNull
    private Boolean needApproval;
}
