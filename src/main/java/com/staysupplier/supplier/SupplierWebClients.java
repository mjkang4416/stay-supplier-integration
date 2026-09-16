package com.staysupplier.supplier;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 공급사별로 기본 URL과 타임아웃이 적용된 WebClient 를 보관한다.
 * 어댑터는 여기서 자기 공급사의 WebClient 를 받아 쓰고 직접 만들지 않는다.
 * 모든 공급사의 클라이언트를 기동 시점에 만들어, 설정이 빠진 공급사가 있으면 그때 실패한다.
 */
public class SupplierWebClients {

	public static final String API_KEY_HEADER = "X-Api-Key";

	private final Map<Supplier, WebClient> clients = new EnumMap<>(Supplier.class);

	public SupplierWebClients(WebClient.Builder builder, SupplierProperties properties) {
		for (Supplier supplier : Supplier.values()) {
			SupplierProperties.Endpoint endpoint = properties.endpoint(supplier);
			// Boot 의 readTimeout 은 reactor-netty 에서 응답 타임아웃(responseTimeout)으로 적용된다
			HttpClientSettings settings = HttpClientSettings.defaults()
				.withConnectTimeout(endpoint.connectTimeout())
				.withReadTimeout(endpoint.responseTimeout());
			WebClient client = builder.clone()
				.baseUrl(endpoint.baseUrl())
				.defaultHeader(API_KEY_HEADER, endpoint.apiKey())
				.clientConnector(ClientHttpConnectorBuilder.reactor().build(settings))
				.build();
			clients.put(supplier, client);
		}
	}

	public WebClient of(Supplier supplier) {
		return clients.get(supplier);
	}

}
