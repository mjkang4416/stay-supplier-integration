package com.staysupplier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "cache.refresh-enabled=false")
class StaySupplierIntegrationApplicationTests {

	@Test
	void contextLoads() {
	}

}
