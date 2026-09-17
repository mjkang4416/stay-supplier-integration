package com.staysupplier.cache;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityCacheTest {

	static StringRedisTemplate template;

	static AvailabilityCache cache;

	@BeforeAll
	static void setUp() {
		template = RedisTestSupport.template();
		cache = new AvailabilityCache(template, Duration.ofMinutes(15));
	}

	@Test
	void writesOneHashPerHotelWithTtlAndReadsRangeInOnePipeline() {
		LocalDate d1 = LocalDate.of(2026, 9, 1);
		cache.write(1L, Map.of(AvailabilityCache.field(11L, d1), new CachedRate(3, 132_000L, "KRW", false, 2),
				AvailabilityCache.field(11L, d1.plusDays(1)), new CachedRate(1, 165_000L, "KRW", false, 2)),
				Instant.parse("2026-09-16T04:30:00Z"));

		Map<Long, Map<String, CachedRate>> read = cache.read(List.of(1L, 99L),
				List.of(AvailabilityCache.field(11L, d1), AvailabilityCache.field(11L, d1.plusDays(1)),
						AvailabilityCache.field(11L, d1.plusDays(2))));

		assertThat(read).containsOnlyKeys(1L); // 99 는 키가 없음
		assertThat(read.get(1L).get(AvailabilityCache.field(11L, d1)))
			.isEqualTo(new CachedRate(3, 132_000L, "KRW", false, 2));
		assertThat(read.get(1L).get(AvailabilityCache.field(11L, d1.plusDays(2)))).isNull(); // 창 밖 날짜
		assertThat(template.getExpire("stay:v1:1")).isBetween(14 * 60L, 15 * 60L);
		assertThat(template.opsForHash().get("stay:v1:1", "_refreshedAt")).isEqualTo("2026-09-16T04:30:00Z");
	}

	@Test
	void rewriteReplacesTheWholeHashSoPastDatesDoNotAccumulate() {
		LocalDate d1 = LocalDate.of(2026, 9, 1);
		cache.write(5L, Map.of(AvailabilityCache.field(11L, d1), new CachedRate(3, 1000L, "KRW", false, 2)), Instant.now());
		cache.write(5L, Map.of(AvailabilityCache.field(11L, d1.plusDays(1)), new CachedRate(2, 1100L, "KRW", false, 2)),
				Instant.now());

		assertThat(template.opsForHash().keys("stay:v1:5")).containsExactlyInAnyOrder(
				AvailabilityCache.field(11L, d1.plusDays(1)), "_refreshedAt");
		assertThat(template.getExpire("stay:v1:5")).isBetween(14 * 60L, 15 * 60L);
	}

	@Test
	void encodesValueAsShortDelimitedStringWithOptionalOccupancy() {
		CachedRate unknownOccupancy = new CachedRate(0, 88_000L, "KRW", true, null);
		assertThat(unknownOccupancy.encode()).isEqualTo("0|88000|KRW|1|");
		assertThat(CachedRate.decode("0|88000|KRW|1|")).isEqualTo(unknownOccupancy);
		assertThat(CachedRate.decode("3|132000|KRW|0|2")).isEqualTo(new CachedRate(3, 132_000L, "KRW", false, 2));
	}

	@Test
	void recordsRefreshStatusPerSupplier() {
		cache.recordStatus(Supplier.B, true, null, Instant.parse("2026-09-16T05:00:00Z"));
		assertThat(cache.status(Supplier.B)).get().satisfies(status -> assertThat(status.failing()).isFalse());

		cache.recordStatus(Supplier.B, false, FailureReason.UNAVAILABLE, Instant.parse("2026-09-16T05:05:00Z"));
		assertThat(cache.status(Supplier.B)).get().satisfies(status -> {
			assertThat(status.failing()).isTrue();
			assertThat(status.lastFailureReason()).isEqualTo(FailureReason.UNAVAILABLE);
		});
		assertThat(cache.status(Supplier.A)).isEmpty();
	}

}
