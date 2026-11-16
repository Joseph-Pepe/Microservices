package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
// import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(
        classes = DemoApplication.class,
        properties = {
			"spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
			"spring.datasource.driver-class-name=org.h2.Driver",
			"spring.datasource.username=sa",
			"spring.datasource.password=",
			"spring.jpa.hibernate.ddl-auto=create-drop",
			"spring.jpa.show-sql=false"
        }
)
// @ContextConfiguration(classes = DemoApplication.class) // Forces Spring Boot to locate your main class smoothly
class DemoApplicationTests {

	@Test
	void contextLoads() {
		// This test will pass automatically if the Spring application context starts up successfully
	}
}