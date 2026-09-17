package com.staysupplier.search;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;

/**
 * 통합 검색 응답. 숙소 단위로 묶고 안에 객실 타입을 둔다. 식별자와 이름은 자사 매핑의 것이다.
 * @param stays 예약 가능한 숙소 (hotelId 오름차순). includeSoldOut 이 아니면 예약 불가 객실 타입과 빈 숙소는 빠진다
 * @param failures 실패한 공급사와 원인. 일부 실패여도 상태 코드는 200 이다
 * @param source 캐시에서 한 숙소라도 읽었으면 cache, 전부 공급사에 직접 물어 만든 응답이면 supplier
 */
public record StaySearchResponse(LocalDate checkIn, LocalDate checkOut, int nights, int adults, int children,
		List<Stay> stays, List<SupplierFailure> failures, Source source) {

	/** 응답이 어디서 왔는지. JSON 에는 소문자로 나간다 */
	public enum Source {

		CACHE, SUPPLIER;

		@JsonValue
		public String value() {
			return name().toLowerCase();
		}

	}

	public record Stay(long hotelId, String hotelName, Supplier supplier, List<RoomType> roomTypes) {
	}

	/**
	 * @param maxOccupancy 객실 1실 최대 수용 인원 (성인+아동). 값이 없는 객실 타입은 응답에 넣지 않으므로 항상 있다
	 * @param availableRooms 요청 기간 전체를 예약할 수 있는 객실 수. 0 이면 예약 불가
	 */
	public record RoomType(long roomTypeId, String roomTypeName, int maxOccupancy, int availableRooms, Price price) {
	}

	/**
	 * @param total 요청 기간 총액, 세금 포함. 통화의 최소 단위 정수
	 */
	public record Price(long total, String currency, boolean taxIncluded, boolean breakfastIncluded) {
	}

	/**
	 * @param affectedHotels 이 실패로 값을 받지 못한 숙소 수
	 */
	public record SupplierFailure(Supplier supplier, FailureReason reason, int affectedHotels) {
	}

}
