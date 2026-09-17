package com.staysupplier.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Mono;

import com.staysupplier.mapping.HotelMapping;
import com.staysupplier.mapping.HotelMappingMapper;
import com.staysupplier.mapping.MappingRegistry;
import com.staysupplier.mapping.RoomTypeMapping;
import com.staysupplier.mapping.RoomTypeMappingMapper;
import com.staysupplier.stay.AvailabilityQuery;
import com.staysupplier.stay.SupplierDailyFetchResult;
import com.staysupplier.stay.SupplierDailyOffer;
import com.staysupplier.stay.SupplierFetchResult;
import com.staysupplier.stay.SupplierHotel;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;
import com.staysupplier.supplier.SupplierCallException;
import com.staysupplier.supplier.SupplierClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class AvailabilityRefreshJobTest {

	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

	static final LocalDate TODAY = LocalDate.of(2026, 9, 1);

	static StringRedisTemplate template;

	static AvailabilityCache cache;

	private final HotelMappingMapper hotelMappingMapper = mock(HotelMappingMapper.class);

	private final RoomTypeMappingMapper roomTypeMappingMapper = mock(RoomTypeMappingMapper.class);

	private final MappingRegistry registry = new MappingRegistry(this.hotelMappingMapper, this.roomTypeMappingMapper);

	@BeforeAll
	static void setUp() {
		template = RedisTestSupport.template();
		cache = new AvailabilityCache(template, Duration.ofMinutes(15));
	}

	@BeforeEach
	void loadMapping() {
		RedisTestSupport.flushAll();
		given(this.hotelMappingMapper.findAllActive()).willReturn(List.of(hotel(1L, Supplier.A, "A-10023"), hotel(3L, Supplier.B, "B77120")));
		given(this.roomTypeMappingMapper.findAllActive()).willReturn(List.of(roomType(11L, 1L, "DLX-TWN"), roomType(13L, 3L, "R-401")));
		this.registry.reload();
	}

	private AvailabilityRefreshJob job(SupplierClient... clients) {
		return new AvailabilityRefreshJob(List.of(clients), this.registry, cache,
				new CacheProperties(true, Duration.ofMinutes(5), 3, 3), CLOCK);
	}

	@Test
	void fillsOneHashPerHotelForTheWindowAndRecordsSuccess() {
		SupplierClient a = daily(Supplier.A, List.of(
				offer(Supplier.A, "A-10023", "DLX-TWN", TODAY, 3, 132_000L),
				offer(Supplier.A, "A-10023", "DLX-TWN", TODAY.plusDays(1), 1, 165_000L),
				offer(Supplier.A, "A-99999", "NEW", TODAY, 1, 1L)));   // 매핑에 없음 → 버림
		SupplierClient b = daily(Supplier.B, List.of(offer(Supplier.B, "B77120", "R-401", TODAY, 3, 150_000L)));

		job(a, b).refresh();

		assertThat(template.opsForHash().get("stay:v1:1", "11:20260901")).isEqualTo("3|132000|KRW|0|2");
		assertThat(template.opsForHash().get("stay:v1:1", "11:20260902")).isEqualTo("1|165000|KRW|0|2");
		assertThat(template.opsForHash().get("stay:v1:3", "13:20260901")).isEqualTo("3|150000|KRW|0|2");
		assertThat(template.keys("stay:v1:*")).doesNotContain("stay:v1:99999");
		assertThat(template.getExpire("stay:v1:1")).isBetween(14 * 60L, 15 * 60L);
		assertThat(cache.status(Supplier.A)).get().satisfies(status -> assertThat(status.failing()).isFalse());
		assertThat(cache.status(Supplier.B)).get().satisfies(status -> assertThat(status.failing()).isFalse());
	}

	@Test
	void failedSupplierKeepsOtherSupplierAndRecordsFailure() {
		SupplierClient a = failing(Supplier.A, FailureReason.TIMEOUT);
		SupplierClient b = daily(Supplier.B, List.of(offer(Supplier.B, "B77120", "R-401", TODAY, 2, 150_000L)));

		job(a, b).refresh();

		assertThat(template.hasKey("stay:v1:1")).isFalse();
		assertThat(template.opsForHash().get("stay:v1:3", "13:20260901")).isEqualTo("2|150000|KRW|0|2");
		assertThat(cache.status(Supplier.A)).get().satisfies(status -> {
			assertThat(status.failing()).isTrue();
			assertThat(status.lastFailureReason()).isEqualTo(FailureReason.TIMEOUT);
		});
	}

	private static SupplierDailyOffer offer(Supplier supplier, String hotelCode, String roomTypeCode, LocalDate date,
			int rooms, long nightly) {
		return new SupplierDailyOffer(supplier, hotelCode, roomTypeCode, 2, date, rooms, nightly, "KRW", false);
	}

	private static SupplierClient daily(Supplier supplier, List<SupplierDailyOffer> offers) {
		return new FixedClient(supplier, Mono.just(new SupplierDailyFetchResult(offers, List.of())));
	}

	private static SupplierClient failing(Supplier supplier, FailureReason reason) {
		return new FixedClient(supplier, Mono.error(new SupplierCallException(supplier, reason, null)));
	}

	private record FixedClient(Supplier supplier, Mono<SupplierDailyFetchResult> daily) implements SupplierClient {

		@Override
		public Mono<List<SupplierHotel>> fetchHotels() {
			return Mono.just(List.of());
		}

		@Override
		public Mono<SupplierFetchResult> fetchAvailability(AvailabilityQuery query) {
			return Mono.just(SupplierFetchResult.empty());
		}

		@Override
		public Mono<SupplierDailyFetchResult> fetchDailyAvailability(List<String> hotelCodes, LocalDate from, LocalDate to) {
			return this.daily;
		}

	}

	private static HotelMapping hotel(Long id, Supplier supplier, String code) {
		HotelMapping mapping = new HotelMapping(supplier, code, code);
		mapping.setId(id);
		return mapping;
	}

	private static RoomTypeMapping roomType(Long id, Long hotelId, String code) {
		RoomTypeMapping mapping = new RoomTypeMapping(hotelId, code, code, 2);
		mapping.setId(id);
		return mapping;
	}

}
