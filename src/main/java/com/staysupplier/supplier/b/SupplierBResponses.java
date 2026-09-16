package com.staysupplier.supplier.b;

import java.time.LocalDate;
import java.util.List;

/**
 * Supplier B 전용 응답 형식. 항상 HTTP 200 이고 resultCode 로 성공/실패를 알린다. 이 패키지 밖으로 나가지 않는다.
 */
final class SupplierBResponses {

	static final String SUCCESS_CODE = "0000";

	private SupplierBResponses() {
	}

	record Envelope<T>(String resultCode, String resultMessage, T data) {
	}

	record PropertiesData(List<PropertyItem> items) {
	}

	record PropertyItem(String propertyId, String propertyName, List<RoomItem> rooms) {
	}

	record RoomItem(String roomId, String roomName, Integer maxOccupancy) {
	}

	record SearchData(List<SearchItem> items) {
	}

	/** 요청 기간의 총액(세금 포함)만 오고 날짜별 요금은 없다. 재고는 날짜별 */
	record SearchItem(String propertyId, String propertyName, String roomId, String roomName, Integer maxOccupancy,
			Boolean breakfastIncluded, String currency, Long totalPrice, Boolean taxIncluded, List<InventoryItem> inventory) {
	}

	record InventoryItem(LocalDate date, Integer remainingRooms) {
	}

}
