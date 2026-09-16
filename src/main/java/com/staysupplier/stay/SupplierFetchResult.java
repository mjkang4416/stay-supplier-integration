package com.staysupplier.stay;

import java.util.List;

import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;

/**
 * 공급사 하나의 재고·요금 조회 결과. 묶음(요청당 50개) 여러 개 중 일부가 실패하면 성공한 항목과 실패한 묶음을 함께 담는다.
 * 모든 묶음이 실패하면 어댑터는 결과 대신 예외를 던진다.
 */
public record SupplierFetchResult(List<SupplierRoomOffer> offers, List<ChunkFailure> failures) {

	public SupplierFetchResult {
		offers = List.copyOf(offers);
		failures = List.copyOf(failures);
	}

	public static SupplierFetchResult empty() {
		return new SupplierFetchResult(List.of(), List.of());
	}

	public boolean hasFailures() {
		return !this.failures.isEmpty();
	}

	/**
	 * @param hotelCodes 실패한 묶음에 들어 있던 숙소 코드. 이 숙소들의 값은 이번 결과에 없다
	 */
	public record ChunkFailure(Supplier supplier, List<String> hotelCodes, FailureReason reason) {

		public ChunkFailure {
			hotelCodes = List.copyOf(hotelCodes);
		}

	}

}
