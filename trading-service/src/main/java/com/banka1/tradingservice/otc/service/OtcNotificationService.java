package com.banka1.tradingservice.otc.service;

import com.banka1.order.client.ClientClient;
import com.banka1.order.dto.CustomerDto;
import com.banka1.tradingservice.notification.TradingNotificationProducer;
import com.banka1.tradingservice.otc.domain.OptionContract;
import com.banka1.tradingservice.otc.domain.OtcOffer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OtcNotificationService {

    static final String OTC_COUNTER_ROUTING_KEY = "otc.countered";
    static final String OTC_ACCEPTED_ROUTING_KEY = "otc.accepted";
    static final String OTC_CANCELED_ROUTING_KEY = "otc.canceled";
    static final String OTC_EXPIRY_ROUTING_KEY = "otc.expiry_reminder";

    private final TradingNotificationProducer notificationProducer;
    private final ClientClient clientClient;

    public void sendCounterOffer(OtcOffer offer, Long actorId) {
        Long recipientId = offer.getBuyerId().equals(actorId) ? offer.getSellerId() : offer.getBuyerId();
        notifyRecipient(recipientId, OTC_COUNTER_ROUTING_KEY, baseOfferVariables("COUNTER_OFFERED", offer, actorId));
    }

    public void sendAccepted(OtcOffer offer, Long actorId) {
        Long recipientId = offer.getBuyerId().equals(actorId) ? offer.getSellerId() : offer.getBuyerId();
        notifyRecipient(recipientId, OTC_ACCEPTED_ROUTING_KEY, baseOfferVariables("ACCEPTED", offer, actorId));
    }

    public void sendCanceled(OtcOffer offer, Long actorId, String eventType) {
        Long recipientId = offer.getBuyerId().equals(actorId) ? offer.getSellerId() : offer.getBuyerId();
        notifyRecipient(recipientId, OTC_CANCELED_ROUTING_KEY, baseOfferVariables(eventType, offer, actorId));
    }

    public void sendExpiryReminder(OptionContract contract, int reminderDays) {
        Map<String, String> buyerVars = baseContractVariables("EXPIRY_REMINDER", contract, contract.getSellerId());
        buyerVars.put("reminderDays", String.valueOf(reminderDays));
        notifyRecipient(contract.getBuyerId(), OTC_EXPIRY_ROUTING_KEY, buyerVars);

        Map<String, String> sellerVars = baseContractVariables("EXPIRY_REMINDER", contract, contract.getBuyerId());
        sellerVars.put("reminderDays", String.valueOf(reminderDays));
        notifyRecipient(contract.getSellerId(), OTC_EXPIRY_ROUTING_KEY, sellerVars);
    }

    private Map<String, String> baseOfferVariables(String eventType, OtcOffer offer, Long actorId) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("eventType", eventType);
        variables.put("offerId", String.valueOf(offer.getId()));
        variables.put("contractId", "");
        variables.put("stockTicker", offer.getStockTicker());
        variables.put("amount", String.valueOf(offer.getAmount()));
        variables.put("pricePerStock", String.valueOf(offer.getPricePerStock()));
        variables.put("premium", String.valueOf(offer.getPremium()));
        variables.put("status", offer.getStatus().name());
        variables.put("timestamp", String.valueOf(LocalDateTime.now()));
        variables.put("expiryDate", String.valueOf(offer.getSettlementDate()));
        Long counterpartyId = offer.getBuyerId().equals(actorId) ? offer.getSellerId() : offer.getBuyerId();
        variables.put("counterpartyId", String.valueOf(actorId));
        variables.put("otherPartyId", String.valueOf(counterpartyId));
        variables.put("buyerId", String.valueOf(offer.getBuyerId()));
        variables.put("sellerId", String.valueOf(offer.getSellerId()));
        return variables;
    }

    private Map<String, String> baseContractVariables(String eventType, OptionContract contract, Long otherPartyId) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("eventType", eventType);
        variables.put("offerId", String.valueOf(contract.getOfferId()));
        variables.put("contractId", String.valueOf(contract.getId()));
        variables.put("stockTicker", contract.getStockTicker());
        variables.put("amount", String.valueOf(contract.getAmount()));
        variables.put("pricePerStock", String.valueOf(contract.getPricePerStock()));
        variables.put("status", contract.getStatus().name());
        variables.put("timestamp", String.valueOf(LocalDateTime.now()));
        variables.put("expiryDate", String.valueOf(contract.getSettlementDate()));
        variables.put("otherPartyId", String.valueOf(otherPartyId));
        variables.put("buyerId", String.valueOf(contract.getBuyerId()));
        variables.put("sellerId", String.valueOf(contract.getSellerId()));
        return variables;
    }

    private void notifyRecipient(Long clientId, String routingKey, Map<String, String> variables) {
        CustomerDto customer = clientClient.getCustomer(clientId);
        if (customer == null || customer.getEmail() == null || customer.getEmail().isBlank()) {
            return;
        }
        String displayName = ((customer.getFirstName() == null ? "" : customer.getFirstName()) + " "
                + (customer.getLastName() == null ? "" : customer.getLastName())).trim();
        notificationProducer.send(routingKey, displayName, customer.getEmail(), variables);
    }
}
