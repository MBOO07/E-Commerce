package com.example.payment_service.dto;

import com.example.payment_service.entity.PaymentMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaymentInitiateRequest {
    private Long orderId;
    private Long userId;
    private BigDecimal amount;
    private String customerId;
    private String email;
    private String phone;
    @Builder.Default
    private PaymentMode paymentMode = PaymentMode.PAYTM;
}
