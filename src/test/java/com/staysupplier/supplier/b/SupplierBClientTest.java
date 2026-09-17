package com.staysupplier.supplier.b;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
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
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supplier B 어댑터: HTTP 200 + resultCode 로 오는 실패를 A 와 같은 원인으로 통일하는지 검증한다.
 */
class SupplierBClientTest {

	static final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

	static final String PROPERTIES_JSON = """
			{
			  "resultCode": "0000",
			  "resultMessage": "SUCCESS",
			  "data": {
			    "items": [
			      {
			        "propertyId": "B77120",
			        "propertyName": "Riverside Hotel Seoul",
			        "rooms": [
			          { "roomId": "R-401", "roomName": "Deluxe Twin Room", "maxOccupancy": 2 }
			        ]
			      }
			    ]
			  }
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

	private SupplierBClient client() {
		return new SupplierBClient(SupplierClientTestSupport.webClients(server.baseUrl(), Duration.ofSeconds(3)),
				SupplierClientTestSupport.rateLimiter());
	}

	@Test
	void normalizesPropertiesAndRoomsAndSendsApiKey() {
		server.stubFor(get(urlEqualTo("/b/api/properties")).willReturn(okJson(PROPERTIES_JSON)));

		List<SupplierHotel> hotels = client().fetchHotels().block();

		assertThat(hotels).containsExactly(new SupplierHotel(Supplier.B, "B77120", "Riverside Hotel Seoul",
				List.of(new SupplierRoomType("R-401", "Deluxe Twin Room", 2))));
		server.verify(getRequestedFor(urlEqualTo("/b/api/properties"))
			.withHeader("X-Api-Key", equalTo(SupplierClientTestSupport.API_KEY_B)));
	}

	@Test
	void failureWithHttp200IsUnifiedByResultCode() {
		server.stubFor(get(urlEqualTo("/b/api/properties")).willReturn(okJson(
				"{\"resultCode\":\"E503\",\"resultMessage\":\"TEMPORARILY_UNAVAILABLE\",\"data\":null}")));

		assertFailure(FailureReason.UNAVAILABLE, "E503");
	}

	@Test
	void unknownResultCodeIsAnInvalidResponse() {
		server.stubFor(get(urlEqualTo("/b/api/properties")).willReturn(okJson(
				"{\"resultCode\":\"E999\",\"resultMessage\":\"?\",\"data\":null}")));

		assertFailure(FailureReason.INVALID_RESPONSE, "E999");
	}

	@Test
	void successWithoutDataItemsIsAnInvalidResponseNotAnEmptyResult() {
		server.stubFor(get(urlEqualTo("/b/api/properties")).willReturn(okJson(
				"{\"resultCode\":\"0000\",\"resultMessage\":\"SUCCESS\",\"data\":null}")));

		assertFailure(FailureReason.INVALID_RESPONSE, "missing data.items");
	}

	@Test
	void emptyItemsIsANormalEmptyResult() {
		server.stubFor(get(urlEqualTo("/b/api/properties")).willReturn(okJson(
				"{\"resultCode\":\"0000\",\"resultMessage\":\"SUCCESS\",\"data\":{\"items\":[]}}")));

		assertThat(client().fetchHotels().block()).isEmpty();
	}

	@Test
	void keepsRoomWithoutMaxOccupancyAsUnknown() {
		server.stubFor(get(urlEqualTo("/b/api/properties")).willReturn(okJson("""
				{ "resultCode": "0000", "resultMessage": "SUCCESS", "data": { "items": [
				  { "propertyId": "B1", "propertyName": "One", "rooms": [
				    { "roomId": "R-1", "roomName": "No occupancy" },
				    { "roomId": "R-2", "roomName": "Fine", "maxOccupancy": 4 } ] } ] } }
				""")));

		List<SupplierHotel> hotels = client().fetchHotels().block();

		assertThat(hotels).singleElement().satisfies(hotel -> assertThat(hotel.roomTypes()).containsExactly(
				new SupplierRoomType("R-1", "No occupancy", null), new SupplierRoomType("R-2", "Fine", 4)));
	}

	@Test
	void httpErrorAgainstTheSpecFallsBackToStatusMapping() {
		server.stubFor(get(urlEqualTo("/b/api/properties")).willReturn(aResponse().withStatus(500)));

		assertFailure(FailureReason.SUPPLIER_ERROR, null);
	}

	static final String SEARCH_JSON = """
			{
			  "resultCode": "0000", "resultMessage": "SUCCESS",
			  "data": { "items": [ {
			    "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul",
			    "roomId": "R-401", "roomName": "Deluxe Twin Room", "maxOccupancy": 2,
			    "breakfastIncluded": true, "currency": "KRW", "totalPrice": 452000, "taxIncluded": true,
			    "inventory": [
			      { "date": "2026-09-01", "remainingRooms": 3 },
			      { "date": "2026-09-02", "remainingRooms": 1 },
			      { "date": "2026-09-03", "remainingRooms": 5 }
			    ]
			  } ] }
			}
			""";

	static final AvailabilityQuery THREE_NIGHTS = new AvailabilityQuery(List.of("B77120"), LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 9, 4), 2, 0);

	@Test
	void normalizesSearchToMinRoomsAndTotalPriceAsIs() {
		server.stubFor(get(urlPathEqualTo("/b/api/search")).willReturn(okJson(SEARCH_JSON)));

		SupplierFetchResult result = client().fetchAvailability(THREE_NIGHTS).block();

		assertThat(result.failures()).isEmpty();
		assertThat(result.offers()).containsExactly(
				new SupplierRoomOffer(Supplier.B, "B77120", "R-401", "Deluxe Twin Room", 2, 1, 452_000L, "KRW", true));
		server.verify(getRequestedFor(urlPathEqualTo("/b/api/search"))
			.withQueryParam("propertyIds", equalTo("B77120"))
			.withQueryParam("checkIn", equalTo("2026-09-01"))
			.withQueryParam("checkOut", equalTo("2026-09-04"))
			.withHeader("X-Api-Key", equalTo(SupplierClientTestSupport.API_KEY_B)));
	}

	@Test
	void searchFailureWithHttp200IsUnifiedByResultCode() {
		server.stubFor(get(urlPathEqualTo("/b/api/search")).willReturn(okJson(
				"{\"resultCode\":\"E503\",\"resultMessage\":\"TEMPORARILY_UNAVAILABLE\",\"data\":null}")));

		SupplierCallException failure = null;
		try {
			client().fetchAvailability(THREE_NIGHTS).block();
		}
		catch (SupplierCallException ex) {
			failure = ex;
		}
		assertThat(failure).isNotNull();
		assertThat(failure.getReason()).isEqualTo(FailureReason.UNAVAILABLE);
		assertThat(failure.getSupplierCode()).isEqualTo("E503");
	}

	@Test
	void dailyAvailabilityCallsOncePerNightBecauseBOnlyGivesPeriodTotals() {
		server.stubFor(get(urlPathEqualTo("/b/api/search")).willReturn(okJson("""
				{ "resultCode": "0000", "resultMessage": "SUCCESS", "data": { "items": [ {
				  "propertyId": "B77120", "roomId": "R-401", "roomName": "Deluxe Twin Room", "maxOccupancy": 2,
				  "breakfastIncluded": true, "currency": "KRW", "totalPrice": 150000, "taxIncluded": true,
				  "inventory": [ { "date": "2026-09-01", "remainingRooms": 3 } ] } ] } }
				""")));

		SupplierDailyFetchResult result = client()
			.fetchDailyAvailability(List.of("B77120"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4))
			.block();

		assertThat(result.offers()).hasSize(3).contains(
				new SupplierDailyOffer(Supplier.B, "B77120", "R-401", 2, LocalDate.of(2026, 9, 3), 3, 150_000L, "KRW", true));
		server.verify(3, getRequestedFor(urlPathEqualTo("/b/api/search")).withQueryParam("adults", equalTo("1")));
		server.verify(getRequestedFor(urlPathEqualTo("/b/api/search")).withQueryParam("checkIn", equalTo("2026-09-02"))
			.withQueryParam("checkOut", equalTo("2026-09-03")));
	}

	@Test
	void skipsOfferWhoseInventoryDoesNotCoverEveryNight() {
		server.stubFor(get(urlPathEqualTo("/b/api/search")).willReturn(okJson("""
				{ "resultCode": "0000", "resultMessage": "SUCCESS", "data": { "items": [ {
				  "propertyId": "B1", "roomId": "R-1", "currency": "KRW", "totalPrice": 1000, "breakfastIncluded": false,
				  "inventory": [ { "date": "2026-09-01", "remainingRooms": 1 }, { "date": "2026-09-02", "remainingRooms": 1 } ] } ] } }
				""")));

		SupplierFetchResult result = client().fetchAvailability(THREE_NIGHTS).block();

		assertThat(result.offers()).isEmpty();
	}

	private static ResponseDefinitionBuilder okJson(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

	private void assertFailure(FailureReason reason, String supplierCode) {
		SupplierCallException failure = null;
		try {
			client().fetchHotels().block();
		}
		catch (SupplierCallException ex) {
			failure = ex;
		}
		assertThat(failure).as("expected SupplierCallException").isNotNull();
		assertThat(failure.getSupplier()).isEqualTo(Supplier.B);
		assertThat(failure.getReason()).isEqualTo(reason);
		assertThat(failure.getSupplierCode()).isEqualTo(supplierCode);
	}

}
