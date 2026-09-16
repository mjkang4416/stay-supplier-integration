package com.staysupplier.stay;

/**
 * 공급사 숙소 목록에서 정규화한 객실 타입. 식별자는 공급사 코드 그대로이며, 내부 식별자 배정은 매핑이 한다.
 * @param roomTypeCode 공급사 객실 타입 코드 (A roomTypeCode, B roomId). 숙소 안에서만 유일
 * @param roomTypeName 객실 타입 이름
 * @param maxOccupancy 최대 수용 인원 (성인 + 아동 합산 기준). 공급사가 주지 않으면 null (미상)
 */
public record SupplierRoomType(String roomTypeCode, String roomTypeName, Integer maxOccupancy) {
}
