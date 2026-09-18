package com.example.payment_service.service;

import com.example.payment_service.client.OrderServiceClient;
import com.example.payment_service.dto.PaymentRequestDTO;
import com.example.payment_service.dto.PaymentResponseDTO;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
public class PaymentService {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderServiceClient orderServiceClient;

    @Transactional
    public PaymentResponseDTO processPayment(PaymentRequestDTO request) {
        String transactionId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .userId(request.getUserId())
                .amount(request.getAmount())
                .paymentMode(request.getPaymentMode())
                .status(PaymentStatus.SUCCESS)
                .transactionId(transactionId)
                .timestamp(now)
                .build();

        Payment savedPayment = paymentRepository.save(payment);
        log.info("Payment processed successfully with ID: {} and transactionId: {}", savedPayment.getId(), transactionId);

        // Notify ORDER-SERVICE via OpenFeign to update order status to CONFIRMED
        try {
            orderServiceClient.updateOrderStatus(savedPayment.getOrderId(), "CONFIRMED");
            log.info("Notified ORDER-SERVICE to update order {} status to CONFIRMED", savedPayment.getOrderId());
        } catch (Exception e) {
            log.error("Failed to notify ORDER-SERVICE for order {}: {}", savedPayment.getOrderId(), e.getMessage());
        }

        return mapToResponseDTO(savedPayment);
    }

    public PaymentResponseDTO getPaymentByOrderId(Long orderId) {
        Payment payment = paymentRepository.findFirstByOrderIdOrderByTimestampDesc(orderId)
                .orElseThrow(() -> new RuntimeException("Payment not found for orderId: " + orderId));

        return mapToResponseDTO(payment);
    }

    private PaymentResponseDTO mapToResponseDTO(Payment payment) {
        return PaymentResponseDTO.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .amount(payment.getAmount())
                .paymentMode(payment.getPaymentMode())
                .status(payment.getStatus())
                .transactionId(payment.getTransactionId())
                .timestamp(payment.getTimestamp())
                .build();
    }
}
