package com.example.demo.controllers;

import com.example.demo.model.Vector3D;
import com.example.demo.services.Vector3DService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest; // 1. FIXED FOR SPRING BOOT 4 (Notice 'webmvc.test.autoconfigure' instead of 'test.autoconfigure.web.servlet')
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean; // 2. FIXED FOR SPRING BOOT 4 (This is the modern @MockitoBean)

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Tells Spring to ONLY load the web layer and this specific controller
@WebMvcTest(controllers = Vector3DController.class)

// Explicitly tell Spring Boot to load your controller and service classes into this test sandbox
@ContextConfiguration(classes = {Vector3DController.class, Vector3DService.class})

// Simulates HTTP traffic without actually starting a real web server.
class Vector3DControllerTest {

    @Autowired
    private MockMvc mockMvc; // Simulates HTTP requests

    @MockitoBean
    private Vector3DService vectorService; // Creates a fake version of our service

    @Test
    void testAddVectorsEndpoint() throws Exception {
        // 1. Arrange: Tell the fake service what to return when it is called
        Vector3D fakeResponse = new Vector3D(5.0, 7.0, 9.0);
        Mockito.when(vectorService.addVectors(any())).thenReturn(fakeResponse);

        // A sample JSON payload to send in the test
        String requestJson = """
                {
                  "v1": { "x": 1.0, "y": 2.0, "z": 3.0 },
                  "v2": { "x": 4.0, "y": 5.0, "z": 6.0 }
                }
                """;

        // 2. Assert: Send the request and check the response (i.e., make sure the endpoint returns the correct JSON and status codes)
        mockMvc.perform(post("/api/vectors/addVectors")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk()) // Expect a 200 OK status
                .andExpect(jsonPath("$.x").value(5.0)) // Check the JSON output
                .andExpect(jsonPath("$.y").value(7.0))
                .andExpect(jsonPath("$.z").value(9.0));
    }
}