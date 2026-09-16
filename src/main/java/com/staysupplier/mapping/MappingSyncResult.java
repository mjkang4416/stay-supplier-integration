package com.staysupplier.mapping;

import java.util.List;

import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;

/**
 * 크론잡 한 번의 결과. 공급사마다 성공(반영 건수) 또는 실패(원인)를 담는다.
 */
public record MappingSyncResult(List<SupplierSyncResult> results) {

	public MappingSyncResult {
		results = List.copyOf(results);
	}

	public boolean allSucceeded() {
		return this.results.stream().allMatch(SupplierSyncResult::succeeded);
	}

	/**
	 * @param deactivationHeld 사라진 숙소가 너무 많아 비활성화를 보류했는지. 추가·변경은 반영한다
	 */
	public record SupplierSyncResult(Supplier supplier, boolean succeeded, FailureReason failureReason, int hotelsAdded,
			int hotelsChanged, int hotelsDeactivated, int roomTypesAdded, int roomTypesChanged, int roomTypesDeactivated,
			boolean deactivationHeld) {

		public static SupplierSyncResult failure(Supplier supplier, FailureReason reason) {
			return new SupplierSyncResult(supplier, false, reason, 0, 0, 0, 0, 0, 0, false);
		}

	}

}
