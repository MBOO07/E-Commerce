package com.example.payment_service.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Utility for Paytm HMAC-SHA256 checksum generation and verification.
 */
@Slf4j
public class PaytmChecksumUtil {

    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * Generates an HMAC-SHA256 checksum for a sorted map of request parameters.
     *
     * @param params      Map of request parameters
     * @param merchantKey Paytm merchant secret key
     * @return Base64 encoded checksum string
     */
    public static String generateSignature(Map<String, String> params, String merchantKey) {
        String payload = buildParameterString(params);
        return generateSignature(payload, merchantKey);
    }

    /**
     * Generates an HMAC-SHA256 checksum for raw string payload (e.g., JSON or formatted string).
     *
     * @param payload     Payload string
     * @param merchantKey Paytm merchant secret key
     * @return Base64 encoded checksum string
     */
    public static String generateSignature(String payload, String merchantKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(merchantKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            log.error("Error generating Paytm signature: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate Paytm signature", e);
        }
    }

    /**
     * Verifies the HMAC-SHA256 signature for the given parameter map.
     *
     * @param params      Map of received parameters
     * @param merchantKey Paytm merchant secret key
     * @param checksum    Received checksum string
     * @return true if valid, false otherwise
     */
    public static boolean verifySignature(Map<String, String> params, String merchantKey, String checksum) {
        if (checksum == null || checksum.trim().isEmpty()) {
            return false;
        }
        String payload = buildParameterString(params);
        return verifySignature(payload, merchantKey, checksum);
    }

    /**
     * Verifies the HMAC-SHA256 signature for a raw payload string.
     *
     * @param payload     Raw payload string
     * @param merchantKey Paytm merchant secret key
     * @param checksum    Received checksum string
     * @return true if valid, false otherwise
     */
    public static boolean verifySignature(String payload, String merchantKey, String checksum) {
        try {
            String calculatedSignature = generateSignature(payload, merchantKey);
            return MessageDigest.isEqual(
                    calculatedSignature.getBytes(StandardCharsets.UTF_8),
                    checksum.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Error verifying Paytm signature: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Sorts parameters alphabetically by key and joins non-null values with '|'.
     * Excludes checksum parameter itself.
     */
    public static String buildParameterString(Map<String, String> params) {
        TreeMap<String, String> sortedMap = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedMap.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            if ("CHECKSUMHASH".equalsIgnoreCase(key) || "checksum".equalsIgnoreCase(key) || "signature".equalsIgnoreCase(key)) {
                continue;
            }
            if (val != null && !val.trim().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("|");
                }
                sb.append(val.trim());
            }
        }
        return sb.toString();
    }
}
