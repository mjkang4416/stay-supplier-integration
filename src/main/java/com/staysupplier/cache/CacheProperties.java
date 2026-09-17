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
 */
@ConfigurationProperties("cache")
public record CacheProperties(@DefaultValue("true") boolean refreshEnabled, @DefaultValue("5m") Duration refreshInterval,
		@DefaultValue("30") int windowDays, @DefaultValue("3") int ttlMultiplier) {

	public Duration ttl() {
		return this.refreshInterval.multipliedBy(this.ttlMultiplier);
	}

}
