package com.staysupplier.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import com.staysupplier.mapping.MappingRegistry;
import com.staysupplier.mapping.MappingRegistry.HotelEntry;
import com.staysupplier.mapping.MappingRegistry.RoomTypeEntry;
import com.staysupplier.search.StaySearchResponse.Price;
import com.staysupplier.search.StaySearchResponse.RoomType;
import com.staysupplier.search.StaySearchResponse.Stay;
import com.staysupplier.search.StaySearchResponse.SupplierFailure;
import com.staysupplier.stay.AvailabilityQuery;
import com.staysupplier.stay.SupplierFetchResult;
import com.staysupplier.stay.SupplierFetchResult.ChunkFailure;
import com.staysupplier.stay.SupplierRoomOffer;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;
import com.staysupplier.supplier.SupplierCallException;
import com.staysupplier.supplier.SupplierClient;

/**
 * 통합 검색: 인메모리 매핑에서 공급사별 숙소 코드를 꺼내 어댑터를 병렬 호출하고, 표준 형태를 내부 식별자로 바꿔 병합한다.
 * 공급사 하나가 실패해도 나머지로 응답하고 실패는 failures 로 드러낸다. 전부 실패하면 예외(503).
 * 요금·재고 캐시(Redis)가 붙으면 이 직접 호출은 fresh=true 와 캐시가 비었을 때만 쓰인다.
 */
@Service
public class StaySearchService {

	private static final Logger log = LoggerFactory.getLogger(StaySearchService.class);

	/** 사용자가 기다리는 경로라 실패가 수 ms 안에 나는 것만 즉시 한 번 더. 타임아웃·429 는 다시 하지 않는다 */
	static final Set<FailureReason> RETRYABLE = EnumSet.of(FailureReason.CONNECTION, FailureReason.UNAVAILABLE,
			FailureReason.SUPPLIER_ERROR);

	private final List<SupplierClient> clients;

	private final MappingRegistry registry;

	private final SearchProperties properties;

	public StaySearchService(List<SupplierClient> clients, MappingRegistry registry, SearchProperties properties) {
		this.clients = List.copyOf(clients);
		this.registry = registry;
		this.properties = properties;
	}

	public StaySearchResponse search(StaySearchRequest request) {
		request.validate(this.properties.maxNights());

		// 1. 공급사별로 active 숙소 코드를 묶어 병렬 호출. 실패는 공급사 단위로 가둔다
		List<SupplierOutcome> outcomes = Flux.fromIterable(this.clients)
			.flatMap(client -> fetch(client, request))
			.collectList()
			.block();

		// 2. 표준 형태 → 내부 식별자, 인원·예약 불가 필터, 숙소 단위로 병합
		Map<Long, List<RoomType>> roomTypesByHotel = new LinkedHashMap<>();
		List<SupplierFailure> failures = new ArrayList<>();
		int called = 0;
		int failedEntirely = 0;
		for (SupplierOutcome outcome : outcomes) {
			if (outcome.skipped()) {
				continue;
			}
			called++;
			if (outcome.failure() != null) {
				failedEntirely++;
				failures.add(new SupplierFailure(outcome.supplier(), outcome.failure(), outcome.hotelCount()));
				continue;
			}
			for (ChunkFailure chunk : outcome.result().failures()) {
				failures.add(new SupplierFailure(outcome.supplier(), chunk.reason(), chunk.hotelCodes().size()));
			}
			for (SupplierRoomOffer offer : outcome.result().offers()) {
				toRoomType(offer, request).ifPresent(roomType -> roomTypesByHotel
					.computeIfAbsent(roomType.hotelId(), id -> new ArrayList<>())
					.add(roomType.roomType()));
			}
		}
		if (called > 0 && failedEntirely == called) {
			throw new AllSuppliersFailedException(failures);
		}

		List<Stay> stays = new ArrayList<>();
		roomTypesByHotel.keySet().stream().sorted().forEach(hotelId -> {
			List<RoomType> roomTypes = roomTypesByHotel.get(hotelId);
			roomTypes.sort((a, b) -> Long.compare(a.roomTypeId(), b.roomTypeId()));
			HotelEntry hotel = this.registry.findHotel(hotelId).orElseThrow();
			stays.add(new Stay(hotel.id(), hotel.name(), hotel.supplier(), roomTypes));
		});
		return new StaySearchResponse(request.checkIn(), request.checkOut(), request.nights(), request.adults(),
				request.children(), stays, failures, List.of(), true);
	}

	private Mono<SupplierOutcome> fetch(SupplierClient client, StaySearchRequest request) {
		Supplier supplier = client.supplier();
		List<String> codes = this.registry.activeHotels(supplier).stream().map(HotelEntry::code).toList();
		if (codes.isEmpty()) {
			return Mono.just(SupplierOutcome.skipped(supplier));
		}
		AvailabilityQuery query = new AvailabilityQuery(codes, request.checkIn(), request.checkOut(), request.adults(),
				request.children());
		return client.fetchAvailability(query)
			.retryWhen(retrySpec(supplier))
			.map(result -> SupplierOutcome.success(supplier, codes.size(), result))
			.onErrorResume(SupplierCallException.class, ex -> {
				log.warn("search supplier={} failed reason={} hotels={}", supplier, ex.getReason(), codes.size());
				return Mono.just(SupplierOutcome.failure(supplier, codes.size(), ex.getReason()));
			});
	}

	private Retry retrySpec(Supplier supplier) {
		return Retry.fixedDelay(this.properties.retryAttempts(), this.properties.retryDelay())
			.filter(error -> error instanceof SupplierCallException ex && RETRYABLE.contains(ex.getReason()))
			.doBeforeRetry(signal -> log.info("search retry supplier={} cause={}", supplier, signal.failure().getMessage()))
			.onRetryExhaustedThrow((spec, signal) -> signal.failure());
	}

	/**
	 * 공급사 코드를 내부 식별자로 바꾸고 인원·예약 불가 필터를 적용한다. 매핑에 없는 코드(지난 새벽 이후 추가된 상품)는 버린다.
	 */
	private Optional<MappedRoomType> toRoomType(SupplierRoomOffer offer, StaySearchRequest request) {
		Optional<HotelEntry> hotel = this.registry.findHotel(offer.supplier(), offer.hotelCode());
		if (hotel.isEmpty()) {
			log.warn("search offer skipped: unmapped hotel supplier={} hotelCode={}", offer.supplier(), offer.hotelCode());
			return Optional.empty();
		}
		Optional<RoomTypeEntry> roomType = this.registry.findRoomType(hotel.get().id(), offer.roomTypeCode());
		if (roomType.isEmpty()) {
			log.warn("search offer skipped: unmapped roomType supplier={} hotelCode={} roomTypeCode={}", offer.supplier(),
					offer.hotelCode(), offer.roomTypeCode());
			return Optional.empty();
		}
		// 최대 인원은 응답 계약상 필수: ② 응답 값 → 매핑 값. 둘 다 없으면 추측하지 않고 뺀다
		Integer maxOccupancy = (offer.maxOccupancy() != null) ? offer.maxOccupancy() : roomType.get().maxOccupancy();
		if (maxOccupancy == null) {
			log.warn("search offer skipped: maxOccupancy unknown supplier={} hotelCode={} roomTypeCode={}",
					offer.supplier(), offer.hotelCode(), offer.roomTypeCode());
			return Optional.empty();
		}
		if (maxOccupancy < request.guests()) {
			return Optional.empty();
		}
		if (offer.availableRooms() == 0 && !request.includeSoldOut()) {
			return Optional.empty();
		}
		Price price = new Price(offer.totalPrice(), offer.currency(), true, offer.breakfastIncluded());
		return Optional.of(new MappedRoomType(hotel.get().id(), new RoomType(roomType.get().id(),
				roomType.get().name(), maxOccupancy, offer.availableRooms(), price)));
	}

	private record MappedRoomType(long hotelId, RoomType roomType) {
	}

	private record SupplierOutcome(Supplier supplier, boolean skipped, int hotelCount, SupplierFetchResult result,
			FailureReason failure) {

		static SupplierOutcome skipped(Supplier supplier) {
			return new SupplierOutcome(supplier, true, 0, null, null);
		}

		static SupplierOutcome success(Supplier supplier, int hotelCount, SupplierFetchResult result) {
			return new SupplierOutcome(supplier, false, hotelCount, result, null);
		}

		static SupplierOutcome failure(Supplier supplier, int hotelCount, FailureReason reason) {
			return new SupplierOutcome(supplier, false, hotelCount, null, reason);
		}

	}

}
