package com.example.payment_service.controller;

import com.example.payment_service.dto.PaymentInitiateRequest;
import com.example.payment_service.dto.PaymentInitiateResponse;
import com.example.payment_service.dto.PaymentRequestDTO;
import com.example.payment_service.dto.PaymentResponseDTO;
import com.example.payment_service.service.PaymentService;
import com.example.payment_service.service.PaytmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping({"/api/payments", "/payments"})
@Slf4j
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaytmService paytmService;

    /**
     * Initiate Paytm transaction. Generates cryptographic HMAC-SHA256 checksum and returns gateway parameters.
     */
    @PostMapping("/initiate")
    public ResponseEntity<PaymentInitiateResponse> initiatePaytmPayment(@RequestBody PaymentInitiateRequest request) {
        log.info("REST request to initiate Paytm payment for order: {}", request.getOrderId());
        PaymentInitiateResponse response = paytmService.initiatePayment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Paytm verification callback/webhook endpoint.
     * Supports both JSON payloads and Form-URL-Encoded postbacks from Paytm gateway.
     */
    @PostMapping(value = "/verify", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<PaymentResponseDTO> verifyPayment(
            @RequestParam(required = false) Map<String, String> formParams,
            @RequestBody(required = false) Map<String, String> jsonParams) {

        Map<String, String> combinedParams = new HashMap<>();
        if (formParams != null) {
            combinedParams.putAll(formParams);
        }
        if (jsonParams != null) {
            combinedParams.putAll(jsonParams);
        }

        log.info("REST request to verify Paytm payment callback with {} params", combinedParams.size());
        PaymentResponseDTO response = paytmService.verifyPayment(combinedParams);
        return ResponseEntity.ok(response);
    }

    /**
     * Legacy / Direct payment processing endpoint.
     */
    @PostMapping("/process")
    public ResponseEntity<PaymentResponseDTO> processPayment(@RequestBody PaymentRequestDTO request) {
        PaymentResponseDTO response = paymentService.processPayment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Fetch payment by Order ID.
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentResponseDTO> getPaymentByOrderId(@PathVariable Long orderId) {
        PaymentResponseDTO response = paymentService.getPaymentByOrderId(orderId);
        return ResponseEntity.ok(response);
    }
}
