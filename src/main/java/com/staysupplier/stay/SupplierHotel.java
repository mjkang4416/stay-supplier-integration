package com.staysupplier.stay;

import java.util.List;

import com.staysupplier.supplier.Supplier;

/**
 * 공급사 숙소 목록에서 정규화한 숙소. 어댑터 밖으로 나가는 유일한 형태다.
 * @param supplier 출처 공급사
 * @param hotelCode 공급사 숙소 코드 (A hotelCode, B propertyId)
 * @param hotelName 숙소 이름
 * @param roomTypes 객실 타입 목록. 필수 값이 빠진 객실 타입은 어댑터가 이미 걸러냈다
 */
public record SupplierHotel(Supplier supplier, String hotelCode, String hotelName, List<SupplierRoomType> roomTypes) {

	public SupplierHotel {
		roomTypes = List.copyOf(roomTypes);
	}

}
