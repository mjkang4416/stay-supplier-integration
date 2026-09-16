package com.staysupplier.supplier.b;

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

}
