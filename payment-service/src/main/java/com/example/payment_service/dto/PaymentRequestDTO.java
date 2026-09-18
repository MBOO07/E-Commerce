package com.example.payment_service.dto;

import com.example.payment_service.entity.PaymentMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaymentRequestDTO {
    private Long orderId;
    private Long userId;
    private BigDecimal amount;
    private PaymentMode paymentMode;
}
