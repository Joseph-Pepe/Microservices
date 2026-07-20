package com.example.demo.repository;

import com.example.demo.model.VectorCalculation; // Import your Entity
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

// A repository is a bridge between Spring and PostgreSQL (Database)
@Repository
public interface VectorCalculationRepository extends JpaRepository<VectorCalculation, Long> {
    // You don't have to write a single line of code here. Spring does it all!
    // extends JpaRepository<VectorCalculation, Long>: Spring Boot automatically writes all the SQL code for saving, finding and deleting records behind the scenes.
}