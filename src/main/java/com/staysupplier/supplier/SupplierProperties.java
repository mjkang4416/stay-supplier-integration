package com.staysupplier.supplier;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공급사별 접속 설정. application.properties 의 supplier.endpoints.{a|b}.* 에 바인딩된다.
 * 새 공급사는 {@link Supplier} 값과 같은 이름의 설정 항목을 추가하면 된다.
 */
@ConfigurationProperties("supplier")
public record SupplierProperties(Map<Supplier, Endpoint> endpoints) {

	public SupplierProperties {
		endpoints = (endpoints == null) ? Map.of() : Map.copyOf(endpoints);
	}

	/**
	 * 설정이 없는 공급사는 기동 시점에 바로 드러나도록 예외를 던진다.
	 */
	public Endpoint endpoint(Supplier supplier) {
		Endpoint endpoint = endpoints.get(supplier);
		if (endpoint == null) {
			throw new IllegalStateException("missing configuration: supplier.endpoints." + supplier.name().toLowerCase());
		}
		return endpoint;
	}

	/**
	 * @param baseUrl 공급사 API 기본 URL
	 * @param apiKey 모든 요청의 X-Api-Key 헤더에 넣는 발급 키
	 * @param connectTimeout 연결 타임아웃
	 * @param responseTimeout 응답 타임아웃 (요청을 보낸 뒤 응답을 다 받기까지)
	 */
	public record Endpoint(String baseUrl, String apiKey, Duration connectTimeout, Duration responseTimeout) {
	}

}
