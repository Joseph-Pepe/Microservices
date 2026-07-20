package com.example.demo.services;

import com.example.demo.model.Vector3D;
import com.example.demo.model.VectorCalculation;
import com.example.demo.model.VectorPair;
import com.example.demo.repository.VectorCalculationRepository;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// [Layer 3]: brain that handles the business logic (i.e., math, calculations).

// [@Service]: tells Spring: "I am a worker. Create one instance of me and share me wherever I am needed."
@Service
public class Vector3DService {

    private static final Logger log = LoggerFactory.getLogger(Vector3DService.class);

    // 1 Inject the Database Bridge
    private final VectorCalculationRepository repository;

    public Vector3DService(VectorCalculationRepository repository) {
        this.repository = repository;
    }

    // Grabs the port number this specific clone is running on
    @Value("${server.port}")
    private String serverPort;

    // All the actual math lives here now!
    public Vector3D addVectors(VectorPair vPair) {
        return new Vector3D(
            vPair.v1().x() + vPair.v2().x(),
            vPair.v1().y() + vPair.v2().y(),
            vPair.v1().z() + vPair.v2().z()
        );
    }

    // Scale a Vector
    public Vector3D scaleVector(Vector3D vector, double factor) {
        return new Vector3D(
            vector.x() * factor,
            vector.y() * factor,
            vector.z() * factor
        );
    }
    
    // 2. Calculate Magnitude & SAVE to the database
    public double calculateMagnitude(Vector3D vector) {
        log.info("🚨 Request received! Doing math on Port: {}", serverPort);

        // Calculate the math (sqrt(x^2 + y^2 + z^2))
        double magnitude = Math.sqrt(Math.pow(vector.x(), 2) + Math.pow(vector.y(), 2) + Math.pow(vector.z(), 2));

        // Create the database record
        VectorCalculation calculation = new VectorCalculation(vector.x(), vector.y(), vector.z(), magnitude);

        // SAVE IT PERMANENTLY TO POSTGRESQL!
        repository.save(calculation);

        return magnitude;
    }

    // 3. NEW ENDPOINT: Let the user view the database history!
    public List<VectorCalculation> getCalculationHistory() {
        // Automatically runs 'SELECT * FROM vector_calculations'
        return repository.findAll();
    }
}