package com.banka1.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TaxChargeMappingTest {

    /**
     * TaxCharge intentionally references the matched sell/buy transactions by raw ID columns
     * rather than {@code @ManyToOne} associations. The foreign-key constraints were deliberately
     * dropped (changelog 010-tax-charge-drop-fk.sql) so that portfolio-average fallback rows
     * (buy_transaction_id = -1) and OTC contract IDs — which are not real transaction IDs —
     * can be stored without a FK violation. This test guards that intentional mapping.
     */
    @Test
    void taxCharge_referencesSellAndBuyTransactionsByIdColumns() throws Exception {
        assertIdColumnMapping("sellTransactionId", "sell_transaction_id");
        assertIdColumnMapping("buyTransactionId", "buy_transaction_id");
    }

    @Test
    void taxCharge_hasNoTransactionAssociations() {
        assertThat(TaxCharge.class.getDeclaredFields())
                .as("TaxCharge must not declare @ManyToOne transaction associations")
                .noneMatch(field -> field.isAnnotationPresent(ManyToOne.class)
                        || field.isAnnotationPresent(JoinColumn.class));
    }

    @Test
    void prePersist_initializesCreatedAtWhenMissing() {
        TaxCharge taxCharge = new TaxCharge();

        taxCharge.initializeCreatedAt();

        assertThat(taxCharge.getCreatedAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    private void assertIdColumnMapping(String fieldName, String columnName) throws Exception {
        Field field = TaxCharge.class.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);

        assertThat(field.getType()).isEqualTo(Long.class);
        assertThat(field.getAnnotation(ManyToOne.class))
                .as("%s must be a plain ID column, not a @ManyToOne association", fieldName)
                .isNull();
        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.nullable()).isFalse();
    }
}
