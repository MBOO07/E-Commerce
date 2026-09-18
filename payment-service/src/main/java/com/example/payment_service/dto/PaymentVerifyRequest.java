package com.example.payment_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaymentVerifyRequest {
    private String orderId;
    private String txnId;
    private String bankTxnId;
    private String txnAmount;
    private String status;
    private String respCode;
    private String respMsg;
    private String checksum;
    private Map<String, String> rawParams;
}
