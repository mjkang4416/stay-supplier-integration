package com.staysupplier.supplier;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierPropertiesTest {

	@Test
	void bindsEndpointPerSupplierFromLowerCaseKeys() {
		MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
				"supplier.endpoints.a.base-url", "http://localhost:9090",
				"supplier.endpoints.a.api-key", "key-a",
				"supplier.endpoints.a.connect-timeout", "1s",
				"supplier.endpoints.a.response-timeout", "3s",
				"supplier.endpoints.b.base-url", "http://localhost:9091",
				"supplier.endpoints.b.api-key", "key-b",
				"supplier.endpoints.b.connect-timeout", "500ms",
				"supplier.endpoints.b.response-timeout", "2s"));

		SupplierProperties properties = new Binder(source).bind("supplier", SupplierProperties.class).get();

		assertThat(properties.endpoint(Supplier.A).baseUrl()).isEqualTo("http://localhost:9090");
		assertThat(properties.endpoint(Supplier.A).apiKey()).isEqualTo("key-a");
		assertThat(properties.endpoint(Supplier.A).connectTimeout()).isEqualTo(Duration.ofSeconds(1));
		assertThat(properties.endpoint(Supplier.A).responseTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(properties.endpoint(Supplier.B).baseUrl()).isEqualTo("http://localhost:9091");
		assertThat(properties.endpoint(Supplier.B).connectTimeout()).isEqualTo(Duration.ofMillis(500));
	}

	@Test
	void missingSupplierConfigurationFailsWithTheSupplierName() {
		SupplierProperties properties = new SupplierProperties(Map.of(
				Supplier.A, new SupplierProperties.Endpoint("http://localhost:9090", "key-a", Duration.ofSeconds(1), Duration.ofSeconds(3))));

		assertThatThrownBy(() -> properties.endpoint(Supplier.B))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("supplier.endpoints.b");
	}

}
