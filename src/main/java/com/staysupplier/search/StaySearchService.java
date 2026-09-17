package com.staysupplier.search;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

import com.staysupplier.cache.AvailabilityCache;
import com.staysupplier.cache.CachedRate;
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
 * 통합 검색. 평소에는 갱신 잡이 미리 채운 Redis 만 읽어 응답한다. 공급사를 직접 부르는 것은 fresh=true(예약 직전 재확인)와
 * Redis 에 값이 없는 숙소(첫 바퀴 전·장애·초기화, 저하 모드)뿐이다. 직접 호출은 인메모리 매핑에서 공급사별 숙소 코드를 꺼내
 * 어댑터를 병렬 호출하고 표준 형태를 내부 식별자로 바꿔 병합한다. 공급사 하나가 실패해도 나머지로 응답하고 실패는 failures 로 드러낸다.
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

	private final AvailabilityCache cache;

	public StaySearchService(List<SupplierClient> clients, MappingRegistry registry, SearchProperties properties,
			AvailabilityCache cache) {
		this.clients = List.copyOf(clients);
		this.registry = registry;
		this.properties = properties;
		this.cache = cache;
	}

	public StaySearchResponse search(StaySearchRequest request) {
		request.validate(this.properties.maxNights());

		Map<Long, List<RoomType>> roomTypesByHotel = new LinkedHashMap<>();
		List<SupplierFailure> failures = new ArrayList<>();

		// 1. Redis 에서 먼저 읽는다. 값이 있는 숙소는 공급사를 부르지 않는다
		Set<Long> missing = new LinkedHashSet<>();
		for (Supplier supplier : Supplier.values()) {
			this.registry.activeHotels(supplier).forEach(hotel -> missing.add(hotel.id()));
		}
		int servedFromCache = 0;
		if (!request.fresh() && !missing.isEmpty()) {
			servedFromCache = readCache(request, missing, roomTypesByHotel, failures);
		}

		// 2. 값이 없는 숙소만 공급사별로 묶어 병렬 호출 (fresh=true 면 전부). 실패는 공급사 단위로 가둔다
		List<SupplierOutcome> outcomes = missing.isEmpty() ? List.of() : Flux.fromIterable(this.clients)
			.flatMap(client -> fetch(client, request, missing))
			.collectList()
			.block();

		// 3. 표준 형태 → 내부 식별자, 인원·예약 불가 필터, 숙소 단위로 병합
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
		if (called > 0 && failedEntirely == called && servedFromCache == 0) {
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
				request.children(), stays, failures, List.of(), servedFromCache == 0);
	}

	/**
	 * Redis 에서 숙소별 (객실 타입 × 숙박일) 필드를 읽어 조립한다. 값이 있는 숙소는 missing 에서 빼고, 읽은 숙소 수를 돌려준다.
	 * Redis 장애면 전부 missing 으로 두어 저하 모드(직접 호출)로 넘어간다.
	 */
	private int readCache(StaySearchRequest request, Set<Long> missing, Map<Long, List<RoomType>> roomTypesByHotel,
			List<SupplierFailure> failures) {
		List<LocalDate> nights = request.checkIn().datesUntil(request.checkOut()).toList();
		List<String> fields = new ArrayList<>();
		Map<Long, HotelEntry> hotels = new LinkedHashMap<>();
		for (Long hotelId : missing) {
			HotelEntry hotel = this.registry.findHotel(hotelId).orElseThrow();
			hotels.put(hotelId, hotel);
			for (RoomTypeEntry roomType : hotel.roomTypes()) {
				nights.forEach(date -> fields.add(AvailabilityCache.field(roomType.id(), date)));
			}
		}
		Map<Long, Map<String, CachedRate>> cached;
		try {
			cached = this.cache.read(missing, fields);
			for (Supplier supplier : Supplier.values()) {
				this.cache.status(supplier).filter(AvailabilityCache.RefreshStatus::failing).ifPresent(status -> failures
					.add(new SupplierFailure(supplier, status.lastFailureReason(), this.registry.activeHotels(supplier).size())));
			}
		}
		catch (RuntimeException ex) {
			log.warn("search cache unavailable, degraded mode: calling suppliers directly ({})", ex.toString());
			return 0;
		}
		int served = 0;
		for (Map.Entry<Long, Map<String, CachedRate>> entry : cached.entrySet()) {
			HotelEntry hotel = hotels.get(entry.getKey());
			for (RoomTypeEntry roomType : hotel.roomTypes()) {
				int availableRooms = Integer.MAX_VALUE;
				long total = 0;
				String currency = null;
				boolean breakfast = false;
				Integer maxOccupancy = null;
				boolean complete = true;
				for (LocalDate date : nights) {
					CachedRate rate = entry.getValue().get(AvailabilityCache.field(roomType.id(), date));
					if (rate == null) {
						complete = false;
						break;
					}
					availableRooms = Math.min(availableRooms, rate.remainingRooms());
					total += rate.nightlyTotal();
					currency = rate.currency();
					breakfast = rate.breakfastIncluded();
					maxOccupancy = rate.maxOccupancy();
				}
				if (!complete) {
					continue; // 이 객실 타입은 이번 창에 값이 없음 (창 밖 날짜 등). 숙소 자체는 캐시로 응답
				}
				SupplierRoomOffer offer = new SupplierRoomOffer(hotel.supplier(), hotel.code(), roomType.code(),
						roomType.name(), maxOccupancy, availableRooms, total, currency, breakfast);
				toRoomType(offer, request).ifPresent(mapped -> roomTypesByHotel
					.computeIfAbsent(mapped.hotelId(), id -> new ArrayList<>())
					.add(mapped.roomType()));
			}
			missing.remove(entry.getKey());
			served++;
		}
		return served;
	}

	private Mono<SupplierOutcome> fetch(SupplierClient client, StaySearchRequest request, Set<Long> hotelIds) {
		Supplier supplier = client.supplier();
		List<String> codes = this.registry.activeHotels(supplier)
			.stream()
			.filter(hotel -> hotelIds.contains(hotel.id()))
			.map(HotelEntry::code)
			.toList();
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
