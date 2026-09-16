package com.staysupplier.supplier;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierWebClientsTest {

	private static final SupplierProperties.Endpoint ENDPOINT =
			new SupplierProperties.Endpoint("http://localhost:9090", "key", Duration.ofSeconds(1), Duration.ofSeconds(3));

	@Test
	void buildsOneClientPerSupplier() {
		SupplierProperties properties = new SupplierProperties(Map.of(Supplier.A, ENDPOINT, Supplier.B, ENDPOINT));

		SupplierWebClients clients = new SupplierWebClients(WebClient.builder(), properties);

		assertThat(clients.of(Supplier.A)).isNotNull();
		assertThat(clients.of(Supplier.B)).isNotNull();
		assertThat(clients.of(Supplier.A)).isNotSameAs(clients.of(Supplier.B));
	}

	@Test
	void failsAtConstructionWhenAnySupplierHasNoConfiguration() {
		SupplierProperties properties = new SupplierProperties(Map.of(Supplier.A, ENDPOINT));

		assertThatThrownBy(() -> new SupplierWebClients(WebClient.builder(), properties))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("supplier.endpoints.b");
	}

}
