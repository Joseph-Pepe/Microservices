package com.example.demo.model;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity // Turns this class into a table
@Table(name = "vector_calculations") // This will be the name of your table in Postgres ()
public class VectorCalculation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // The primary key (auto-increments: 1, 2, 3...)

    private double x;
    private double y;
    private double z;
    
    private double magnitude; // The answer we calculated
    
    private LocalDateTime calculatedAt; // When did it happen?

    // --- Empty Constructor required by Hibernate ---
    public VectorCalculation() {}

    // --- Constructor for us to use ---
    public VectorCalculation(double x, double y, double z, double magnitude) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.magnitude = magnitude;
        this.calculatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---
    public Long getId() { return id; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public double getMagnitude() { return magnitude; }
    public LocalDateTime getCalculatedAt() { return calculatedAt; }
}