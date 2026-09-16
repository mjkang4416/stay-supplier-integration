package com.staysupplier.search;

import java.util.List;

import com.staysupplier.search.StaySearchResponse.SupplierFailure;

/** 조회 대상 공급사가 전부 실패해 돌려줄 결과가 없음 (503). 빈 200 은 "예약 가능한 숙소가 없다"로 오해되므로 구분한다 */
public class AllSuppliersFailedException extends RuntimeException {

	private final List<SupplierFailure> failures;

	public AllSuppliersFailedException(List<SupplierFailure> failures) {
		super("every supplier failed: " + failures);
		this.failures = List.copyOf(failures);
	}

	public List<SupplierFailure> getFailures() {
		return this.failures;
	}

}
