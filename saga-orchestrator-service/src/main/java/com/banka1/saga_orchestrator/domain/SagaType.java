package com.banka1.saga_orchestrator.domain;

/**
 * Tipovi SAGA-a koje orkestrator podržava. Mapiraju se na konkretne tokove
 * iz GH issue-a #213/#220/#231 (OTC kupoprodaja i fond likvidacija).
 */
public enum SagaType {
    /**
     * OTC ,,Iskoristi opciju'' tok (Issue #220): rezervacija sredstava u
     * banking-service, transfer hartija u order-service, naplata u
     * banking-service. Posle banking-konsolidacije svedeno na 2 servisa.
     */
    OTC_EXERCISE,

    /**
     * Velika isplata iz fonda (Issue #231): likvidacija pozicija fonda u
     * order-service, transfer sredstava klijentu u banking-service.
     */
    FUND_LIQUIDATION_FOR_REDEMPTION
}
