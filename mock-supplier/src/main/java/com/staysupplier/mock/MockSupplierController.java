package com.staysupplier.mock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Supplier A·B의 API를 흉내 내는 Mock 컨트롤러.
 * 요청 파라미터와 관계없이 고정 응답을 주고, 재고·요금 조회 API만 모드(정상 / 장애 / 무응답)를 바꿀 수 있다.
 */
@RestController
class MockSupplierController {

	private static final Set<String> SUPPLIERS = Set.of("a", "b");

	private static final Duration NO_RESPONSE_WAIT = Duration.ofMinutes(10);

	private static final String A_HOTELS = load("responses/a-hotels.json");

	private static final String A_AVAILABILITY = load("responses/a-availability.json");

	private static final String B_PROPERTIES = load("responses/b-properties.json");

	private static final String B_SEARCH = load("responses/b-search.json");

	private final Map<String, MockMode> modes = new ConcurrentHashMap<>();

	@PostMapping("/control/{supplier}/mode")
	Map<String, String> setMode(@PathVariable String supplier, @RequestParam String value) {
		if (!SUPPLIERS.contains(supplier)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown supplier: " + supplier);
		}
		MockMode mode = MockMode.from(value);
		modes.put(supplier, mode);
		return Map.of(supplier, mode.value());
	}

	@GetMapping(value = "/a/v1/hotels", produces = MediaType.APPLICATION_JSON_VALUE)
	String hotelsA() {
		return A_HOTELS;
	}

	@GetMapping(value = "/a/v1/availability", produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<String> availabilityA(@RequestParam String hotelCodes) {
		return switch (modeOf("a")) {
			case NORMAL -> ResponseEntity.ok(A_AVAILABILITY);
			case ERROR -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body("{\"error\":\"SERVICE_UNAVAILABLE\",\"message\":\"temporarily unavailable\"}");
			case NO_RESPONSE -> waitWithoutResponse();
		};
	}

	@GetMapping(value = "/b/api/properties", produces = MediaType.APPLICATION_JSON_VALUE)
	String propertiesB() {
		return B_PROPERTIES;
	}

	@GetMapping(value = "/b/api/search", produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<String> searchB(@RequestParam String propertyIds) {
		return switch (modeOf("b")) {
			case NORMAL -> ResponseEntity.ok(B_SEARCH);
			// Supplier B는 장애 상황에서도 HTTP 200을 주고 본문 resultCode로만 실패를 알린다
			case ERROR -> ResponseEntity.ok("{\"resultCode\":\"E503\",\"resultMessage\":\"TEMPORARILY_UNAVAILABLE\",\"data\":null}");
			case NO_RESPONSE -> waitWithoutResponse();
		};
	}

	private MockMode modeOf(String supplier) {
		return modes.getOrDefault(supplier, MockMode.NORMAL);
	}

	private ResponseEntity<String> waitWithoutResponse() {
		try {
			Thread.sleep(NO_RESPONSE_WAIT);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		return ResponseEntity.ok("{}");
	}

	private static String load(String path) {
		try {
			return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
