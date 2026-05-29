package com.banka1.order.dto;

import com.banka1.order.entity.RecurringOrder;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.RecurringCadence;
import com.banka1.order.entity.enums.RecurringMode;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RecurringOrderDto {

    private Long id;
    private Long userId;
    private Long listingId;
    private OrderDirection direction;
    private RecurringMode mode;
    private BigDecimal value;
    private Long accountId;
    private RecurringCadence cadence;
    private LocalDateTime nextRun;
    private Boolean active;
    private LocalDateTime createdAt;

    public static RecurringOrderDto from(RecurringOrder order) {
        RecurringOrderDto dto = new RecurringOrderDto();
        dto.setId(order.getId());
        dto.setUserId(order.getUserId());
        dto.setListingId(order.getListingId());
        dto.setDirection(order.getDirection());
        dto.setMode(order.getMode());
        dto.setValue(order.getValue());
        dto.setAccountId(order.getAccountId());
        dto.setCadence(order.getCadence());
        dto.setNextRun(order.getNextRun());
        dto.setActive(order.getActive());
        dto.setCreatedAt(order.getCreatedAt());
        return dto;
    }
}
