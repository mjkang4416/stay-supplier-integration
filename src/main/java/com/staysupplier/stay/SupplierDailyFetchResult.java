package com.staysupplier.stay;

import java.util.List;

import com.staysupplier.stay.SupplierFetchResult.ChunkFailure;

/**
 * 공급사 하나의 날짜별 재고·요금 조회 결과. 일부 묶음이 실패하면 성공한 항목과 실패한 묶음을 함께 담는다.
 */
public record SupplierDailyFetchResult(List<SupplierDailyOffer> offers, List<ChunkFailure> failures) {

	public SupplierDailyFetchResult {
		offers = List.copyOf(offers);
		failures = List.copyOf(failures);
	}

	public static SupplierDailyFetchResult empty() {
		return new SupplierDailyFetchResult(List.of(), List.of());
	}

}
