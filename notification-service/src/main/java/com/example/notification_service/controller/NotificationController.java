package com.example.notification_service.controller;

import com.example.notification_service.dto.EmailRequestDTO;
import com.example.notification_service.dto.OrderNotificationDTO;
import com.example.notification_service.dto.PaymentNotificationDTO;
import com.example.notification_service.entity.NotificationLog;
import com.example.notification_service.repository.NotificationRepository;
import com.example.notification_service.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/notifications", "/notifications"})
public class NotificationController {

    @Autowired
    private EmailService emailService;

    @Autowired
    private NotificationRepository notificationRepository;

    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> sendEmail(@RequestBody EmailRequestDTO request) {
        emailService.sendEmail(request.getTo(), request.getSubject(), request.getBody(), "GENERAL");
        return ResponseEntity.ok(Map.of("message", "Notification dispatched successfully"));
    }

    @PostMapping("/order-confirmation")
    public ResponseEntity<Map<String, String>> sendOrderConfirmation(@RequestBody OrderNotificationDTO order) {
        emailService.sendOrderConfirmation(order);
        return ResponseEntity.ok(Map.of("message", "Order confirmation notification dispatched"));
    }

    @PostMapping("/payment-success")
    public ResponseEntity<Map<String, String>> sendPaymentSuccess(@RequestBody PaymentNotificationDTO payment) {
        emailService.sendPaymentReceipt(payment);
        return ResponseEntity.ok(Map.of("message", "Payment receipt notification dispatched"));
    }

    @GetMapping("/history")
    public ResponseEntity<List<NotificationLog>> getNotificationHistory(@RequestParam(required = false) String recipient) {
        if (recipient != null && !recipient.isBlank()) {
            return ResponseEntity.ok(notificationRepository.findByRecipient(recipient));
        }
        return ResponseEntity.ok(notificationRepository.findAll());
    }
}
