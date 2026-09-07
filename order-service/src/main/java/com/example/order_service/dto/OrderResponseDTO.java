package com.example.order_service.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data   //setter , getter etc
@AllArgsConstructor
@NoArgsConstructor
public class OrderResponseDTO {
    private Long orderId;
    private Long productId;
    private int quantity;
    private double totalPrice;

    //product details

    private String productName;
    private double productprice;
}
