package com.example.product_service.controller;

import com.example.product_service.entity.Product;
import com.example.product_service.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import com.example.product_service.specification.ProductSpecification;
import org.springframework.data.jpa.domain.Specification;

@RestController
@RequestMapping({"/api/products", "/products"})
public class ProductController {

    @Autowired
    private ProductRepository productRepository;

    // create a product
    @PostMapping
    @CacheEvict(value = {"products", "productList"}, allEntries = true)
    public Product addProduct(@RequestBody Product product) {
        if (product.getStockQuantity() == null) {
            product.setStockQuantity(100);
        }
        if (product.getCategory() == null) {
            product.setCategory("General");
        }
        return productRepository.save(product);
    }

    // get all products
    @GetMapping
    @Cacheable(value = "productList")
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    // dynamic search using JPA Specification
    @GetMapping("/search")
    public ResponseEntity<List<Product>> searchProducts(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean inStock) {
        Specification<Product> spec = ProductSpecification.filterProducts(name, minPrice, maxPrice, category, inStock);
        List<Product> products = productRepository.findAll(spec);
        return ResponseEntity.ok(products);
    }

    // check stock availability
    @GetMapping("/{id}/check-stock")
    public ResponseEntity<Boolean> checkStock(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer quantity) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found with Id: " + id));
        boolean available = product.getStockQuantity() != null && product.getStockQuantity() >= quantity;
        return ResponseEntity.ok(available);
    }

    // deduct stock on order placement
    @PostMapping("/{id}/deduct-stock")
    @CacheEvict(value = {"products", "productList"}, allEntries = true)
    public ResponseEntity<Product> deductStock(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer quantity) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found with Id: " + id));
        if (product.getStockQuantity() == null || product.getStockQuantity() < quantity) {
            throw new IllegalArgumentException("Insufficient stock for product id " + id + ". Available: " + product.getStockQuantity() + ", Requested: " + quantity);
        }
        product.setStockQuantity(product.getStockQuantity() - quantity);
        Product saved = productRepository.save(product);
        return ResponseEntity.ok(saved);
    }

    // get product by id
    @GetMapping("/{id}")
    @Cacheable(value = "products", key = "#id")
    public ResponseEntity<Product> getProductById(@PathVariable Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found with Id: " + id));
        return ResponseEntity.ok(product);
    }

    // update a product
    @PutMapping("/{id}")
    @CacheEvict(value = {"products", "productList"}, allEntries = true)
    public ResponseEntity<Product> updateProduct(@PathVariable Long id, @RequestBody Product productDetails) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found with Id: " + id));
        if (productDetails.getName() != null) product.setName(productDetails.getName());
        if (productDetails.getPrice() != null) product.setPrice(productDetails.getPrice());
        if (productDetails.getStockQuantity() != null) product.setStockQuantity(productDetails.getStockQuantity());
        if (productDetails.getCategory() != null) product.setCategory(productDetails.getCategory());
        Product updatedProduct = productRepository.save(product);
        return ResponseEntity.ok(updatedProduct);
    }

    // delete a product
    @DeleteMapping("/{id}")
    @CacheEvict(value = {"products", "productList"}, allEntries = true)
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found with Id: " + id));
        productRepository.delete(product);
        return ResponseEntity.noContent().build();
    }
}
