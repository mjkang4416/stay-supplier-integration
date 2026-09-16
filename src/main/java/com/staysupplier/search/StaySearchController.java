package com.staysupplier.search;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 통합 검색 API. 고객은 날짜와 인원으로 검색하고, 대상은 자사가 보유한 숙소 전체다.
 */
@RestController
@RequestMapping("/api/v1/stays")
@Tag(name = "Stay search", description = "여러 공급사의 재고·요금을 하나의 표준 모델로 병합해 돌려준다")
public class StaySearchController {

	private final StaySearchService searchService;

	public StaySearchController(StaySearchService searchService) {
		this.searchService = searchService;
	}

	@GetMapping("/search")
	@Operation(summary = "숙박 상품 통합 검색",
			description = "체크인·체크아웃(체크아웃일 미포함)과 인원으로 보유 숙소 전체를 검색한다. "
					+ "일부 공급사가 실패하면 200 과 failures 로 알리고, 전부 실패하면 503 이다.")
	public StaySearchResponse search(
			@Parameter(description = "체크인일 (YYYY-MM-DD)", example = "2026-09-01") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
			@Parameter(description = "체크아웃일 (YYYY-MM-DD, 숙박일에 포함되지 않음)", example = "2026-09-04") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
			@Parameter(description = "성인 수 (1 이상)", example = "2") @RequestParam int adults,
			@Parameter(description = "아동 수 (0 이상)", example = "0") @RequestParam(defaultValue = "0") int children,
			@Parameter(description = "true 면 캐시를 건너뛰고 공급사에 직접 묻는다 (예약 직전 재확인)") @RequestParam(defaultValue = "false") boolean fresh,
			@Parameter(description = "true 면 예약 불가(예약 가능 객실 0) 객실 타입도 포함한다") @RequestParam(defaultValue = "false") boolean includeSoldOut) {
		return this.searchService
			.search(new StaySearchRequest(checkIn, checkOut, adults, children, fresh, includeSoldOut));
	}

}
