package com.example.payment_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaymentInitiateResponse {
    private Long paymentId;
    private Long orderId;
    private String transactionId;
    private String mid;
    private BigDecimal amount;
    private String checksum;
    private String callbackUrl;
    private String paymentUrl;
    private Map<String, String> paytmParams;
}
