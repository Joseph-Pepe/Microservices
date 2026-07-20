package com.example.demo.services;

import com.example.demo.model.Vector3D;
import com.example.demo.model.VectorPair;
import com.example.demo.repository.VectorCalculationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

// No dependency on the web server.
@ExtendWith(MockitoExtension.class) // Tells JUnit to use Mockito
class Vector3DServiceTest {

    @Mock // Creates the fake database bridge automatically
    private VectorCalculationRepository mockRepository;

    @InjectMocks // Creates the Service and automatically shoves the @Mock into its constructor!
    private Vector3DService vectorService;     // Instantiate the service we want to test

    // ===================================
    // ANNOTATIONS (@Mock & @InjectMocks)
    // ===================================
    /*
        - @Mock & @InjectMocks allows us to not have to write this setUp function. 
        - It does it automatically.

        @BeforeEach
        void setUp() {
            // 2. Create a "fake" database bridge that won't actually try to hit Postgres
            mockRepository = Mockito.mock(VectorCalculationRepository.class);

            // 3. Pass the fake repository into the service, satisfying the new constructor!
            vectorService = new Vector3DService(mockRepository);
        }
    */
    
    // [@Test]: unit test the pure math and logic in isolation
    @Test
    void testAddVectors() {
        // 1. Arrange: Set up the data
        Vector3D v1 = new Vector3D(1.0, 2.0, 3.0);
        Vector3D v2 = new Vector3D(4.0, 5.0, 6.0);
        VectorPair pair = new VectorPair(v1, v2);

        // 2. Act: Call the method
        Vector3D result = vectorService.addVectors(pair);

        // 3. Assert: Verify the outcome
        assertEquals(5.0, result.x(), "X coordinate should be 5.0");
        assertEquals(7.0, result.y(), "Y coordinate should be 7.0");
        assertEquals(9.0, result.z(), "Z coordinate should be 9.0");
    }
}