package com.example.payment_service.client;

import com.example.payment_service.dto.OrderDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "ORDER-SERVICE")
public interface OrderServiceClient {

    @PutMapping("/api/orders/{orderId}/status")
    OrderDTO updateOrderStatus(@PathVariable("orderId") Long orderId, @RequestParam("status") String status);
}
