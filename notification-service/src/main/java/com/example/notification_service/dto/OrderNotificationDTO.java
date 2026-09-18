package com.example.notification_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderNotificationDTO {
    private Long orderId;
    private Long userId;
    private String customerEmail;
    private String customerName;
    private String productName;
    private Integer quantity;
    private Double totalPrice;
}
