package com.staysupplier.cache;

import com.redis.testcontainers.RedisContainer;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 JVM 에서 Redis 컨테이너 하나를 띄워 공유한다(싱글턴 컨테이너). 클래스마다 새로 띄우면 준비되기 전에 붙어 실패할 수 있고,
 * 컨테이너 종료는 JVM 이 끝날 때 Testcontainers(ryuk)가 한다. 테스트는 시작할 때 flushAll 로 상태를 비운다.
 */
public final class RedisTestSupport {

	private static RedisContainer container;

	private static StringRedisTemplate template;

	private RedisTestSupport() {
	}

	public static synchronized RedisContainer container() {
		if (container == null) {
			container = new RedisContainer(DockerImageName.parse("redis:7.4-alpine"))
				.waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
			container.start();
		}
		return container;
	}

	public static synchronized StringRedisTemplate template() {
		if (template == null) {
			RedisContainer redis = container();
			template = template(redis.getHost(), redis.getMappedPort(6379));
			template.getConnectionFactory().getConnection().ping();
		}
		return template;
	}

	public static StringRedisTemplate template(String host, int port) {
		LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
		factory.afterPropertiesSet();
		factory.start();
		StringRedisTemplate redisTemplate = new StringRedisTemplate(factory);
		redisTemplate.afterPropertiesSet();
		return redisTemplate;
	}

	public static void flushAll() {
		template().getConnectionFactory().getConnection().serverCommands().flushAll();
	}

}
