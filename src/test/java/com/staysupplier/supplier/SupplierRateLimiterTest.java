package com.staysupplier.supplier;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierRateLimiterTest {

	@Test
	void spacesCallsToThePermittedRate() {
		SupplierRateLimiter limiter = new SupplierRateLimiter(10); // 100ms 간격
		Instant start = Instant.now();

		for (int i = 0; i < 4; i++) {
			limiter.throttle(Supplier.A, Mono.just(i)).block();
		}

		// 첫 호출은 즉시, 나머지 세 번은 100ms 씩 → 최소 300ms
		assertThat(Duration.between(start, Instant.now())).isGreaterThanOrEqualTo(Duration.ofMillis(290));
	}

	@Test
	void budgetsAreIndependentPerSupplier() {
		SupplierRateLimiter limiter = new SupplierRateLimiter(2); // 500ms 간격
		Instant start = Instant.now();

		limiter.throttle(Supplier.A, Mono.just(1)).block();
		limiter.throttle(Supplier.B, Mono.just(2)).block();

		assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(400));
	}

	@Test
	void halvesPermitsOnRateLimitedButNotOnOtherFailures() {
		SupplierRateLimiter limiter = new SupplierRateLimiter(4);

		assertThatThrownBy(() -> limiter
			.throttle(Supplier.A, Mono.error(new SupplierCallException(Supplier.A, FailureReason.UNAVAILABLE, null)))
			.block()).isInstanceOf(SupplierCallException.class);
		assertThat(limiter.permitsPerSecond(Supplier.A)).isEqualTo(4);

		assertThatThrownBy(() -> limiter
			.throttle(Supplier.A, Mono.error(new SupplierCallException(Supplier.A, FailureReason.RATE_LIMITED, "E429")))
			.block()).isInstanceOf(SupplierCallException.class);
		assertThat(limiter.permitsPerSecond(Supplier.A)).isEqualTo(2);
		assertThat(limiter.permitsPerSecond(Supplier.B)).isEqualTo(4);
	}

}
