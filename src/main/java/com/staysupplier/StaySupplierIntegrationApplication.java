package com.staysupplier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 기본 프로필은 웹 앱(검색 API, 인메모리 매핑). sync 프로필은 매핑 갱신 잡을 한 번 돌리고 종료 코드로 결과를 알린다.
 */
@SpringBootApplication
public class StaySupplierIntegrationApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(StaySupplierIntegrationApplication.class, args);
		if (context.getEnvironment().matchesProfiles("sync")) {
			System.exit(SpringApplication.exit(context));
		}
	}

}
