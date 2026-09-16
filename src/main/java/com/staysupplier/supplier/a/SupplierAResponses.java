package com.staysupplier.supplier.a;

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

	/** 실패 응답 본문: { "error": "...", "message": "..." } */
	record ErrorBody(String error, String message) {

		static final ErrorBody EMPTY = new ErrorBody(null, null);

	}

}
