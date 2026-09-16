package com.staysupplier.search;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.staysupplier.mapping.HotelMapping;
import com.staysupplier.mapping.HotelMappingMapper;
import com.staysupplier.mapping.MappingRegistry;
import com.staysupplier.mapping.RoomTypeMapping;
import com.staysupplier.mapping.RoomTypeMappingMapper;
import com.staysupplier.search.StaySearchResponse.RoomType;
import com.staysupplier.search.StaySearchResponse.Stay;
import com.staysupplier.search.StaySearchResponse.SupplierFailure;
import com.staysupplier.stay.SupplierFetchResult;
import com.staysupplier.stay.SupplierFetchResult.ChunkFailure;
import com.staysupplier.stay.SupplierRoomOffer;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 예제 데이터(Riverside A/B, Namsan A)로 병합·연박 판정·예약 불가 제외·인원 필터·부분 실패를 검증한다.
 */
class StaySearchServiceTest {

	static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 1);

	static final LocalDate CHECK_OUT = LocalDate.of(2026, 9, 4);

	static final SupplierRoomOffer RIVERSIDE_A = new SupplierRoomOffer(Supplier.A, "A-10023", "DLX-TWN", "Deluxe Twin",
			2, 1, 429_000L, "KRW", false);

	/** 9/2 재고 0 → 3박 예약 불가 */
	static final SupplierRoomOffer NAMSAN_A_SOLD_OUT = new SupplierRoomOffer(Supplier.A, "A-10044", "STD-DBL",
			"Standard Double", 2, 0, 302_500L, "KRW", false);

	static final SupplierRoomOffer RIVERSIDE_B = new SupplierRoomOffer(Supplier.B, "B77120", "R-401",
			"Deluxe Twin Room", 2, 1, 452_000L, "KRW", true);

	private final HotelMappingMapper hotelMappingMapper = mock(HotelMappingMapper.class);

	private final RoomTypeMappingMapper roomTypeMappingMapper = mock(RoomTypeMappingMapper.class);

	private final MappingRegistry registry = new MappingRegistry(this.hotelMappingMapper, this.roomTypeMappingMapper);

	@BeforeEach
	void loadMapping() {
		given(this.hotelMappingMapper.findAllActive()).willReturn(List.of(hotel(1L, Supplier.A, "A-10023", "Riverside Hotel Seoul"),
				hotel(2L, Supplier.A, "A-10044", "Namsan Garden Stay"), hotel(3L, Supplier.B, "B77120", "Riverside Hotel Seoul")));
		given(this.roomTypeMappingMapper.findAllActive()).willReturn(List.of(roomType(11L, 1L, "DLX-TWN", "Deluxe Twin", 2),
				roomType(12L, 2L, "STD-DBL", "Standard Double", 2), roomType(13L, 3L, "R-401", "Deluxe Twin Room", null)));
		this.registry.reload();
	}

	private StaySearchService service(StubClient... clients) {
		return new StaySearchService(List.of(clients), this.registry,
				new SearchProperties(30, 1, Duration.ofMillis(1)));
	}

	private static StaySearchRequest request(int adults, boolean includeSoldOut) {
		return new StaySearchRequest(CHECK_IN, CHECK_OUT, adults, 0, false, includeSoldOut);
	}

	@Test
	void mergesSuppliersIntoInternalIdsAndExcludesSoldOutRoomTypes() {
		StubClient a = new StubClient(Supplier.A).willReturn(result(RIVERSIDE_A, NAMSAN_A_SOLD_OUT));
		StubClient b = new StubClient(Supplier.B).willReturn(result(RIVERSIDE_B));

		StaySearchResponse response = service(a, b).search(request(2, false));

		assertThat(response.nights()).isEqualTo(3);
		assertThat(response.failures()).isEmpty();
		assertThat(response.stays()).extracting(Stay::hotelId).containsExactly(1L, 3L);
		Stay riversideA = response.stays().get(0);
		assertThat(riversideA.hotelName()).isEqualTo("Riverside Hotel Seoul");
		assertThat(riversideA.supplier()).isEqualTo(Supplier.A);
		assertThat(riversideA.roomTypes()).singleElement().satisfies(roomType -> {
			assertThat(roomType.roomTypeId()).isEqualTo(11L);
			assertThat(roomType.availableRooms()).isEqualTo(1);
			assertThat(roomType.price().total()).isEqualTo(429_000L);
			assertThat(roomType.price().taxIncluded()).isTrue();
			assertThat(roomType.price().breakfastIncluded()).isFalse();
		});
		assertThat(response.stays().get(1).roomTypes()).singleElement().satisfies(roomType -> {
			assertThat(roomType.roomTypeId()).isEqualTo(13L);
			assertThat(roomType.maxOccupancy()).isEqualTo(2);
			assertThat(roomType.price().breakfastIncluded()).isTrue();
		});
		assertThat(a.lastQuery().hotelCodes()).containsExactly("A-10023", "A-10044");
		assertThat(a.lastQuery().adults()).isEqualTo(2);
	}

	@Test
	void includeSoldOutKeepsRoomTypesWithZeroAvailability() {
		StubClient a = new StubClient(Supplier.A).willReturn(result(RIVERSIDE_A, NAMSAN_A_SOLD_OUT));

		StaySearchResponse response = service(a).search(request(2, true));

		assertThat(response.stays()).extracting(Stay::hotelId).containsExactly(1L, 2L);
		assertThat(response.stays().get(1).roomTypes()).extracting(RoomType::availableRooms).containsExactly(0);
	}

	@Test
	void filtersRoomTypesBelowRequestedOccupancy() {
		StubClient a = new StubClient(Supplier.A).willReturn(result(RIVERSIDE_A));

		StaySearchResponse response = service(a).search(request(3, false));

		assertThat(response.stays()).isEmpty();
	}

	@Test
	void usesMappingOccupancyWhenOfferHasNoneAndDropsWhenBothUnknown() {
		SupplierRoomOffer noOccupancyB = new SupplierRoomOffer(Supplier.B, "B77120", "R-401", "Deluxe Twin Room", null, 1,
				452_000L, "KRW", true);
		SupplierRoomOffer noOccupancyA = new SupplierRoomOffer(Supplier.A, "A-10023", "DLX-TWN", "Deluxe Twin", null, 1,
				429_000L, "KRW", false);

		StaySearchResponse response = service(new StubClient(Supplier.A).willReturn(result(noOccupancyA)),
				new StubClient(Supplier.B).willReturn(result(noOccupancyB))).search(request(2, false));

		// A-10023 은 매핑에 최대 인원 2 가 있어 살고, B77120 은 매핑도 미상이라 응답에서 빠진다
		assertThat(response.stays()).extracting(Stay::hotelId).containsExactly(1L);
		assertThat(response.stays().get(0).roomTypes().get(0).maxOccupancy()).isEqualTo(2);
	}

	@Test
	void partialFailureReturnsOtherSuppliersWithFailureEntry() {
		StubClient a = new StubClient(Supplier.A).willFail(FailureReason.TIMEOUT);
		StubClient b = new StubClient(Supplier.B).willReturn(result(RIVERSIDE_B));

		StaySearchResponse response = service(a, b).search(request(2, false));

		assertThat(response.stays()).extracting(Stay::hotelId).containsExactly(3L);
		assertThat(response.failures()).containsExactly(new SupplierFailure(Supplier.A, FailureReason.TIMEOUT, 2));
		assertThat(a.calls()).isEqualTo(1);
	}

	@Test
	void chunkFailureIsReportedWithAffectedHotelCount() {
		SupplierFetchResult partial = new SupplierFetchResult(List.of(RIVERSIDE_A),
				List.of(new ChunkFailure(Supplier.A, List.of("A-10044"), FailureReason.UNAVAILABLE)));

		StaySearchResponse response = service(new StubClient(Supplier.A).willReturn(partial)).search(request(2, false));

		assertThat(response.stays()).hasSize(1);
		assertThat(response.failures()).containsExactly(new SupplierFailure(Supplier.A, FailureReason.UNAVAILABLE, 1));
	}

	@Test
	void everySupplierFailingIsAnError() {
		StubClient a = new StubClient(Supplier.A).willFail(FailureReason.UNAVAILABLE);
		StubClient b = new StubClient(Supplier.B).willFail(FailureReason.TIMEOUT);

		assertThatThrownBy(() -> service(a, b).search(request(2, false))).isInstanceOf(AllSuppliersFailedException.class)
			.satisfies(ex -> assertThat(((AllSuppliersFailedException) ex).getFailures()).hasSize(2));
	}

	@Test
	void retriesConnectionFailureOnceImmediatelyButNotTimeout() {
		StubClient a = new StubClient(Supplier.A).willFail(FailureReason.CONNECTION).willReturn(result(RIVERSIDE_A));
		StubClient b = new StubClient(Supplier.B).willFail(FailureReason.TIMEOUT).willReturn(result(RIVERSIDE_B));

		StaySearchResponse response = service(a, b).search(request(2, false));

		assertThat(a.calls()).isEqualTo(2);
		assertThat(b.calls()).isEqualTo(1);
		assertThat(response.stays()).extracting(Stay::hotelId).containsExactly(1L);
		assertThat(response.failures()).extracting(SupplierFailure::supplier).containsExactly(Supplier.B);
	}

	@Test
	void skipsOffersForCodesNotInMapping() {
		SupplierRoomOffer unmapped = new SupplierRoomOffer(Supplier.A, "A-99999", "NEW", "New Room", 2, 1, 1000L, "KRW",
				false);

		StaySearchResponse response = service(new StubClient(Supplier.A).willReturn(result(unmapped, RIVERSIDE_A)))
			.search(request(2, false));

		assertThat(response.stays()).extracting(Stay::hotelId).containsExactly(1L);
	}

	@Test
	void rejectsInvalidRequestsBeforeCallingSuppliers() {
		StubClient a = new StubClient(Supplier.A).willReturn(result(RIVERSIDE_A));
		StaySearchService service = service(a);

		assertThatThrownBy(() -> service.search(new StaySearchRequest(CHECK_OUT, CHECK_IN, 2, 0, false, false)))
			.isInstanceOf(InvalidSearchRequestException.class);
		assertThatThrownBy(() -> service.search(new StaySearchRequest(CHECK_IN, CHECK_OUT, 0, 0, false, false)))
			.isInstanceOf(InvalidSearchRequestException.class);
		assertThatThrownBy(() -> service.search(new StaySearchRequest(CHECK_IN, CHECK_IN.plusDays(31), 2, 0, false, false)))
			.isInstanceOf(InvalidSearchRequestException.class);
		assertThat(a.calls()).isZero();
	}

	private static SupplierFetchResult result(SupplierRoomOffer... offers) {
		return new SupplierFetchResult(List.of(offers), List.of());
	}

	private static HotelMapping hotel(Long id, Supplier supplier, String code, String name) {
		HotelMapping mapping = new HotelMapping(supplier, code, name);
		mapping.setId(id);
		return mapping;
	}

	private static RoomTypeMapping roomType(Long id, Long hotelId, String code, String name, Integer maxOccupancy) {
		RoomTypeMapping mapping = new RoomTypeMapping(hotelId, code, name, maxOccupancy);
		mapping.setId(id);
		return mapping;
	}

}
