package com.staysupplier.stay;

import com.staysupplier.supplier.Supplier;

/**
 * 공급사 재고·요금 응답을 요청 기간 기준으로 정규화한 객실 타입 하나의 판매 정보.
 * 식별자는 공급사 코드 그대로이고 내부 식별자 변환은 호출자가 매핑으로 한다.
 * @param roomTypeName 공급사가 준 객실 타입 이름. 없으면 null (매핑의 이름을 쓴다)
 * @param maxOccupancy 최대 수용 인원. 공급사가 주지 않으면 null (미상)
 * @param availableRooms 요청 기간 전체를 예약할 수 있는 객실 수 = 날짜별 재고의 최솟값. 0이면 예약 불가
 * @param totalPrice 요청 기간 총액, 세금 포함. 통화의 최소 단위 정수
 * @param breakfastIncluded 요금에 조식이 포함되는지
 */
public record SupplierRoomOffer(Supplier supplier, String hotelCode, String roomTypeCode, String roomTypeName,
		Integer maxOccupancy, int availableRooms, long totalPrice, String currency, boolean breakfastIncluded) {
}
