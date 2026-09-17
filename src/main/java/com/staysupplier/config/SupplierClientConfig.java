package com.staysupplier.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.staysupplier.supplier.SupplierProperties;
import com.staysupplier.supplier.SupplierRateLimiter;
import com.staysupplier.supplier.SupplierWebClients;

/**
 * 공급사 접속 설정을 바인딩하고, 공급사별 WebClient 를 하나의 빈으로 묶는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupplierProperties.class)
public class SupplierClientConfig {

	@Bean
	SupplierWebClients supplierWebClients(WebClient.Builder builder, SupplierProperties properties) {
		return new SupplierWebClients(builder, properties);
	}

	/** 공급사별 호출 예산. 시작값은 설정, 429 를 받으면 절반으로 */
	@Bean
	SupplierRateLimiter supplierRateLimiter(@Value("${supplier.rate-limit.per-second:4}") double permitsPerSecond) {
		return new SupplierRateLimiter(permitsPerSecond);
	}

}
