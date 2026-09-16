package com.staysupplier.search;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 검색 조건. 안내 문서의 최소 계약(checkIn, checkOut, adults, children)에 선택 플래그 둘을 더했다.
 * @param fresh true 면 캐시를 건너뛰고 공급사에 직접 묻는다 (예약 직전 재확인)
 * @param includeSoldOut true 면 예약 가능 객실 수가 0인 객실 타입도 응답에 넣는다
 */
public record StaySearchRequest(LocalDate checkIn, LocalDate checkOut, int adults, int children, boolean fresh,
		boolean includeSoldOut) {

	public int nights() {
		return (int) ChronoUnit.DAYS.between(this.checkIn, this.checkOut);
	}

	public int guests() {
		return this.adults + this.children;
	}

	/**
	 * 공급사를 부르기 전에 요청 자체를 거른다. 어긋나면 400 이다.
	 */
	public void validate(int maxNights) {
		if (this.checkIn == null || this.checkOut == null) {
			throw new InvalidSearchRequestException("checkIn and checkOut are required (YYYY-MM-DD)");
		}
		if (!this.checkOut.isAfter(this.checkIn)) {
			throw new InvalidSearchRequestException("checkOut must be after checkIn");
		}
		if (nights() > maxNights) {
			throw new InvalidSearchRequestException("stay must be at most " + maxNights + " nights");
		}
		if (this.adults < 1) {
			throw new InvalidSearchRequestException("adults must be at least 1");
		}
		if (this.children < 0) {
			throw new InvalidSearchRequestException("children must be 0 or more");
		}
	}

}
