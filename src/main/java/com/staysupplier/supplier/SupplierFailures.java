package com.staysupplier.supplier;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.core.codec.CodecException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClientRequestException;

/**
 * 공급사와 무관한 실패(전송 계층, HTTP 상태)를 {@link SupplierCallException} 으로 바꾸는 공통 규칙.
 * 공급사 고유 규칙(B 의 resultCode 등)은 각 공급사 패키지 안에 둔다.
 */
public final class SupplierFailures {

	private SupplierFailures() {
	}

	/**
	 * WebClient 체인에서 올라온 어떤 예외든 통일한 예외로 바꾼다. 이미 통일된 예외는 그대로 둔다.
	 */
	public static SupplierCallException classify(Supplier supplier, Throwable error) {
		if (error instanceof SupplierCallException unified) {
			return unified;
		}
		if (hasCause(error, TimeoutException.class) || hasCause(error, ReadTimeoutException.class)) {
			return new SupplierCallException(supplier, FailureReason.TIMEOUT, null, null, error);
		}
		if (error instanceof WebClientRequestException) {
			return new SupplierCallException(supplier, FailureReason.CONNECTION, null, null, error);
		}
		if (error instanceof CodecException) {
			return new SupplierCallException(supplier, FailureReason.INVALID_RESPONSE, null, null, error);
		}
		return new SupplierCallException(supplier, FailureReason.INVALID_RESPONSE, null, null, error);
	}

	/**
	 * HTTP 상태 코드로 실패를 알리는 응답을 통일한 예외로 바꾼다.
	 * @param supplierCode 본문에 담긴 공급사 원본 코드. 없으면 null
	 */
	public static SupplierCallException fromStatus(Supplier supplier, HttpStatusCode status, String supplierCode,
			HttpHeaders headers) {
		FailureReason reason = switch (status.value()) {
			case 400 -> FailureReason.INVALID_REQUEST;
			case 401 -> FailureReason.UNAUTHORIZED;
			case 429 -> FailureReason.RATE_LIMITED;
			case 503 -> FailureReason.UNAVAILABLE;
			default -> status.is4xxClientError() ? FailureReason.INVALID_REQUEST : FailureReason.SUPPLIER_ERROR;
		};
		return new SupplierCallException(supplier, reason, supplierCode, retryAfter(headers), null);
	}

	/**
	 * Retry-After 헤더가 초 단위 숫자면 그 값을 돌려준다. 날짜 형식이거나 없으면 null.
	 */
	static Duration retryAfter(HttpHeaders headers) {
		String value = (headers == null) ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Duration.ofSeconds(Long.parseLong(value.trim()));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (type.isInstance(current)) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

}
