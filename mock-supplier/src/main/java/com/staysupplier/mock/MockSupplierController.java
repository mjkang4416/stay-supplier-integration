package com.staysupplier.mock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
 * 숙소 목록은 고정 JSON 이고, 재고·요금은 요청한 날짜 범위대로 부록 예제의 3일 패턴을 반복해 만든다
 * (2026-09-01~04 를 요청하면 예제와 같은 값: A 429,000 / B 452,000). 재고·요금 조회 API만 모드(정상 / 장애 / 무응답)를 바꿀 수 있다.
 */
@RestController
class MockSupplierController {

	private static final Set<String> SUPPLIERS = Set.of("a", "b");

	private static final Duration NO_RESPONSE_WAIT = Duration.ofMinutes(10);

	private static final String A_HOTELS = load("responses/a-hotels.json");

	private static final String B_PROPERTIES = load("responses/b-properties.json");

	/** 패턴의 기준일. 이 날부터 3일 주기로 값을 반복한다 */
	private static final LocalDate PATTERN_BASE = LocalDate.of(2026, 9, 1);

	// A: 날짜별 [잔여 객실, 1박 단가(세금 별도), 세금]
	private static final int[][] A_RIVERSIDE = { { 3, 120000, 12000 }, { 1, 150000, 15000 }, { 5, 120000, 12000 } };

	private static final int[][] A_NAMSAN = { { 2, 88000, 8800 }, { 0, 99000, 9900 }, { 4, 88000, 8800 } };

	// B: 날짜별 [잔여 객실, 1박 총액(세금 포함)]. 3박 합 452,000
	private static final int[][] B_RIVERSIDE = { { 3, 150000 }, { 1, 152000 }, { 5, 150000 } };

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
	ResponseEntity<String> availabilityA(@RequestParam String hotelCodes, @RequestParam LocalDate checkIn,
			@RequestParam LocalDate checkOut) {
		return switch (modeOf("a")) {
			case NORMAL -> ResponseEntity.ok(availabilityA(checkIn, checkOut));
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
	ResponseEntity<String> searchB(@RequestParam String propertyIds, @RequestParam LocalDate checkIn,
			@RequestParam LocalDate checkOut) {
		return switch (modeOf("b")) {
			case NORMAL -> ResponseEntity.ok(searchB(checkIn, checkOut));
			// Supplier B는 장애 상황에서도 HTTP 200을 주고 본문 resultCode로만 실패를 알린다
			case ERROR -> ResponseEntity.ok("{\"resultCode\":\"E503\",\"resultMessage\":\"TEMPORARILY_UNAVAILABLE\",\"data\":null}");
			case NO_RESPONSE -> waitWithoutResponse();
		};
	}

	private static String availabilityA(LocalDate checkIn, LocalDate checkOut) {
		return "{\"items\":[" + itemA("A-10023", "Riverside Hotel Seoul", "DLX-TWN", "Deluxe Twin", A_RIVERSIDE, checkIn, checkOut)
				+ "," + itemA("A-10044", "Namsan Garden Stay", "STD-DBL", "Standard Double", A_NAMSAN, checkIn, checkOut) + "]}";
	}

	private static String itemA(String hotelCode, String hotelName, String roomTypeCode, String roomTypeName,
			int[][] pattern, LocalDate checkIn, LocalDate checkOut) {
		List<String> rates = new ArrayList<>();
		for (LocalDate date = checkIn; date.isBefore(checkOut); date = date.plusDays(1)) {
			int[] day = pattern[dayIndex(date)];
			rates.add("{\"date\":\"" + date + "\",\"remainingRooms\":" + day[0] + ",\"nightlyRate\":" + day[1]
					+ ",\"taxAmount\":" + day[2] + "}");
		}
		return "{\"hotelCode\":\"" + hotelCode + "\",\"hotelName\":\"" + hotelName + "\",\"roomTypeCode\":\"" + roomTypeCode
				+ "\",\"roomTypeName\":\"" + roomTypeName + "\",\"maxOccupancy\":2,\"breakfastIncluded\":false,\"currency\":\"KRW\","
				+ "\"dailyRates\":[" + String.join(",", rates) + "]}";
	}

	private static String searchB(LocalDate checkIn, LocalDate checkOut) {
		List<String> inventory = new ArrayList<>();
		long total = 0;
		for (LocalDate date = checkIn; date.isBefore(checkOut); date = date.plusDays(1)) {
			int[] day = B_RIVERSIDE[dayIndex(date)];
			inventory.add("{\"date\":\"" + date + "\",\"remainingRooms\":" + day[0] + "}");
			total += day[1];
		}
		return "{\"resultCode\":\"0000\",\"resultMessage\":\"SUCCESS\",\"data\":{\"items\":[{\"propertyId\":\"B77120\","
				+ "\"propertyName\":\"Riverside Hotel Seoul\",\"roomId\":\"R-401\",\"roomName\":\"Deluxe Twin Room\",\"maxOccupancy\":2,"
				+ "\"breakfastIncluded\":true,\"currency\":\"KRW\",\"totalPrice\":" + total + ",\"taxIncluded\":true,"
				+ "\"inventory\":[" + String.join(",", inventory) + "]}]}}";
	}

	private static int dayIndex(LocalDate date) {
		return (int) Math.floorMod(ChronoUnit.DAYS.between(PATTERN_BASE, date), 3);
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
