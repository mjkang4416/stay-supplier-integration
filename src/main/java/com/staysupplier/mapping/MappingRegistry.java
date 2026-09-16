package com.staysupplier.mapping;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.staysupplier.supplier.Supplier;

/**
 * 검색이 읽는 인메모리 매핑. DB 의 active 매핑을 통째로 올려 두고, 검색은 DB 를 읽지 않는다.
 * 기동 시 한 번 올리고 매일 크론잡이 끝난 뒤(04:30) 다시 올린다. 교체는 스냅샷 단위라 읽는 쪽은 항상 일관된 상태를 본다.
 */
@Component
public class MappingRegistry {

	private static final Logger log = LoggerFactory.getLogger(MappingRegistry.class);

	private final HotelMappingMapper hotelMappingMapper;

	private final RoomTypeMappingMapper roomTypeMappingMapper;

	private volatile Snapshot snapshot = Snapshot.EMPTY;

	public MappingRegistry(HotelMappingMapper hotelMappingMapper, RoomTypeMappingMapper roomTypeMappingMapper) {
		this.hotelMappingMapper = hotelMappingMapper;
		this.roomTypeMappingMapper = roomTypeMappingMapper;
	}

	/**
	 * DB 의 active 매핑을 읽어 스냅샷을 통째로 교체한다. 읽기에 실패하면 기존 스냅샷을 유지한 채 예외를 던진다.
	 */
	public void reload() {
		List<HotelMapping> hotels = this.hotelMappingMapper.findAllActive();
		List<RoomTypeMapping> roomTypes = this.roomTypeMappingMapper.findAllActive();

		Map<Long, List<RoomTypeEntry>> roomTypesByHotel = new HashMap<>();
		for (RoomTypeMapping roomType : roomTypes) {
			roomTypesByHotel.computeIfAbsent(roomType.getHotelId(), id -> new ArrayList<>())
				.add(new RoomTypeEntry(roomType.getId(), roomType.getHotelId(), roomType.getSupplierRoomTypeCode(),
						roomType.getRoomTypeName(), roomType.getMaxOccupancy()));
		}
		Map<Supplier, Map<String, HotelEntry>> byCode = new EnumMap<>(Supplier.class);
		Map<Long, HotelEntry> byId = new HashMap<>();
		for (HotelMapping hotel : hotels) {
			HotelEntry entry = new HotelEntry(hotel.getId(), hotel.getSupplier(), hotel.getSupplierHotelCode(),
					hotel.getHotelName(), roomTypesByHotel.getOrDefault(hotel.getId(), List.of()));
			byCode.computeIfAbsent(hotel.getSupplier(), supplier -> new HashMap<>()).put(entry.code(), entry);
			byId.put(entry.id(), entry);
		}
		this.snapshot = new Snapshot(byCode, byId, Instant.now());
		log.info("mapping registry loaded hotels={} roomTypes={}", hotels.size(), roomTypes.size());
	}

	/** 공급사의 active 숙소 (내부 식별자 순). 검색이 공급사별 코드 묶음을 만들 때 쓴다 */
	public List<HotelEntry> activeHotels(Supplier supplier) {
		List<HotelEntry> entries = new ArrayList<>(this.snapshot.byCode().getOrDefault(supplier, Map.of()).values());
		entries.sort((a, b) -> Long.compare(a.id(), b.id()));
		return Collections.unmodifiableList(entries);
	}

	public Optional<HotelEntry> findHotel(Supplier supplier, String hotelCode) {
		return Optional.ofNullable(this.snapshot.byCode().getOrDefault(supplier, Map.of()).get(hotelCode));
	}

	public Optional<HotelEntry> findHotel(long hotelId) {
		return Optional.ofNullable(this.snapshot.byId().get(hotelId));
	}

	public Optional<RoomTypeEntry> findRoomType(long hotelId, String roomTypeCode) {
		return findHotel(hotelId).flatMap(hotel -> hotel.roomTypes()
			.stream()
			.filter(roomType -> roomType.code().equals(roomTypeCode))
			.findFirst());
	}

	public boolean isLoaded() {
		return this.snapshot.loadedAt() != null;
	}

	public Instant loadedAt() {
		return this.snapshot.loadedAt();
	}

	/**
	 * @param roomTypes 이 숙소의 active 객실 타입
	 */
	public record HotelEntry(long id, Supplier supplier, String code, String name, List<RoomTypeEntry> roomTypes) {

		public HotelEntry {
			roomTypes = List.copyOf(roomTypes);
		}

	}

	/**
	 * @param maxOccupancy 최대 수용 인원. 공급사가 주지 않았으면 null (미상)
	 */
	public record RoomTypeEntry(long id, long hotelId, String code, String name, Integer maxOccupancy) {
	}

	private record Snapshot(Map<Supplier, Map<String, HotelEntry>> byCode, Map<Long, HotelEntry> byId,
			Instant loadedAt) {

		static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), null);

	}

}
