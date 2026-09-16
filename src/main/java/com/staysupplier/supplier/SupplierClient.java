package com.staysupplier.supplier;

import java.util.List;

import reactor.core.publisher.Mono;

import com.staysupplier.stay.AvailabilityQuery;
import com.staysupplier.stay.SupplierFetchResult;
import com.staysupplier.stay.SupplierHotel;

/**
 * 공급사 하나를 호출하고 응답을 표준 형태로 바꾸는 어댑터의 공통 인터페이스.
 * 호출자(크론잡, 검색)는 이 인터페이스와 표준 형태만 보고, 공급사별 요청/응답 형식은 구현체 패키지 밖으로 나가지 않는다.
 * 실패는 종류가 무엇이든 {@link SupplierCallException} 으로 통일한다. 재시도는 호출자가 정한다.
 */
public interface SupplierClient {

	/** 두 공급사 모두 재고·요금 조회 한 번에 받는 숙소 코드 상한 */
	int MAX_HOTEL_CODES_PER_REQUEST = 50;

	Supplier supplier();

	/**
	 * ① 숙소 목록. 공급사가 취급하는 숙소와 객실 타입 전체를 돌려준다.
	 * 필수 값이 빠진 항목은 버리고 로그를 남기며, 정상 0건은 빈 목록이다.
	 */
	Mono<List<SupplierHotel>> fetchHotels();

	/**
	 * ② 재고·요금 조회. 코드 목록을 한도(50개)만큼 잘라 병렬 호출하고 요청 기간 기준으로 정규화한다.
	 * 일부 묶음이 실패하면 결과의 failures 에 담고, 전부 실패하면 예외를 던진다. 깨진 항목은 버리고 로그를 남긴다.
	 */
	Mono<SupplierFetchResult> fetchAvailability(AvailabilityQuery query);

}
