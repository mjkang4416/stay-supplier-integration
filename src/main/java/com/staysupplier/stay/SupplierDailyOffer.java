package com.staysupplier.stay;

import java.time.LocalDate;

import com.staysupplier.supplier.Supplier;

/**
 * 캐시를 채우기 위한 날짜별 판매 정보. 요청 기간 단위인 {@link SupplierRoomOffer} 와 달리 하루 단위다.
 * @param nightlyTotal 그날 1박 요금, 세금 포함
 * @param maxOccupancy 최대 수용 인원. 공급사가 주지 않으면 null
 */
public record SupplierDailyOffer(Supplier supplier, String hotelCode, String roomTypeCode, Integer maxOccupancy,
		LocalDate date, int remainingRooms, long nightlyTotal, String currency, boolean breakfastIncluded) {
}
