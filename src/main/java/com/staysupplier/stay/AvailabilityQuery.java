package com.staysupplier.stay;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 재고·요금 조회 조건. 공급사 코드 목록은 어댑터가 자기 한도(요청당 50개)에 맞춰 나눈다.
 * @param hotelCodes 조회할 공급사 숙소 코드
 * @param checkIn 체크인일
 * @param checkOut 체크아웃일 (숙박일에 포함되지 않음)
 * @param adults 성인 수
 * @param children 아동 수
 */
public record AvailabilityQuery(List<String> hotelCodes, LocalDate checkIn, LocalDate checkOut, int adults,
		int children) {

	public AvailabilityQuery {
		hotelCodes = List.copyOf(hotelCodes);
	}

	/** 숙박일 수. 체크인일부터 체크아웃 전날까지 */
	public int nights() {
		return (int) ChronoUnit.DAYS.between(this.checkIn, this.checkOut);
	}

}
