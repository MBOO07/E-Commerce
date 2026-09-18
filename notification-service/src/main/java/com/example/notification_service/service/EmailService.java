package com.example.notification_service.service;

import com.example.notification_service.dto.OrderNotificationDTO;
import com.example.notification_service.dto.PaymentNotificationDTO;
import com.example.notification_service.entity.NotificationLog;
import com.example.notification_service.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
public class EmailService {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Autowired
    private NotificationRepository notificationRepository;

    public void sendEmail(String to, String subject, String body, String eventType) {
        String status = "SENT";
        try {
            if (mailSender != null) {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setTo(to);
                message.setSubject(subject);
                message.setText(body);
                mailSender.send(message);
                log.info("Email dispatched successfully to: {}", to);
            } else {
                log.info("[MOCK EMAIL] JavaMailSender not active. Dispatching mock email to: {}\nSubject: {}\n{}", to, subject, body);
            }
        } catch (Exception e) {
            log.warn("SMTP email dispatch failed: {}. Logging notification locally.", e.getMessage());
            status = "FAILED";
        }

        // Persist audit log
        NotificationLog notificationLog = NotificationLog.builder()
                .recipient(to)
                .subject(subject)
                .body(body)
                .eventType(eventType)
                .channel("EMAIL")
                .status(status)
                .timestamp(LocalDateTime.now())
                .build();

        notificationRepository.save(notificationLog);
    }

    public void sendOrderConfirmation(OrderNotificationDTO order) {
        String recipient = order.getCustomerEmail() != null ? order.getCustomerEmail() : "customer@example.com";
        String subject = "Order Confirmation - Order #" + order.getOrderId();
        String body = String.format(
                "Hello %s,\n\nYour order has been placed successfully!\n\n" +
                "Order ID: %d\nProduct: %s\nQuantity: %d\nTotal Price: $%.2f\n\n" +
                "Thank you for shopping with us!",
                order.getCustomerName() != null ? order.getCustomerName() : "Valued Customer",
                order.getOrderId(),
                order.getProductName() != null ? order.getProductName() : "Product",
                order.getQuantity() != null ? order.getQuantity() : 1,
                order.getTotalPrice() != null ? order.getTotalPrice() : 0.0
        );

        sendEmail(recipient, subject, body, "ORDER_CONFIRMATION");
    }

    public void sendPaymentReceipt(PaymentNotificationDTO payment) {
        String recipient = payment.getCustomerEmail() != null ? payment.getCustomerEmail() : "customer@example.com";
        String subject = "Payment Receipt - Order #" + payment.getOrderId();
        String body = String.format(
                "Hello,\n\nYour payment of $%s has been processed successfully!\n\n" +
                "Transaction ID: %s\nOrder ID: %d\nPayment Mode: %s\nStatus: %s\n\n" +
                "Thank you for your business!",
                payment.getAmount(),
                payment.getTransactionId(),
                payment.getOrderId(),
                payment.getPaymentMode(),
                payment.getStatus()
        );

        sendEmail(recipient, subject, body, "PAYMENT_RECEIPT");
    }
}
