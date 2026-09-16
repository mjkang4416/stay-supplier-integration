package com.staysupplier.supplier.a;

import java.time.LocalDate;
import java.util.List;

/**
 * Supplier A 전용 응답 형식. 이 패키지 밖으로 나가지 않는다.
 */
final class SupplierAResponses {

	private SupplierAResponses() {
	}

	record HotelsResponse(List<HotelItem> items) {
	}

	record HotelItem(String hotelCode, String hotelName, List<RoomTypeItem> roomTypes) {
	}

	record RoomTypeItem(String roomTypeCode, String roomTypeName, Integer maxOccupancy) {
	}

	record AvailabilityResponse(List<AvailabilityItem> items) {
	}

	/** 날짜별 1박 단가와 세금이 따로 온다. 결제 금액 = nightlyRate + taxAmount */
	record AvailabilityItem(String hotelCode, String hotelName, String roomTypeCode, String roomTypeName,
			Integer maxOccupancy, Boolean breakfastIncluded, String currency, List<DailyRate> dailyRates) {
	}

	record DailyRate(LocalDate date, Integer remainingRooms, Long nightlyRate, Long taxAmount) {
	}

	/** 실패 응답 본문: { "error": "...", "message": "..." } */
	record ErrorBody(String error, String message) {

		static final ErrorBody EMPTY = new ErrorBody(null, null);

	}

}
