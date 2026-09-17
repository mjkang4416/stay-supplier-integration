package com.staysupplier.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 요금·재고 캐시 설정.
 * @param refreshEnabled 갱신 잡 실행 여부 (테스트에서 끈다)
 * @param refreshInterval 갱신 잡이 창 전체를 다시 채우는 주기
 * @param windowDays 오늘부터 며칠치를 채우는지
 * @param ttlMultiplier TTL = 주기 × 이 값. 갱신 잡이 멈췄을 때 옛 값이 남지 않게 하는 안전장치
 * @param rateLimitRetries 갱신 잡이 429 를 받았을 때 물러났다 다시 시도하는 횟수
 * @param rateLimitBackoff 첫 대기. 이후 두 배씩 늘고 60초를 넘지 않는다
 */
@ConfigurationProperties("cache")
public record CacheProperties(@DefaultValue("true") boolean refreshEnabled, @DefaultValue("5m") Duration refreshInterval,
		@DefaultValue("30") int windowDays, @DefaultValue("3") int ttlMultiplier,
		@DefaultValue("5") int rateLimitRetries, @DefaultValue("1s") Duration rateLimitBackoff) {

	public Duration ttl() {
		return this.refreshInterval.multipliedBy(this.ttlMultiplier);
	}

}
