package com.staysupplier.mapping;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 웹 앱의 인메모리 매핑 리로드 설정.
 * @param cron 매일 다시 읽는 시각. 크론잡(04:00)이 끝난 뒤인 04:30 이 기본값
 * @param zone cron 의 시간대
 * @param retryDelay 리로드가 실패했을 때 한 번 더 시도하기까지의 대기 시간
 */
@ConfigurationProperties("mapping.reload")
public record MappingReloadProperties(@DefaultValue("0 30 4 * * *") String cron,
		@DefaultValue("Asia/Seoul") String zone, @DefaultValue("5m") Duration retryDelay) {
}
