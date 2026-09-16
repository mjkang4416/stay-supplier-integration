package com.staysupplier.search;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.staysupplier.search.StaySearchResponse.SupplierFailure;

/**
 * 검색 API 의 오류 응답. 잘못된 요청은 400, 공급사 전부 실패는 503 + Retry-After.
 */
@RestControllerAdvice(assignableTypes = StaySearchController.class)
public class SearchExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(SearchExceptionHandler.class);

	static final String RETRY_AFTER_SECONDS = "5";

	@ExceptionHandler({ InvalidSearchRequestException.class, MissingServletRequestParameterException.class,
			MethodArgumentTypeMismatchException.class })
	public ResponseEntity<ErrorResponse> invalidRequest(Exception ex) {
		String message = (ex instanceof MethodArgumentTypeMismatchException mismatch)
				? "invalid value for " + mismatch.getName() : ex.getMessage();
		return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", message, List.of()));
	}

	@ExceptionHandler(AllSuppliersFailedException.class)
	public ResponseEntity<ErrorResponse> allSuppliersFailed(AllSuppliersFailedException ex) {
		log.error("search failed for every supplier: {}", ex.getFailures());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
			.body(new ErrorResponse("ALL_SUPPLIERS_FAILED", "every supplier failed, retry later", ex.getFailures()));
	}

	public record ErrorResponse(String code, String message, List<SupplierFailure> failures) {
	}

}
