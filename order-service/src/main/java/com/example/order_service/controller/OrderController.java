package com.example.order_service.controller;

import com.example.order_service.client.NotificationServiceClient;
import com.example.order_service.client.ProductServiceClient;
import com.example.order_service.dto.OrderResponseDTO;
import com.example.order_service.dto.ProductDTO;
import com.example.order_service.entity.Order;
import com.example.order_service.repository.OrderRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/orders", "/orders"})
@Slf4j
public class OrderController {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductServiceClient productServiceClient;

    @Autowired
    private NotificationServiceClient notificationServiceClient;

    // Place an order with OpenFeign, Stock Validation, and Resilience4j
    @PostMapping("/placeOrder")
    @CircuitBreaker(name = "productService", fallbackMethod = "placeOrderFallback")
    @Retry(name = "productService")
    @Bulkhead(name = "productService")
    public ResponseEntity<OrderResponseDTO> placeOrder(@RequestBody Order order) {
        if (order.getStatus() == null || order.getStatus().isBlank()) {
            order.setStatus("PENDING");
        }

        // 1. Fetch product details via OpenFeign
        ProductDTO productDTO = productServiceClient.getProductById(order.getProductId());
        if (productDTO == null) {
            throw new IllegalArgumentException("Product not found with id: " + order.getProductId());
        }

        // 2. Check stock availability
        Boolean hasStock = productServiceClient.checkStock(order.getProductId(), order.getQuantity());
        if (Boolean.FALSE.equals(hasStock)) {
            throw new IllegalArgumentException("Insufficient stock for product ID: " + order.getProductId() +
                    ". Available: " + productDTO.getStockQuantity() + ", Requested: " + order.getQuantity());
        }

        // 3. Deduct stock
        productServiceClient.deductStock(order.getProductId(), order.getQuantity());

        // 4. Save order details to DB
        Order savedOrder = orderRepository.save(order);

        // 5. Build response DTO
        OrderResponseDTO responseDTO = new OrderResponseDTO();
        responseDTO.setOrderId(savedOrder.getId());
        responseDTO.setProductId(savedOrder.getProductId());
        responseDTO.setQuantity(savedOrder.getQuantity());
        responseDTO.setProductName(productDTO.getName());
        responseDTO.setProductprice(productDTO.getPrice());
        responseDTO.setTotalPrice(savedOrder.getQuantity() * productDTO.getPrice());
        responseDTO.setStatus(savedOrder.getStatus());

        // 6. Notify notification service asynchronously / with error suppression
        try {
            Map<String, Object> notificationPayload = new HashMap<>();
            notificationPayload.put("orderId", savedOrder.getId());
            notificationPayload.put("productName", productDTO.getName());
            notificationPayload.put("quantity", savedOrder.getQuantity());
            notificationPayload.put("totalPrice", responseDTO.getTotalPrice());
            notificationPayload.put("customerEmail", "customer@example.com");
            notificationServiceClient.sendOrderConfirmation(notificationPayload);
        } catch (Exception e) {
            log.warn("Could not dispatch notification for order {}: {}", savedOrder.getId(), e.getMessage());
        }

        return ResponseEntity.ok(responseDTO);
    }

    // Fallback method for placeOrder
    public ResponseEntity<OrderResponseDTO> placeOrderFallback(Order order, Throwable t) {
        log.error("Fallback triggered for placeOrder with order {} due to: {}", order, t.getMessage());
        if (t instanceof IllegalArgumentException) {
            throw (IllegalArgumentException) t;
        }
        OrderResponseDTO fallbackResponse = new OrderResponseDTO();
        fallbackResponse.setProductId(order.getProductId());
        fallbackResponse.setQuantity(order.getQuantity());
        fallbackResponse.setStatus("FAILED: Product Service is currently unavailable. " + t.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(fallbackResponse);
    }

    // Get all orders
    @GetMapping
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    // Get order by id
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrderById(@PathVariable Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + orderId));
        return ResponseEntity.ok(order);
    }

    // Update order status (used by payment-service via OpenFeign)
    @PutMapping("/{orderId}/status")
    public ResponseEntity<Order> updateOrderStatus(@PathVariable Long orderId, @RequestParam String status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found with id: " + orderId));
        order.setStatus(status);
        Order updatedOrder = orderRepository.save(order);
        return ResponseEntity.ok(updatedOrder);
    }
}
