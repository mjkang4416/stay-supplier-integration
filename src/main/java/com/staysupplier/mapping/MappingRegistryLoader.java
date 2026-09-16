package com.staysupplier.mapping;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 웹 앱에서 인메모리 매핑을 올리는 시점을 담당한다.
 * 기동 시에는 웹 서버가 요청을 받기 전에 동기로 읽고(실패하면 기동 실패), 매일 04:30 에 다시 읽는다.
 * 리로드 실패는 기존 스냅샷을 유지한 채 로그를 남기고 한 번 더 시도한다. sync 프로필(크론잡)에서는 동작하지 않는다.
 */
@Component
@Profile("!sync")
public class MappingRegistryLoader implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(MappingRegistryLoader.class);

	private final MappingRegistry registry;

	private final TaskScheduler taskScheduler;

	private final MappingReloadProperties properties;

	public MappingRegistryLoader(MappingRegistry registry, TaskScheduler taskScheduler,
			MappingReloadProperties properties) {
		this.registry = registry;
		this.taskScheduler = taskScheduler;
		this.properties = properties;
	}

	/** 모든 빈이 만들어진 직후, 웹 서버가 뜨기 전에 실행된다. 예외는 그대로 던져 기동을 실패시킨다 */
	@Override
	public void afterSingletonsInstantiated() {
		this.registry.reload();
	}

	@Scheduled(cron = "${mapping.reload.cron}", zone = "${mapping.reload.zone}")
	public void reloadDaily() {
		if (!tryReload("scheduled")) {
			this.taskScheduler.schedule(() -> tryReload("retry"), Instant.now().plus(this.properties.retryDelay()));
		}
	}

	boolean tryReload(String trigger) {
		try {
			this.registry.reload();
			return true;
		}
		catch (RuntimeException ex) {
			log.error("mapping reload failed trigger={} (existing in-memory mapping kept, loadedAt={})", trigger,
					this.registry.loadedAt(), ex);
			return false;
		}
	}

}
