package com.example.notification_service.consumer;

import com.example.notification_service.dto.OrderNotificationDTO;
import com.example.notification_service.dto.PaymentNotificationDTO;
import com.example.notification_service.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
public class KafkaNotificationConsumer {

    @Autowired
    private EmailService emailService;

    @KafkaListener(topics = "order-events", groupId = "notification-group")
    public void consumeOrderEvent(OrderNotificationDTO orderNotification) {
        log.info("Received Kafka order-events for Order #{}", orderNotification.getOrderId());
        emailService.sendOrderConfirmation(orderNotification);
    }

    @KafkaListener(topics = "payment-events", groupId = "notification-group")
    public void consumePaymentEvent(PaymentNotificationDTO paymentNotification) {
        log.info("Received Kafka payment-events for Order #{}", paymentNotification.getOrderId());
        emailService.sendPaymentReceipt(paymentNotification);
    }
}
