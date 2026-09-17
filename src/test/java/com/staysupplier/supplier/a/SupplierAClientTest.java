package com.staysupplier.supplier.a;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.staysupplier.stay.AvailabilityQuery;
import com.staysupplier.stay.SupplierDailyFetchResult;
import com.staysupplier.stay.SupplierDailyOffer;
import com.staysupplier.stay.SupplierFetchResult;
import com.staysupplier.stay.SupplierHotel;
import com.staysupplier.stay.SupplierRoomOffer;
import com.staysupplier.stay.SupplierRoomType;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;
import com.staysupplier.supplier.SupplierCallException;
import com.staysupplier.supplier.SupplierClientTestSupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Supplier A 어댑터: 숙소 목록 응답의 정규화와 HTTP 상태 코드 기반 실패 판정을 WireMock 으로 검증한다.
 */
class SupplierAClientTest {

	static final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

	static final String HOTELS_JSON = """
			{
			  "items": [
			    {
			      "hotelCode": "A-10023",
			      "hotelName": "Riverside Hotel Seoul",
			      "roomTypes": [
			        { "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin", "maxOccupancy": 2 }
			      ]
			    },
			    {
			      "hotelCode": "A-10044",
			      "hotelName": "Namsan Garden Stay",
			      "roomTypes": [
			        { "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double", "maxOccupancy": 2 }
			      ]
			    }
			  ]
			}
			""";

	@BeforeAll
	static void start() {
		server.start();
	}

	@AfterAll
	static void stop() {
		server.stop();
	}

	@BeforeEach
	void reset() {
		server.resetAll();
	}

	private SupplierAClient client() {
		return client(Duration.ofSeconds(3));
	}

	private SupplierAClient client(Duration responseTimeout) {
		return new SupplierAClient(SupplierClientTestSupport.webClients(server.baseUrl(), responseTimeout),
				SupplierClientTestSupport.rateLimiter());
	}

	@Test
	void normalizesHotelsAndRoomTypesAndSendsApiKey() {
		server.stubFor(get(urlEqualTo("/a/v1/hotels")).willReturn(okJson(HOTELS_JSON)));

		List<SupplierHotel> hotels = client().fetchHotels().block();

		assertThat(hotels).containsExactly(
				new SupplierHotel(Supplier.A, "A-10023", "Riverside Hotel Seoul",
						List.of(new SupplierRoomType("DLX-TWN", "Deluxe Twin", 2))),
				new SupplierHotel(Supplier.A, "A-10044", "Namsan Garden Stay",
						List.of(new SupplierRoomType("STD-DBL", "Standard Double", 2))));
		server.verify(getRequestedFor(urlEqualTo("/a/v1/hotels"))
			.withHeader("X-Api-Key", equalTo(SupplierClientTestSupport.API_KEY_A)));
	}

	@Test
	void keepsRoomTypeWithoutMaxOccupancyAsUnknownAndSkipsOneWithoutCode() {
		server.stubFor(get(urlEqualTo("/a/v1/hotels")).willReturn(okJson("""
				{ "items": [ { "hotelCode": "A-1", "hotelName": "One", "roomTypes": [
				    { "roomTypeCode": "NO-OCC", "roomTypeName": "No occupancy" },
				    { "roomTypeName": "No code", "maxOccupancy": 2 },
				    { "roomTypeCode": "OK", "roomTypeName": "Fine", "maxOccupancy": 3 } ] } ] }
				""")));

		List<SupplierHotel> hotels = client().fetchHotels().block();

		assertThat(hotels).singleElement().satisfies(hotel -> assertThat(hotel.roomTypes()).containsExactly(
				new SupplierRoomType("NO-OCC", "No occupancy", null), new SupplierRoomType("OK", "Fine", 3)));
	}

	@Test
	void emptyItemsIsANormalEmptyResult() {
		server.stubFor(get(urlEqualTo("/a/v1/hotels")).willReturn(okJson("{ \"items\": [] }")));

		assertThat(client().fetchHotels().block()).isEmpty();
	}

	@Test
	void missingItemsIsAnInvalidResponse() {
		server.stubFor(get(urlEqualTo("/a/v1/hotels")).willReturn(okJson("{}")));

		assertFailure(client(), FailureReason.INVALID_RESPONSE, "missing items");
	}

	@Test
	void serviceUnavailableStatusIsUnifiedWithSupplierCode() {
		server.stubFor(get(urlEqualTo("/a/v1/hotels")).willReturn(aResponse().withStatus(503)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"error\":\"SERVICE_UNAVAILABLE\",\"message\":\"temporarily unavailable\"}")));

		assertFailure(client(), FailureReason.UNAVAILABLE, "SERVICE_UNAVAILABLE");
	}

	@Test
	void rateLimitedCarriesRetryAfter() {
		server.stubFor(get(urlEqualTo("/a/v1/hotels")).willReturn(aResponse().withStatus(429)
			.withHeader("Retry-After", "30")
			.withHeader("Content-Type", "application/json")
			.withBody("{\"error\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"slow down\"}")));

		SupplierCallException failure = failureOf(client());

		assertThat(failure.getReason()).isEqualTo(FailureReason.RATE_LIMITED);
		assertThat(failure.getRetryAfter()).isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void slowResponseBecomesTimeout() {
		server.stubFor(get(urlEqualTo("/a/v1/hotels"))
			.willReturn(okJson(HOTELS_JSON).withFixedDelay(1500)));

		assertFailure(client(Duration.ofMillis(300)), FailureReason.TIMEOUT, null);
	}

	@Test
	void unreachableSupplierBecomesConnectionFailure() {
		SupplierAClient client = new SupplierAClient(
				SupplierClientTestSupport.webClients("http://localhost:1", Duration.ofSeconds(1)),
				SupplierClientTestSupport.rateLimiter());

		assertFailure(client, FailureReason.CONNECTION, null);
	}

	static final String AVAILABILITY_JSON = """
			{
			  "items": [
			    {
			      "hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul",
			      "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin", "maxOccupancy": 2,
			      "breakfastIncluded": false, "currency": "KRW",
			      "dailyRates": [
			        { "date": "2026-09-01", "remainingRooms": 3, "nightlyRate": 120000, "taxAmount": 12000 },
			        { "date": "2026-09-02", "remainingRooms": 1, "nightlyRate": 150000, "taxAmount": 15000 },
			        { "date": "2026-09-03", "remainingRooms": 5, "nightlyRate": 120000, "taxAmount": 12000 }
			      ]
			    },
			    {
			      "hotelCode": "A-10044", "hotelName": "Namsan Garden Stay",
			      "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double", "maxOccupancy": 2,
			      "breakfastIncluded": false, "currency": "KRW",
			      "dailyRates": [
			        { "date": "2026-09-01", "remainingRooms": 2, "nightlyRate": 88000, "taxAmount": 8800 },
			        { "date": "2026-09-02", "remainingRooms": 0, "nightlyRate": 99000, "taxAmount": 9900 },
			        { "date": "2026-09-03", "remainingRooms": 4, "nightlyRate": 88000, "taxAmount": 8800 }
			      ]
			    }
			  ]
			}
			""";

	static final AvailabilityQuery THREE_NIGHTS = new AvailabilityQuery(List.of("A-10023", "A-10044"),
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4), 2, 0);

	@Test
	void normalizesAvailabilityToMinRoomsAndTaxIncludedTotalForThePeriod() {
		server.stubFor(get(urlPathEqualTo("/a/v1/availability")).willReturn(okJson(AVAILABILITY_JSON)));

		SupplierFetchResult result = client().fetchAvailability(THREE_NIGHTS).block();

		assertThat(result.failures()).isEmpty();
		assertThat(result.offers()).containsExactly(
				new SupplierRoomOffer(Supplier.A, "A-10023", "DLX-TWN", "Deluxe Twin", 2, 1, 429_000L, "KRW", false),
				new SupplierRoomOffer(Supplier.A, "A-10044", "STD-DBL", "Standard Double", 2, 0, 302_500L, "KRW", false));
		server.verify(getRequestedFor(urlPathEqualTo("/a/v1/availability"))
			.withQueryParam("hotelCodes", equalTo("A-10023,A-10044"))
			.withQueryParam("checkIn", equalTo("2026-09-01"))
			.withQueryParam("checkOut", equalTo("2026-09-04"))
			.withQueryParam("adults", equalTo("2"))
			.withQueryParam("children", equalTo("0")));
	}

	@Test
	void splitsHotelCodesIntoRequestsOfAtMostFifty() {
		server.stubFor(get(urlPathEqualTo("/a/v1/availability")).willReturn(okJson("{ \"items\": [] }")));
		List<String> codes = IntStream.rangeClosed(1, 120).mapToObj(i -> "A-" + i).toList();

		SupplierFetchResult result = client()
			.fetchAvailability(new AvailabilityQuery(codes, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), 1, 0))
			.block();

		assertThat(result.offers()).isEmpty();
		server.verify(3, getRequestedFor(urlPathEqualTo("/a/v1/availability")));
	}

	@Test
	void keepsOtherChunksWhenOneChunkFails() {
		// 51개 코드 → 묶음 두 개. 앞 50개 묶음은 503, 마지막 1개 묶음은 정상
		List<String> codes = new java.util.ArrayList<>(IntStream.rangeClosed(1, 50).mapToObj(i -> "A-x" + i).toList());
		codes.add("A-10023");
		server.stubFor(get(urlPathEqualTo("/a/v1/availability"))
			.withQueryParam("hotelCodes", equalTo(String.join(",", codes.subList(0, 50))))
			.willReturn(aResponse().withStatus(503)));
		server.stubFor(get(urlPathEqualTo("/a/v1/availability"))
			.withQueryParam("hotelCodes", equalTo("A-10023"))
			.willReturn(okJson(AVAILABILITY_JSON)));

		SupplierFetchResult result = client()
			.fetchAvailability(new AvailabilityQuery(codes, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4), 2, 0))
			.block();

		assertThat(result.offers()).hasSize(2);
		assertThat(result.failures()).singleElement().satisfies(failure -> {
			assertThat(failure.reason()).isEqualTo(FailureReason.UNAVAILABLE);
			assertThat(failure.hotelCodes()).hasSize(50);
		});
	}

	@Test
	void failsWhenEveryChunkFails() {
		server.stubFor(get(urlPathEqualTo("/a/v1/availability")).willReturn(aResponse().withStatus(503)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"error\":\"SERVICE_UNAVAILABLE\",\"message\":\"temporarily unavailable\"}")));

		SupplierCallException failure = null;
		try {
			client().fetchAvailability(THREE_NIGHTS).block();
		}
		catch (SupplierCallException ex) {
			failure = ex;
		}
		assertThat(failure).isNotNull();
		assertThat(failure.getReason()).isEqualTo(FailureReason.UNAVAILABLE);
	}

	@Test
	void dailyAvailabilityIsOneCallPerChunkWithNightlyTaxIncludedValues() {
		server.stubFor(get(urlPathEqualTo("/a/v1/availability")).willReturn(okJson(AVAILABILITY_JSON)));

		SupplierDailyFetchResult result = client()
			.fetchDailyAvailability(List.of("A-10023", "A-10044"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4))
			.block();

		assertThat(result.failures()).isEmpty();
		assertThat(result.offers()).hasSize(6).contains(
				new SupplierDailyOffer(Supplier.A, "A-10023", "DLX-TWN", 2, LocalDate.of(2026, 9, 2), 1, 165_000L, "KRW", false),
				new SupplierDailyOffer(Supplier.A, "A-10044", "STD-DBL", 2, LocalDate.of(2026, 9, 2), 0, 108_900L, "KRW", false));
		server.verify(1, getRequestedFor(urlPathEqualTo("/a/v1/availability")).withQueryParam("adults", equalTo("1"))
			.withQueryParam("checkIn", equalTo("2026-09-01"))
			.withQueryParam("checkOut", equalTo("2026-09-04")));
	}

	@Test
	void skipsOfferWhoseDailyRatesDoNotCoverEveryNight() {
		server.stubFor(get(urlPathEqualTo("/a/v1/availability")).willReturn(okJson("""
				{ "items": [ { "hotelCode": "A-1", "roomTypeCode": "R", "currency": "KRW", "breakfastIncluded": false,
				  "dailyRates": [ { "date": "2026-09-01", "remainingRooms": 1, "nightlyRate": 100, "taxAmount": 10 } ] } ] }
				""")));

		SupplierFetchResult result = client().fetchAvailability(THREE_NIGHTS).block();

		assertThat(result.offers()).isEmpty();
		assertThat(result.failures()).isEmpty();
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

	private static void assertFailure(SupplierAClient client, FailureReason reason, String supplierCode) {
		SupplierCallException failure = failureOf(client);
		assertThat(failure.getSupplier()).isEqualTo(Supplier.A);
		assertThat(failure.getReason()).isEqualTo(reason);
		assertThat(failure.getSupplierCode()).isEqualTo(supplierCode);
	}

	private static SupplierCallException failureOf(SupplierAClient client) {
		try {
			client.fetchHotels().block();
		}
		catch (SupplierCallException ex) {
			return ex;
		}
		throw new AssertionError("expected SupplierCallException");
	}

}
