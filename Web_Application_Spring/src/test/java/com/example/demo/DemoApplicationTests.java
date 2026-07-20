package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest
@ContextConfiguration(classes = DemoApplication.class) // Forces Spring Boot 4 to locate your main class smoothly
class DemoApplicationTests {

	@Test
	void contextLoads() {
		// This test will pass automatically if the Spring application context starts up successfully
	}
}