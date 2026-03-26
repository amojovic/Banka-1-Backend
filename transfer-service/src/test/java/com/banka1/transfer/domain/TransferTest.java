package com.banka1.transfer.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferEntityTest {

    @Test
    void onCreate_ShouldSetTimestamps() {
        Transfer transfer = new Transfer();
        assertNull(transfer.getCreatedAt());

        transfer.onCreate(); // Simuliramo JPA callback

        assertNotNull(transfer.getCreatedAt());
        assertNotNull(transfer.getUpdatedAt());
        assertNotNull(transfer.getTimestamp());
    }
}