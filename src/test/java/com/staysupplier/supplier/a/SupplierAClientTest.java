package com.staysupplier.supplier.a;

import java.time.Duration;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.staysupplier.stay.SupplierHotel;
import com.staysupplier.stay.SupplierRoomType;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;
import com.staysupplier.supplier.SupplierCallException;
import com.staysupplier.supplier.SupplierClientTestSupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
		return new SupplierAClient(SupplierClientTestSupport.webClients(server.baseUrl(), responseTimeout));
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
				SupplierClientTestSupport.webClients("http://localhost:1", Duration.ofSeconds(1)));

		assertFailure(client, FailureReason.CONNECTION, null);
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
