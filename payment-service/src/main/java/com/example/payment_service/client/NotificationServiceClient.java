package com.example.payment_service.client;

import com.example.payment_service.dto.PaymentNotificationDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "NOTIFICATION-SERVICE")
public interface NotificationServiceClient {

    @PostMapping("/api/notifications/payment-success")
    Map<String, String> sendPaymentSuccess(@RequestBody PaymentNotificationDTO paymentNotification);
}
