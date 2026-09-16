package com.staysupplier.supplier.b;

import java.time.Duration;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
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
		return new SupplierBClient(SupplierClientTestSupport.webClients(server.baseUrl(), Duration.ofSeconds(3)));
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
