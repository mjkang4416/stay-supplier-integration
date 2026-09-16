package com.staysupplier.search;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 통합 검색 설정.
 * @param maxNights 한 번에 검색할 수 있는 최대 박수
 * @param retryAttempts 공급사 직접 호출이 연결 실패·5xx 로 실패했을 때 즉시 다시 시도하는 횟수 (사용자가 기다리므로 짧게)
 * @param retryDelay 재시도 전 대기. 0~200ms 수준
 */
@ConfigurationProperties("search")
public record SearchProperties(@DefaultValue("30") int maxNights, @DefaultValue("1") int retryAttempts,
		@DefaultValue("200ms") Duration retryDelay) {
}
