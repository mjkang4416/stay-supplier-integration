package com.staysupplier.supplier;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 공급사별 호출 예산. 초당 허용 횟수를 간격으로 바꿔 호출을 고르게 낸다 (공급사당 초당 4회면 250ms 간격).
 * 429(RATE_LIMITED)를 받으면 그 공급사의 허용 횟수를 절반으로 줄인다. 올리는 것은 자동이 아니라 설정값으로 사람이 한다.
 */
public class SupplierRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(SupplierRateLimiter.class);

	private final Map<Supplier, Slot> slots = new EnumMap<>(Supplier.class);

	public SupplierRateLimiter(double permitsPerSecond) {
		for (Supplier supplier : Supplier.values()) {
			this.slots.put(supplier, new Slot(permitsPerSecond));
		}
	}

	/** 호출을 예산에 맞춰 늦춘다. 이미 통일된 429 예외가 올라오면 허용 횟수를 절반으로 */
	public <T> Mono<T> throttle(Supplier supplier, Mono<T> call) {
		Slot slot = this.slots.get(supplier);
		return Mono.defer(() -> {
			Duration wait = slot.reserve();
			return wait.isZero() ? call : Mono.delay(wait).then(call);
		}).doOnError(SupplierCallException.class, ex -> {
			if (ex.getReason() == FailureReason.RATE_LIMITED) {
				double halved = slot.halve();
				log.warn("supplier={} rate limited (429): permits per second halved to {}", supplier, halved);
			}
		});
	}

	public double permitsPerSecond(Supplier supplier) {
		return this.slots.get(supplier).permitsPerSecond;
	}

	private static final class Slot {

		private volatile double permitsPerSecond;

		private long nextSlotNanos = System.nanoTime();

		Slot(double permitsPerSecond) {
			this.permitsPerSecond = permitsPerSecond;
		}

		synchronized Duration reserve() {
			long now = System.nanoTime();
			long interval = (long) (1_000_000_000L / this.permitsPerSecond);
			long slot = Math.max(now, this.nextSlotNanos);
			this.nextSlotNanos = slot + interval;
			return Duration.ofNanos(Math.max(0, slot - now));
		}

		synchronized double halve() {
			this.permitsPerSecond = Math.max(0.5, this.permitsPerSecond / 2);
			return this.permitsPerSecond;
		}

	}

}
