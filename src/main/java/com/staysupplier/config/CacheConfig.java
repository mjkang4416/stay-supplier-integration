package com.staysupplier.config;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import com.staysupplier.cache.AvailabilityCache;
import com.staysupplier.cache.AvailabilityRefreshJob;
import com.staysupplier.cache.CacheProperties;
import com.staysupplier.mapping.MappingRegistry;
import com.staysupplier.supplier.SupplierClient;

/**
 * 요금·재고 캐시 구성. 갱신 잡은 웹 앱에서만 돌고(sync 프로필 제외), 테스트는 cache.refresh-enabled=false 로 끈다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

	private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

	@Bean
	Clock clock() {
		return Clock.systemDefaultZone();
	}

	@Bean
	AvailabilityCache availabilityCache(StringRedisTemplate redis, CacheProperties properties) {
		return new AvailabilityCache(redis, properties.ttl());
	}

	@Bean
	@Profile("!sync")
	@ConditionalOnProperty(name = "cache.refresh-enabled", havingValue = "true", matchIfMissing = true)
	RefreshScheduler refreshScheduler(List<SupplierClient> clients, MappingRegistry registry, AvailabilityCache cache,
			CacheProperties properties, Clock clock) {
		return new RefreshScheduler(new AvailabilityRefreshJob(clients, registry, cache, properties, clock));
	}

	/** 기동 직후 한 번, 그 뒤 refresh-interval 마다. 첫 바퀴 전의 검색은 저하 모드(직접 호출)로 응답한다 */
	public static class RefreshScheduler {

		private final AvailabilityRefreshJob job;

		RefreshScheduler(AvailabilityRefreshJob job) {
			this.job = job;
		}

		@Scheduled(fixedDelayString = "${cache.refresh-interval}", initialDelayString = "PT0S")
		public void run() {
			try {
				this.job.refresh();
			}
			catch (RuntimeException ex) {
				log.error("cache refresh round failed", ex);
			}
		}

	}

}
