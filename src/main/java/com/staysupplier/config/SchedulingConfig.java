package com.staysupplier.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 웹 앱의 스케줄(매핑 리로드 등). 크론잡(sync 프로필)은 한 번 실행하고 끝나므로 스케줄을 켜지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!sync")
public class SchedulingConfig {

}
