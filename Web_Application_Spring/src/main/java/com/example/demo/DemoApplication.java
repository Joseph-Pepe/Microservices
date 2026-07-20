package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @SpringBootApplication(scanBasePackages = {"com.example.demo", "com.example.vectorapi"}) // scanBasePackages: Tell Spring exactly which packages to scan for controllers

// [@SpringBootApplication]: Let's spring know that this is where our microservice starts.
@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}
}

// @SpringBootApplication
// @RestController // We are making the main class a Controller to guarantee Spring finds it
// @RequestMapping("/api/vectors")
// public class DemoApplication {

// 	public static void main(String[] args) {
// 		SpringApplication.run(DemoApplication.class, args);
// 	}

// 	// 1. Define the data structures (Records) inside the main class
//     public record Vector3D(double x, double y, double z) {}
//     public record VectorPair(Vector3D v1, Vector3D v2) {}

//     // 2. A simple GET endpoint to test if the controller is awake
//     @GetMapping("/ping")
//     public String ping() {
//         return "Server is alive and the controller is working!";
//     }

//     // 3. Your actual 3D Vector addition endpoint
//     @PostMapping("/add")
//     public Vector3D addVectors(@RequestBody VectorPair pair) {
//         return new Vector3D(
//             pair.v1().x() + pair.v2().x(),
//             pair.v1().y() + pair.v2().y(),
//             pair.v1().z() + pair.v2().z()
//         );
//     }
// }