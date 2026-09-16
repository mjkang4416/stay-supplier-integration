package com.staysupplier.supplier;

import java.time.Duration;
import java.util.Map;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * 어댑터 테스트에서 WireMock 서버를 가리키는 WebClient 묶음을 만든다.
 */
public final class SupplierClientTestSupport {

	public static final String API_KEY_A = "key-a";

	public static final String API_KEY_B = "key-b";

	private SupplierClientTestSupport() {
	}

	public static SupplierWebClients webClients(String baseUrl, Duration responseTimeout) {
		SupplierProperties properties = new SupplierProperties(Map.of(
				Supplier.A, new SupplierProperties.Endpoint(baseUrl, API_KEY_A, Duration.ofSeconds(1), responseTimeout),
				Supplier.B, new SupplierProperties.Endpoint(baseUrl, API_KEY_B, Duration.ofSeconds(1), responseTimeout)));
		return new SupplierWebClients(WebClient.builder(), properties);
	}

}
