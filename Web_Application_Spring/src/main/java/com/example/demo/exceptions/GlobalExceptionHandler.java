package com.example.demo.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

// [@RestControllerAdvice] 
/*
    - annotation tells Spring to act as a bodyguard for all your controllers.
    - if a user sends a malformed request this intercepts failures globally and transforms them into clean, predictable error payloads.
    - keeps internal code structures secure and APIs easier for frontend to consume.
*/
// CHANGED: Now it ONLY watches your controllers, leaving Swagger to work in peace!
@RestControllerAdvice(basePackages = "com.example.demo.controllers")
public class GlobalExceptionHandler {

    // 1. Handle 404 Errors Cleanly
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException ex) {
        Map<String, Object> errorDetails = new HashMap<>();
        errorDetails.put("timestamp", LocalDateTime.now().toString());
        errorDetails.put("status", HttpStatus.NOT_FOUND.value());
        errorDetails.put("error", "Resource Not Found");
        errorDetails.put("message", "The endpoint you are looking for does not exist: " + ex.getResourcePath());

        // Returns an error in a clean readable JSON, so the frontend knows what failed.
        return new ResponseEntity<>(errorDetails, HttpStatus.NOT_FOUND);
    }

    // 2. Handle Everything Else (500 Internal Server Error fallback)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {

        // FIX FOR SWAGGER INTROSPECTION IN SPRING BOOT 4
        // If the exception comes from SpringDoc scanning our models, throw it so Swagger can read it!
        // if (ex.getClass().getName().contains("org.springdoc") || 
        //     ex.getStackTrace()[0].getClassName().contains("io.swagger")) {
        //     throw new RuntimeException(ex);
        // }

        Map<String, Object> errorDetails = new HashMap<>();
        errorDetails.put("timestamp", LocalDateTime.now().toString());
        errorDetails.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        errorDetails.put("error", "Internal Server Error");
        errorDetails.put("message", "An unexpected error occurred: " + ex.getMessage());

        return new ResponseEntity<>(errorDetails, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // 3. Handle Validation Errors for @Valid annotated functions (400 Bad Request)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        // Extract all the specific field errors into a list
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> 
            fieldErrors.put(error.getField(), error.getDefaultMessage()));

        Map<String, Object> errorDetails = new HashMap<>();
        errorDetails.put("timestamp", LocalDateTime.now().toString());
        errorDetails.put("status", HttpStatus.BAD_REQUEST.value());
        errorDetails.put("error", "Validation Failed");
        errorDetails.put("invalid_fields", fieldErrors); // Shows the user exactly which fields they messed up!

        return new ResponseEntity<>(errorDetails, HttpStatus.BAD_REQUEST);
    }
}