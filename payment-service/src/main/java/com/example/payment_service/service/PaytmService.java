package com.example.payment_service.service;

import com.example.payment_service.client.NotificationServiceClient;
import com.example.payment_service.client.OrderServiceClient;
import com.example.payment_service.dto.*;
import com.example.payment_service.entity.Payment;
import com.example.payment_service.entity.PaymentMode;
import com.example.payment_service.entity.PaymentStatus;
import com.example.payment_service.repository.PaymentRepository;
import com.example.payment_service.util.PaytmChecksumUtil;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class PaytmService {

    @Value("${paytm.mid:TEST_MID_12345}")
    private String mid;

    @Value("${paytm.merchantKey:TEST_MERCHANT_KEY_67890}")
    private String merchantKey;

    @Value("${paytm.website:WEBSTAGING}")
    private String website;

    @Value("${paytm.industryTypeId:Retail}")
    private String industryTypeId;

    @Value("${paytm.channelId:WEB}")
    private String channelId;

    @Value("${paytm.callbackUrl:http://localhost:8000/api/payments/verify}")
    private String callbackUrl;

    @Value("${paytm.initiateUrl:https://securegw-stage.paytm.in/order/process}")
    private String initiateUrl;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderServiceClient orderServiceClient;

    @Autowired(required = false)
    private NotificationServiceClient notificationServiceClient;

    /**
     * Initiates a Paytm payment, generates cryptographic HMAC-SHA256 checksum,
     * and records the payment in PENDING status.
     */
    @Transactional
    public PaymentInitiateResponse initiatePayment(PaymentInitiateRequest request) {
        log.info("Initiating Paytm payment for orderId: {}, userId: {}, amount: {}",
                request.getOrderId(), request.getUserId(), request.getAmount());

        // Check if an existing payment has already succeeded for this order
        Optional<Payment> existingPaymentOpt = paymentRepository.findFirstByOrderIdOrderByTimestampDesc(request.getOrderId());
        if (existingPaymentOpt.isPresent() && existingPaymentOpt.get().getStatus() == PaymentStatus.SUCCESS) {
            log.warn("Payment already completed successfully for orderId: {}", request.getOrderId());
            Payment p = existingPaymentOpt.get();
            return PaymentInitiateResponse.builder()
                    .paymentId(p.getId())
                    .orderId(p.getOrderId())
                    .transactionId(p.getTransactionId())
                    .mid(mid)
                    .amount(p.getAmount())
                    .paymentUrl(initiateUrl)
                    .callbackUrl(callbackUrl)
                    .build();
        }

        String transactionId = "TXN_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String customerId = (request.getCustomerId() != null && !request.getCustomerId().isBlank())
                ? request.getCustomerId()
                : "CUST_" + request.getUserId();

        String formattedAmount = request.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString();

        // Build Paytm parameters map
        Map<String, String> paytmParams = new HashMap<>();
        paytmParams.put("MID", mid);
        paytmParams.put("ORDER_ID", String.valueOf(request.getOrderId()));
        paytmParams.put("CUST_ID", customerId);
        paytmParams.put("TXN_AMOUNT", formattedAmount);
        paytmParams.put("CHANNEL_ID", channelId);
        paytmParams.put("WEBSITE", website);
        paytmParams.put("INDUSTRY_TYPE_ID", industryTypeId);
        paytmParams.put("CALLBACK_URL", callbackUrl);

        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            paytmParams.put("EMAIL", request.getEmail());
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            paytmParams.put("MOBILE_NO", request.getPhone());
        }

        // Generate HMAC-SHA256 Checksum signature
        String checksum = PaytmChecksumUtil.generateSignature(paytmParams, merchantKey);
        paytmParams.put("CHECKSUMHASH", checksum);

        // Save Payment record in PENDING state
        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .userId(request.getUserId())
                .amount(request.getAmount())
                .paymentMode(request.getPaymentMode() != null ? request.getPaymentMode() : PaymentMode.PAYTM)
                .status(PaymentStatus.PENDING)
                .transactionId(transactionId)
                .timestamp(LocalDateTime.now())
                .build();

        Payment savedPayment = paymentRepository.save(payment);
        log.info("Recorded PENDING payment record with ID: {} and txnId: {}", savedPayment.getId(), transactionId);

        return PaymentInitiateResponse.builder()
                .paymentId(savedPayment.getId())
                .orderId(savedPayment.getOrderId())
                .transactionId(transactionId)
                .mid(mid)
                .amount(savedPayment.getAmount())
                .checksum(checksum)
                .callbackUrl(callbackUrl)
                .paymentUrl(initiateUrl)
                .paytmParams(paytmParams)
                .build();
    }

    /**
     * Verifies the Paytm callback or webhook response, performs checksum validation,
     * enforces idempotency against replay attacks, updates payment status, and
     * communicates with ORDER-SERVICE and NOTIFICATION-SERVICE.
     */
    @Transactional
    public PaymentResponseDTO verifyPayment(Map<String, String> responseParams) {
        log.info("Received Paytm payment verification callback with params: {}", responseParams);

        String checksum = responseParams.getOrDefault("CHECKSUMHASH",
                responseParams.getOrDefault("checksum", responseParams.get("signature")));

        if (checksum == null || checksum.isBlank()) {
            throw new IllegalArgumentException("Paytm verification failed: Checksum signature is missing from callback parameters");
        }

        // Verify cryptographic signature
        boolean isValidSignature = PaytmChecksumUtil.verifySignature(responseParams, merchantKey, checksum);
        if (!isValidSignature) {
            log.error("Paytm checksum verification failed! Potential tampering detected.");
            throw new SecurityException("Paytm verification failed: Invalid cryptographic checksum signature!");
        }

        String orderIdStr = responseParams.getOrDefault("ORDERID", responseParams.get("orderId"));
        if (orderIdStr == null || orderIdStr.isBlank()) {
            throw new IllegalArgumentException("ORDERID is missing from Paytm callback");
        }

        Long orderId = Long.parseLong(orderIdStr.trim());
        String paytmTxnId = responseParams.getOrDefault("TXNID", responseParams.get("txnId"));
        String bankTxnId = responseParams.getOrDefault("BANKTXNID", responseParams.get("bankTxnId"));
        String statusStr = responseParams.getOrDefault("STATUS", responseParams.get("status"));
        String respCode = responseParams.getOrDefault("RESPCODE", responseParams.get("respCode"));
        String respMsg = responseParams.getOrDefault("RESPMSG", responseParams.get("respMsg"));

        Payment payment = paymentRepository.findFirstByOrderIdOrderByTimestampDesc(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Payment record not found for order ID: " + orderId));

        // Idempotency check: Deduplicate if already processed
        if (payment.getStatus() == PaymentStatus.SUCCESS || payment.getStatus() == PaymentStatus.REFUNDED) {
            log.warn("Payment for orderId {} has already been processed with status {}. Returning idempotent response.",
                    orderId, payment.getStatus());
            return mapToResponseDTO(payment);
        }

        payment.setPaytmTxnId(paytmTxnId);
        payment.setBankTxnId(bankTxnId);
        payment.setGatewayResponse(String.format("RespCode: %s, RespMsg: %s", respCode, respMsg));

        boolean isSuccess = "TXN_SUCCESS".equalsIgnoreCase(statusStr) || "SUCCESS".equalsIgnoreCase(statusStr);

        if (isSuccess) {
            payment.setStatus(PaymentStatus.SUCCESS);
            log.info("Paytm transaction verified successfully for order {}. PaytmTxnId: {}", orderId, paytmTxnId);

            // Update order status in ORDER-SERVICE via OpenFeign with circuit breaker
            updateOrderStatusWithResilience(orderId, "CONFIRMED");

            // Dispatch notification via NOTIFICATION-SERVICE
            dispatchPaymentNotification(payment);
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            log.warn("Paytm transaction failed for order {}. Reason: {}", orderId, respMsg);
            updateOrderStatusWithResilience(orderId, "CANCELLED");
        }

        Payment updatedPayment = paymentRepository.save(payment);
        return mapToResponseDTO(updatedPayment);
    }

    @CircuitBreaker(name = "orderService", fallbackMethod = "orderStatusFallback")
    @Retry(name = "orderService")
    public void updateOrderStatusWithResilience(Long orderId, String status) {
        log.info("Calling ORDER-SERVICE to update order {} to status {}", orderId, status);
        orderServiceClient.updateOrderStatus(orderId, status);
    }

    public void orderStatusFallback(Long orderId, String status, Throwable t) {
        log.error("Resilience4j fallback: Failed to update order status for orderId: {} to {} due to: {}",
                orderId, status, t.getMessage());
    }

    private void dispatchPaymentNotification(Payment payment) {
        if (notificationServiceClient != null) {
            try {
                PaymentNotificationDTO notificationDTO = PaymentNotificationDTO.builder()
                        .paymentId(payment.getId())
                        .orderId(payment.getOrderId())
                        .userId(payment.getUserId())
                        .amount(payment.getAmount())
                        .paymentMode(payment.getPaymentMode() != null ? payment.getPaymentMode().name() : "PAYTM")
                        .transactionId(payment.getPaytmTxnId() != null ? payment.getPaytmTxnId() : payment.getTransactionId())
                        .status(payment.getStatus().name())
                        .build();

                notificationServiceClient.sendPaymentSuccess(notificationDTO);
                log.info("Successfully dispatched payment receipt notification for orderId: {}", payment.getOrderId());
            } catch (Exception e) {
                log.error("Failed to dispatch payment notification to NOTIFICATION-SERVICE: {}", e.getMessage());
            }
        }
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
