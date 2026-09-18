package com.example.notification_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaymentNotificationDTO {
    private Long paymentId;
    private Long orderId;
    private Long userId;
    private String customerEmail;
    private BigDecimal amount;
    private String paymentMode;
    private String transactionId;
    private String status;
}
