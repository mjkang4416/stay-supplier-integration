package com.staysupplier.mapping;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 매핑 갱신 잡(크론잡) 설정.
 * @param retryAttempts 공급사 목록 호출이 일시 장애로 실패했을 때 다시 시도하는 횟수
 * @param retryDelay 재시도 사이 고정 대기 시간
 * @param maxDisappearRatio 직전 active 숙소 대비 사라진 비율이 이 값을 넘으면 비활성화를 보류하고 알림만 남긴다
 */
@ConfigurationProperties("mapping.sync")
public record MappingSyncProperties(@DefaultValue("3") int retryAttempts, @DefaultValue("30s") Duration retryDelay,
		@DefaultValue("0.5") double maxDisappearRatio) {
}
