package com.staysupplier.mapping;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import com.staysupplier.mapping.MappingSyncResult.SupplierSyncResult;
import com.staysupplier.stay.SupplierHotel;
import com.staysupplier.stay.SupplierRoomType;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;
import com.staysupplier.supplier.SupplierCallException;
import com.staysupplier.supplier.SupplierClient;

/**
 * 매핑 갱신 잡. 공급사별 숙소 목록을 받아 DB 의 active 집합과 비교해 추가·변경·사라짐(델타)만 반영한다.
 * 공급사 하나가 실패하면 그 공급사만 건너뛰고 기존 매핑을 유지한다. 행은 지우지 않고 active 로만 관리한다.
 */
@Service
public class MappingSyncJob {

	private static final Logger log = LoggerFactory.getLogger(MappingSyncJob.class);

	/** 잠시 뒤 다시 하면 될 수 있는 실패. 잘못된 요청·인증·깨진 응답은 다시 해도 같으므로 재시도하지 않는다 */
	static final Set<FailureReason> RETRYABLE = EnumSet.of(FailureReason.CONNECTION, FailureReason.TIMEOUT,
			FailureReason.SUPPLIER_ERROR, FailureReason.UNAVAILABLE, FailureReason.RATE_LIMITED);

	private final List<SupplierClient> clients;

	private final HotelMappingMapper hotelMappingMapper;

	private final RoomTypeMappingMapper roomTypeMappingMapper;

	private final TransactionTemplate transactionTemplate;

	private final MappingSyncProperties properties;

	public MappingSyncJob(List<SupplierClient> clients, HotelMappingMapper hotelMappingMapper,
			RoomTypeMappingMapper roomTypeMappingMapper, PlatformTransactionManager transactionManager,
			MappingSyncProperties properties) {
		this.clients = List.copyOf(clients);
		this.hotelMappingMapper = hotelMappingMapper;
		this.roomTypeMappingMapper = roomTypeMappingMapper;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.properties = properties;
	}

	public MappingSyncResult run() {
		// 1. 공급사별로 목록을 병렬로 받는다. 실패는 그 공급사 결과로만 남기고 다른 공급사에 영향을 주지 않는다
		List<Fetched> fetched = Flux.fromIterable(this.clients)
			.flatMap(client -> client.fetchHotels()
				.retryWhen(retrySpec(client.supplier()))
				.map(hotels -> Fetched.success(client.supplier(), hotels))
				.onErrorResume(SupplierCallException.class,
						ex -> Mono.just(Fetched.failure(client.supplier(), ex.getReason()))))
			.collectList()
			.block();

		// 2. 성공한 공급사만 트랜잭션 안에서 델타를 반영한다
		List<SupplierSyncResult> results = new ArrayList<>();
		for (Fetched item : fetched) {
			if (!item.succeeded()) {
				log.error("mapping sync skipped supplier={} reason={} (existing mapping kept)", item.supplier(),
						item.failureReason());
				results.add(SupplierSyncResult.failure(item.supplier(), item.failureReason()));
				continue;
			}
			SupplierSyncResult result = this.transactionTemplate
				.execute(status -> applyDelta(item.supplier(), item.hotels()));
			log.info("mapping sync supplier={} hotels(added={}, changed={}, deactivated={}) "
					+ "roomTypes(added={}, changed={}, deactivated={}) deactivationHeld={}",
					result.supplier(), result.hotelsAdded(), result.hotelsChanged(), result.hotelsDeactivated(),
					result.roomTypesAdded(), result.roomTypesChanged(), result.roomTypesDeactivated(),
					result.deactivationHeld());
			results.add(result);
		}
		return new MappingSyncResult(results);
	}

	private Retry retrySpec(Supplier supplier) {
		return Retry.fixedDelay(this.properties.retryAttempts(), this.properties.retryDelay())
			.filter(error -> error instanceof SupplierCallException ex && RETRYABLE.contains(ex.getReason()))
			.doBeforeRetry(signal -> log.warn("mapping sync retry supplier={} attempt={} cause={}", supplier,
					signal.totalRetries() + 1, signal.failure().getMessage()))
			.onRetryExhaustedThrow((spec, signal) -> signal.failure());
	}

	/**
	 * 델타 반영. 이번 목록 vs DB active 집합: 추가 = 목록 − DB (upsert), 변경 = 이름·인원이 다른 것 (upsert),
	 * 사라짐 = DB − 목록 (active=false). 사라짐이 직전 대비 너무 많으면 공급사 쪽 장애로 보고 비활성화만 보류한다.
	 */
	SupplierSyncResult applyDelta(Supplier supplier, List<SupplierHotel> hotels) {
		Map<String, HotelMapping> current = byKey(this.hotelMappingMapper.findActiveBySupplier(supplier),
				HotelMapping::getSupplierHotelCode);
		Set<String> disappeared = new HashSet<>(current.keySet());
		hotels.forEach(hotel -> disappeared.remove(hotel.hotelCode()));
		boolean held = !current.isEmpty()
				&& disappeared.size() > this.properties.maxDisappearRatio() * current.size();

		int hotelsAdded = 0;
		int hotelsChanged = 0;
		int roomTypesAdded = 0;
		int roomTypesChanged = 0;
		int roomTypesDeactivated = 0;
		for (SupplierHotel hotel : hotels) {
			HotelMapping existing = current.get(hotel.hotelCode());
			HotelMapping mapping = new HotelMapping(supplier, hotel.hotelCode(), hotel.hotelName());
			this.hotelMappingMapper.upsert(mapping);
			if (existing == null) {
				hotelsAdded++;
			}
			else if (!existing.getHotelName().equals(hotel.hotelName())) {
				hotelsChanged++;
			}
			RoomTypeDelta delta = applyRoomTypes(mapping.getId(), hotel.roomTypes());
			roomTypesAdded += delta.added();
			roomTypesChanged += delta.changed();
			roomTypesDeactivated += delta.deactivated();
		}

		int hotelsDeactivated = 0;
		if (!disappeared.isEmpty()) {
			if (held) {
				log.error("mapping sync deactivation held supplier={} disappeared={} of active={} (ratio > {})",
						supplier, disappeared.size(), current.size(), this.properties.maxDisappearRatio());
			}
			else {
				hotelsDeactivated = this.hotelMappingMapper.deactivate(supplier, disappeared);
			}
		}
		return new SupplierSyncResult(supplier, true, null, hotelsAdded, hotelsChanged, hotelsDeactivated,
				roomTypesAdded, roomTypesChanged, roomTypesDeactivated, held);
	}

	private RoomTypeDelta applyRoomTypes(Long hotelId, List<SupplierRoomType> roomTypes) {
		Map<String, RoomTypeMapping> current = byKey(this.roomTypeMappingMapper.findActiveByHotelId(hotelId),
				RoomTypeMapping::getSupplierRoomTypeCode);
		Set<String> disappeared = new HashSet<>(current.keySet());
		int added = 0;
		int changed = 0;
		for (SupplierRoomType roomType : roomTypes) {
			disappeared.remove(roomType.roomTypeCode());
			RoomTypeMapping existing = current.get(roomType.roomTypeCode());
			this.roomTypeMappingMapper.upsert(new RoomTypeMapping(hotelId, roomType.roomTypeCode(),
					roomType.roomTypeName(), roomType.maxOccupancy()));
			if (existing == null) {
				added++;
			}
			else if (!existing.getRoomTypeName().equals(roomType.roomTypeName())
					|| !java.util.Objects.equals(existing.getMaxOccupancy(), roomType.maxOccupancy())) {
				changed++;
			}
		}
		int deactivated = disappeared.isEmpty() ? 0 : this.roomTypeMappingMapper.deactivate(hotelId, disappeared);
		return new RoomTypeDelta(added, changed, deactivated);
	}

	private static <T> Map<String, T> byKey(List<T> items, Function<T, String> key) {
		Map<String, T> map = new LinkedHashMap<>();
		items.forEach(item -> map.put(key.apply(item), item));
		return map;
	}

	private record Fetched(Supplier supplier, boolean succeeded, List<SupplierHotel> hotels,
			FailureReason failureReason) {

		static Fetched success(Supplier supplier, List<SupplierHotel> hotels) {
			return new Fetched(supplier, true, hotels, null);
		}

		static Fetched failure(Supplier supplier, FailureReason reason) {
			return new Fetched(supplier, false, List.of(), reason);
		}

	}

	private record RoomTypeDelta(int added, int changed, int deactivated) {
	}

}
