package com.example.product_service.controller;


import com.example.product_service.entity.Product;
import com.example.product_service.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    @Autowired
    private ProductRepository productRepository;


    //create a product
    @PostMapping
    public Product addProduct(@RequestBody Product product)
    {
        return productRepository.save(product);
    }

    //get All products
    @GetMapping
    public List<Product> getAllProducts()
    {
        return productRepository.findAll();
    }

    //get product by id
    @GetMapping("/{productId}")
    public ResponseEntity<Product> getProductById(@PathVariable Long productId)
    {
       Product product=productRepository.findById(productId).orElseThrow(()-> new RuntimeException("Product not found with Id:"+productId));
       return ResponseEntity.ok(product);
    }

}
