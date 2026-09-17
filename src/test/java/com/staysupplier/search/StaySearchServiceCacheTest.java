package com.staysupplier.search;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.staysupplier.cache.AvailabilityCache;
import com.staysupplier.cache.CachedRate;
import com.staysupplier.cache.RedisTestSupport;
import com.staysupplier.mapping.HotelMapping;
import com.staysupplier.mapping.HotelMappingMapper;
import com.staysupplier.mapping.MappingRegistry;
import com.staysupplier.mapping.RoomTypeMapping;
import com.staysupplier.mapping.RoomTypeMappingMapper;
import com.staysupplier.search.StaySearchResponse.Stay;
import com.staysupplier.search.StaySearchResponse.SupplierFailure;
import com.staysupplier.stay.SupplierFetchResult;
import com.staysupplier.stay.SupplierRoomOffer;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 검색의 Redis 우선 경로: 값이 있으면 공급사를 부르지 않고, 없는 숙소만 직접 부르며, Redis 장애면 저하 모드로 전부 직접 부른다.
 */
class StaySearchServiceCacheTest {

	static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 1);

	static final LocalDate CHECK_OUT = LocalDate.of(2026, 9, 4);

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
		given(this.hotelMappingMapper.findAllActive()).willReturn(List.of(hotel(1L, Supplier.A, "A-10023", "Riverside Hotel Seoul"),
				hotel(3L, Supplier.B, "B77120", "Riverside Hotel Seoul")));
		given(this.roomTypeMappingMapper.findAllActive()).willReturn(List.of(roomType(11L, 1L, "DLX-TWN", "Deluxe Twin"),
				roomType(13L, 3L, "R-401", "Deluxe Twin Room")));
		this.registry.reload();
	}

	private StaySearchService service(AvailabilityCache availabilityCache, StubClient... clients) {
		return new StaySearchService(List.of(clients), this.registry, new SearchProperties(30, 1, Duration.ofMillis(1)),
				availabilityCache);
	}

	private static StaySearchRequest request(boolean fresh) {
		return new StaySearchRequest(CHECK_IN, CHECK_OUT, 2, 0, fresh, false);
	}

	private static void prefill(long hotelId, long roomTypeId, int... roomsPerNight) {
		Map<String, CachedRate> fields = new java.util.HashMap<>();
		for (int i = 0; i < roomsPerNight.length; i++) {
			fields.put(AvailabilityCache.field(roomTypeId, CHECK_IN.plusDays(i)),
					new CachedRate(roomsPerNight[i], 100_000L + i * 10_000L, "KRW", i == 0, 2));
		}
		cache.write(hotelId, fields, Instant.now());
	}

	@Test
	void servesFromRedisWithoutCallingSuppliersWhenEveryHotelIsCached() {
		prefill(1L, 11L, 3, 1, 5);
		prefill(3L, 13L, 2, 2, 2);
		StubClient a = new StubClient(Supplier.A).willFail(FailureReason.UNAVAILABLE);
		StubClient b = new StubClient(Supplier.B).willFail(FailureReason.UNAVAILABLE);

		StaySearchResponse response = service(cache, a, b).search(request(false));

		assertThat(a.calls()).isZero();
		assertThat(b.calls()).isZero();
		assertThat(response.fresh()).isFalse();
		assertThat(response.failures()).isEmpty();
		assertThat(response.stays()).extracting(Stay::hotelId).containsExactly(1L, 3L);
		assertThat(response.stays().get(0).roomTypes()).singleElement().satisfies(roomType -> {
			assertThat(roomType.availableRooms()).isEqualTo(1);              // min(3, 1, 5)
			assertThat(roomType.price().total()).isEqualTo(330_000L);        // 100,000 + 110,000 + 120,000
			assertThat(roomType.maxOccupancy()).isEqualTo(2);
		});
	}

	@Test
	void callsSuppliersOnlyForHotelsMissingFromRedis() {
		prefill(1L, 11L, 3, 1, 5);
		StubClient a = new StubClient(Supplier.A).willFail(FailureReason.UNAVAILABLE);
		StubClient b = new StubClient(Supplier.B).willReturn(new SupplierFetchResult(List.of(
				new SupplierRoomOffer(Supplier.B, "B77120", "R-401", "Deluxe Twin Room", 2, 1, 452_000L, "KRW", true)), List.of()));

		StaySearchResponse response = service(cache, a, b).search(request(false));

		assertThat(a.calls()).isZero();
		assertThat(b.calls()).isEqualTo(1);
		assertThat(b.lastQuery().hotelCodes()).containsExactly("B77120");
		assertThat(response.fresh()).isFalse();
		assertThat(response.stays()).extracting(Stay::hotelId).containsExactly(1L, 3L);
	}

	@Test
	void freshSkipsRedisAndCallsEverySupplier() {
		prefill(1L, 11L, 3, 1, 5);
		StubClient a = new StubClient(Supplier.A).willReturn(new SupplierFetchResult(List.of(
				new SupplierRoomOffer(Supplier.A, "A-10023", "DLX-TWN", "Deluxe Twin", 2, 1, 429_000L, "KRW", false)), List.of()));
		StubClient b = new StubClient(Supplier.B).willReturn(SupplierFetchResult.empty());

		StaySearchResponse response = service(cache, a, b).search(request(true));

		assertThat(a.calls()).isEqualTo(1);
		assertThat(response.fresh()).isTrue();
		assertThat(response.stays().get(0).roomTypes().get(0).price().total()).isEqualTo(429_000L);
	}

	@Test
	void reportsRefreshFailureOfASupplierFromStatusKey() {
		prefill(1L, 11L, 3, 1, 5);
		prefill(3L, 13L, 2, 2, 2);
		cache.recordStatus(Supplier.A, false, FailureReason.TIMEOUT, Instant.now());

		StaySearchResponse response = service(cache, new StubClient(Supplier.A), new StubClient(Supplier.B))
			.search(request(false));

		assertThat(response.failures()).containsExactly(new SupplierFailure(Supplier.A, FailureReason.TIMEOUT, 1));
		assertThat(response.stays()).hasSize(2);
	}

	@Test
	void redisOutageDegradesToDirectSupplierCalls() {
		AvailabilityCache unreachable = new AvailabilityCache(RedisTestSupport.template("localhost", 1), Duration.ofMinutes(15));
		StubClient a = new StubClient(Supplier.A).willReturn(new SupplierFetchResult(List.of(
				new SupplierRoomOffer(Supplier.A, "A-10023", "DLX-TWN", "Deluxe Twin", 2, 1, 429_000L, "KRW", false)), List.of()));
		StubClient b = new StubClient(Supplier.B).willReturn(SupplierFetchResult.empty());

		StaySearchResponse response = service(unreachable, a, b).search(request(false));

		assertThat(a.calls()).isEqualTo(1);
		assertThat(response.fresh()).isTrue();
		assertThat(response.stays()).extracting(Stay::hotelId).containsExactly(1L);
	}

	private static HotelMapping hotel(Long id, Supplier supplier, String code, String name) {
		HotelMapping mapping = new HotelMapping(supplier, code, name);
		mapping.setId(id);
		return mapping;
	}

	private static RoomTypeMapping roomType(Long id, Long hotelId, String code, String name) {
		RoomTypeMapping mapping = new RoomTypeMapping(hotelId, code, name, 2);
		mapping.setId(id);
		return mapping;
	}

}
