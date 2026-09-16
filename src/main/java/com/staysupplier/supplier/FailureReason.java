package com.staysupplier.supplier;

/**
 * 공급사 호출 실패를 우리 기준으로 통일한 원인.
 * A 는 HTTP 상태 코드로, B 는 HTTP 200 + 본문 resultCode 로 실패를 알리지만 밖에서는 이 값만 본다.
 */
public enum FailureReason {

	/** 잘못된 요청. A 400, B E400. 다시 보내도 같으므로 재시도하지 않는다 */
	INVALID_REQUEST,

	/** 인증 실패. A 401, B E401. 발급 키 설정 문제 */
	UNAUTHORIZED,

	/** 호출 한도 초과. A 429, B E429 */
	RATE_LIMITED,

	/** 공급사 내부 오류. A 500, B E500 */
	SUPPLIER_ERROR,

	/** 일시적 장애. A 503, B E503 */
	UNAVAILABLE,

	/** 응답 타임아웃 */
	TIMEOUT,

	/** 연결 실패 (연결 거부, 연결 타임아웃, DNS) */
	CONNECTION,

	/** 본문이 스펙과 다름 (파싱 실패, 필수 구조 누락, 모르는 resultCode). 정상 0건과 구분한다 */
	INVALID_RESPONSE

}
