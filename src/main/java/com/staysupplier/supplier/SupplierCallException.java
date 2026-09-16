package com.staysupplier.supplier;

import java.time.Duration;

/**
 * 공급사 호출 실패 하나로 통일한 예외. 어댑터는 실패 종류가 무엇이든 이 예외만 던진다.
 */
public class SupplierCallException extends RuntimeException {

	private final Supplier supplier;

	private final FailureReason reason;

	private final String supplierCode;

	private final Duration retryAfter;

	public SupplierCallException(Supplier supplier, FailureReason reason, String supplierCode) {
		this(supplier, reason, supplierCode, null, null);
	}

	/**
	 * @param supplier 실패한 공급사
	 * @param reason 통일한 원인
	 * @param supplierCode 공급사가 준 원본 코드 (A 의 error, B 의 resultCode). 없으면 null
	 * @param retryAfter 공급사가 Retry-After 로 알려준 대기 시간. 없으면 null
	 * @param cause 원인 예외. 없으면 null
	 */
	public SupplierCallException(Supplier supplier, FailureReason reason, String supplierCode, Duration retryAfter,
			Throwable cause) {
		super(describe(supplier, reason, supplierCode), cause);
		this.supplier = supplier;
		this.reason = reason;
		this.supplierCode = supplierCode;
		this.retryAfter = retryAfter;
	}

	private static String describe(Supplier supplier, FailureReason reason, String supplierCode) {
		String message = "supplier " + supplier + " call failed: " + reason;
		return (supplierCode == null) ? message : message + " (" + supplierCode + ")";
	}

	public Supplier getSupplier() {
		return this.supplier;
	}

	public FailureReason getReason() {
		return this.reason;
	}

	public String getSupplierCode() {
		return this.supplierCode;
	}

	public Duration getRetryAfter() {
		return this.retryAfter;
	}

}
