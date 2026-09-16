package com.staysupplier.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * sync 프로필(K8s CronJob)에서 매핑 갱신 잡을 한 번 돌리고 결과를 종료 코드로 알린다.
 * 공급사 하나라도 실패하면 1 이라, K8s 가 backoffLimit 만큼 잡을 다시 띄운다. upsert 기반이라 재실행은 안전하다.
 */
@Component
@Profile("sync")
public class MappingSyncRunner implements ApplicationRunner, ExitCodeGenerator {

	private static final Logger log = LoggerFactory.getLogger(MappingSyncRunner.class);

	private final MappingSyncJob job;

	private volatile int exitCode = 1;

	public MappingSyncRunner(MappingSyncJob job) {
		this.job = job;
	}

	@Override
	public void run(ApplicationArguments args) {
		MappingSyncResult result = this.job.run();
		this.exitCode = result.allSucceeded() ? 0 : 1;
		log.info("mapping sync finished allSucceeded={} exitCode={}", result.allSucceeded(), this.exitCode);
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

}
