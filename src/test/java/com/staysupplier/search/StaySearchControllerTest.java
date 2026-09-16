package com.staysupplier.search;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.staysupplier.search.StaySearchResponse.Price;
import com.staysupplier.search.StaySearchResponse.RoomType;
import com.staysupplier.search.StaySearchResponse.Stay;
import com.staysupplier.search.StaySearchResponse.SupplierFailure;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaySearchController.class)
class StaySearchControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	StaySearchService searchService;

	@Test
	void returnsMergedResultWithFailuresAs200() throws Exception {
		given(this.searchService.search(any())).willReturn(new StaySearchResponse(LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 9, 4), 3, 2, 0,
				List.of(new Stay(1L, "Riverside Hotel Seoul", Supplier.A, List.of(
						new RoomType(11L, "Deluxe Twin", 2, 1, new Price(429_000L, "KRW", true, false))))),
				List.of(new SupplierFailure(Supplier.B, FailureReason.TIMEOUT, 1)), List.of(), true));

		this.mockMvc
			.perform(get("/api/v1/stays/search").param("checkIn", "2026-09-01")
				.param("checkOut", "2026-09-04")
				.param("adults", "2")
				.param("children", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nights").value(3))
			.andExpect(jsonPath("$.stays[0].hotelId").value(1))
			.andExpect(jsonPath("$.stays[0].supplier").value("A"))
			.andExpect(jsonPath("$.stays[0].roomTypes[0].roomTypeId").value(11))
			.andExpect(jsonPath("$.stays[0].roomTypes[0].availableRooms").value(1))
			.andExpect(jsonPath("$.stays[0].roomTypes[0].price.total").value(429000))
			.andExpect(jsonPath("$.stays[0].roomTypes[0].price.taxIncluded").value(true))
			.andExpect(jsonPath("$.failures[0].supplier").value("B"))
			.andExpect(jsonPath("$.failures[0].reason").value("TIMEOUT"));
	}

	@Test
	void invalidRequestIs400WithoutCallingSuppliers() throws Exception {
		given(this.searchService.search(any())).willThrow(new InvalidSearchRequestException("checkOut must be after checkIn"));

		this.mockMvc
			.perform(get("/api/v1/stays/search").param("checkIn", "2026-09-04")
				.param("checkOut", "2026-09-01")
				.param("adults", "2"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.message").value("checkOut must be after checkIn"));
	}

	@Test
	void missingOrMalformedParameterIs400() throws Exception {
		this.mockMvc.perform(get("/api/v1/stays/search").param("checkIn", "2026-09-01").param("adults", "2"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		this.mockMvc
			.perform(get("/api/v1/stays/search").param("checkIn", "2026/09/01")
				.param("checkOut", "2026-09-04")
				.param("adults", "2"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("invalid value for checkIn"));
	}

	@Test
	void everySupplierFailingIs503WithRetryAfter() throws Exception {
		given(this.searchService.search(any())).willThrow(new AllSuppliersFailedException(
				List.of(new SupplierFailure(Supplier.A, FailureReason.UNAVAILABLE, 2),
						new SupplierFailure(Supplier.B, FailureReason.TIMEOUT, 1))));

		this.mockMvc
			.perform(get("/api/v1/stays/search").param("checkIn", "2026-09-01")
				.param("checkOut", "2026-09-04")
				.param("adults", "2"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "5"))
			.andExpect(jsonPath("$.code").value("ALL_SUPPLIERS_FAILED"))
			.andExpect(jsonPath("$.failures.length()").value(2));
	}

}
