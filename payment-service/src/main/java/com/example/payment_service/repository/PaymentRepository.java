package com.example.payment_service.repository;

import com.example.payment_service.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findFirstByOrderIdOrderByTimestampDesc(Long orderId);
    List<Payment> findAllByOrderId(Long orderId);
    Optional<Payment> findByTransactionId(String transactionId);
}
