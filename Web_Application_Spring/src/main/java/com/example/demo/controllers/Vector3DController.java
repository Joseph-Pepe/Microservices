package com.example.demo.controllers;
import org.springframework.web.bind.annotation.*;

import com.example.demo.model.Vector3D;
import com.example.demo.model.VectorCalculation;
import com.example.demo.model.VectorPair;
import com.example.demo.services.Vector3DService;
import io.swagger.v3.oas.annotations.tags.Tag;


import jakarta.validation.Valid;

// CIRCUIT BREAKER
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


// [Layer 1]: handles HTTP requests (i.e., hands the data over to a service).
@RestController 
@RequestMapping("api/vectors")
@Tag(name = "Vector Mathematics API", description = "Endpoints for 3D vector operations and calculations") // used to customize the documentation for this API in swagger.
public class Vector3DController {
    
    private static final Logger log = LoggerFactory.getLogger(Vector3DController.class);

    // 1. Declare the service as a final variable (i.e., hires our new service).
    private final Vector3DService vectorService;

    // 2. Constructor Injection: Spring automatically passes the service in when it builds the controller (i.e., dependency injection)
    public Vector3DController(Vector3DService vectorService) {
        this.vectorService = vectorService;
    }

    @GetMapping("/ping")
    public String ping() {
        return "Controller and Service are successfully connected!";
    }

    // Add Vectors
    // curl -X POST http://localhost:8080/api/vectors/addVectors -H "Content-Type: application/json" -d "{\"v1\": {\"x\": 1.0, \"y\": 2.0, \"z\": 3.0}, \"v2\": {\"x\": 4.0, \"y\": 5.0, \"z\": 6.0}}"
    @PostMapping("/addVectors")
    @CircuitBreaker(name = "vectorMathService", fallbackMethod = "addVectorsFallback") // Binds this method to the fallback below
    public Vector3D addVectors( // Gives SpringDoc's documentation engine a hint on how to serialize a record into the Open API (Swagger) specification.
                                @io.swagger.v3.oas.annotations.parameters.RequestBody(
                                    description = "Pair of 3D vectors to add together",
                                    required = true
                                ) @Valid @RequestBody VectorPair vPair) {  // @Valid means check the rules in VectorPair before letting this data inside.
        // 3. The Controller just delegates the work to the Service!
        return vectorService.addVectors(vPair);
    }

    // --- THE FALLBACK METHOD ---
    // Must match the exact signature of addVectors(), plus a Throwable parameter at the end.
    public Vector3D addVectorsFallback(VectorPair vPair, Throwable ex) {
        log.error("Circuit breaker tripped for addVectors! Returning safe default. Reason: {}", ex.getMessage());
        
        // Return a "safe" default response so the client doesn't crash.
        // For vector addition, returning a zero-vector is a standard safe fallback.
        return new Vector3D(0.0, 0.0, 0.0); 
    }

    // Scale a Vector
    // curl -X POST http://localhost:8080/api/vectors/scale/3 -H "Content-Type: application/json" -d "{\"x\": 1.0, \"y\": 2.0, \"z\": 3.0}"
    @PostMapping("/scale/{factor}")
    public Vector3D scaleVector(@io.swagger.v3.oas.annotations.parameters.RequestBody(
                                    description = "Scales a 3D vector",
                                    required = true
                                ) @RequestBody Vector3D vector, @PathVariable double factor) {
       return vectorService.scaleVector(vector, factor);
    }

    // Calculate Magnitude & SAVE to the database
    // curl -X POST http://localhost:8080/api/vectors/calculateMagnitude -H "Content-Type: application/json" -d "{\"x\": 1.0, \"y\": 2.0, \"z\": 3.0}"
    // curl -u admin:vector-secret-123 -X POST http://localhost:8080/api/vectors/calculateMagnitude -H "Content-Type: application/json" -d "{\"x\": 3.0, \"y\": 4.0, \"z\": 0.0}"
    @PostMapping("/calculateMagnitude")
    public double calculateMagnitude(@io.swagger.v3.oas.annotations.parameters.RequestBody(
                                        description = "3D vector's magnitude",
                                        required = true
                                    ) @RequestBody Vector3D vector) {
        return vectorService.calculateMagnitude(vector);
    }

    // Let the user view the database history!
    // curl -v -u admin:vector-secret-123 http://localhost:8080/api/vectors/history
    @GetMapping("/history")
    public List<VectorCalculation> getCalculationHistory() {
        return vectorService.getCalculationHistory();
    }

    // 4. Search history by ID
    // curl -v -u admin:vector-secret-123 "http://localhost:8080/api/vectors/search?keyword=1"
    @GetMapping("/search")
    public List<VectorCalculation> searchHistoryById(@RequestParam("keyword") String keyword) {
        return vectorService.searchHistoryById(keyword);
    }
}