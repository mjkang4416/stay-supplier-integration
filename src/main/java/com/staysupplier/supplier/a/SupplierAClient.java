package com.staysupplier.supplier.a;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import com.staysupplier.stay.AvailabilityQuery;
import com.staysupplier.stay.SupplierFetchResult;
import com.staysupplier.stay.SupplierHotel;
import com.staysupplier.stay.SupplierRoomOffer;
import com.staysupplier.stay.SupplierRoomType;
import com.staysupplier.supplier.ChunkedFetch;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;
import com.staysupplier.supplier.SupplierCallException;
import com.staysupplier.supplier.SupplierClient;
import com.staysupplier.supplier.SupplierFailures;
import com.staysupplier.supplier.SupplierWebClients;
import com.staysupplier.supplier.a.SupplierAResponses.AvailabilityItem;
import com.staysupplier.supplier.a.SupplierAResponses.AvailabilityResponse;
import com.staysupplier.supplier.a.SupplierAResponses.DailyRate;
import com.staysupplier.supplier.a.SupplierAResponses.ErrorBody;
import com.staysupplier.supplier.a.SupplierAResponses.HotelItem;
import com.staysupplier.supplier.a.SupplierAResponses.HotelsResponse;
import com.staysupplier.supplier.a.SupplierAResponses.RoomTypeItem;

/**
 * Supplier A 어댑터. 실패는 HTTP 상태 코드로 온다 (본문 { error, message }).
 */
@Component
public class SupplierAClient implements SupplierClient {

	private static final Logger log = LoggerFactory.getLogger(SupplierAClient.class);

	private final WebClient webClient;

	public SupplierAClient(SupplierWebClients webClients) {
		this.webClient = webClients.of(Supplier.A);
	}

	@Override
	public Supplier supplier() {
		return Supplier.A;
	}

	@Override
	public Mono<List<SupplierHotel>> fetchHotels() {
		return this.webClient.get()
			.uri("/a/v1/hotels")
			.retrieve()
			.onStatus(HttpStatusCode::isError, this::toFailure)
			.bodyToMono(HotelsResponse.class)
			.onErrorMap(error -> SupplierFailures.classify(Supplier.A, error))
			.map(this::normalize);
	}

	@Override
	public Mono<SupplierFetchResult> fetchAvailability(AvailabilityQuery query) {
		return ChunkedFetch.fetch(Supplier.A, query.hotelCodes(), MAX_HOTEL_CODES_PER_REQUEST,
				chunk -> this.webClient.get()
					.uri(uri -> uri.path("/a/v1/availability")
						.queryParam("hotelCodes", String.join(",", chunk))
						.queryParam("checkIn", query.checkIn())
						.queryParam("checkOut", query.checkOut())
						.queryParam("adults", query.adults())
						.queryParam("children", query.children())
						.build())
					.retrieve()
					.onStatus(HttpStatusCode::isError, this::toFailure)
					.bodyToMono(AvailabilityResponse.class)
					.onErrorMap(error -> SupplierFailures.classify(Supplier.A, error))
					.map(response -> normalizeAvailability(response, query)));
	}

	private Mono<SupplierCallException> toFailure(org.springframework.web.reactive.function.client.ClientResponse response) {
		return response.bodyToMono(ErrorBody.class)
			.onErrorResume(ex -> Mono.empty())
			.defaultIfEmpty(ErrorBody.EMPTY)
			.map(body -> SupplierFailures.fromStatus(Supplier.A, response.statusCode(), body.error(),
					response.headers().asHttpHeaders()));
	}

	/**
	 * 요청 기간 기준으로 정규화: 예약 가능 객실 수 = 날짜별 재고의 최솟값, 총액 = Σ(nightlyRate + taxAmount).
	 * 필수 값이 없거나 날짜 수가 숙박일 수와 다른 항목은 버리고 로그를 남긴다.
	 */
	private List<SupplierRoomOffer> normalizeAvailability(AvailabilityResponse response, AvailabilityQuery query) {
		if (response.items() == null) {
			throw new SupplierCallException(Supplier.A, FailureReason.INVALID_RESPONSE, "missing items");
		}
		List<SupplierRoomOffer> offers = new ArrayList<>();
		for (AvailabilityItem item : response.items()) {
			if (isBlank(item.hotelCode()) || isBlank(item.roomTypeCode()) || isBlank(item.currency())
					|| item.dailyRates() == null || item.dailyRates().size() != query.nights()) {
				log.warn("supplier=A offer skipped: missing code/currency or dailyRates != nights (hotelCode={}, roomTypeCode={}, days={}, nights={})",
						item.hotelCode(), item.roomTypeCode(), (item.dailyRates() == null) ? null : item.dailyRates().size(),
						query.nights());
				continue;
			}
			int availableRooms = Integer.MAX_VALUE;
			long totalPrice = 0;
			boolean valid = true;
			for (DailyRate rate : item.dailyRates()) {
				if (rate.remainingRooms() == null || rate.remainingRooms() < 0 || rate.nightlyRate() == null
						|| rate.nightlyRate() < 0 || rate.taxAmount() == null || rate.taxAmount() < 0) {
					valid = false;
					break;
				}
				availableRooms = Math.min(availableRooms, rate.remainingRooms());
				totalPrice += rate.nightlyRate() + rate.taxAmount();
			}
			if (!valid) {
				log.warn("supplier=A offer skipped: invalid dailyRates (hotelCode={}, roomTypeCode={})", item.hotelCode(),
						item.roomTypeCode());
				continue;
			}
			if (item.breakfastIncluded() == null) {
				log.warn("supplier=A breakfastIncluded missing, treated as false (hotelCode={}, roomTypeCode={})",
						item.hotelCode(), item.roomTypeCode());
			}
			offers.add(new SupplierRoomOffer(Supplier.A, item.hotelCode(), item.roomTypeCode(), item.roomTypeName(),
					occupancyOrUnknown(item.hotelCode(), item.roomTypeCode(), item.maxOccupancy()), availableRooms,
					totalPrice, item.currency(), Boolean.TRUE.equals(item.breakfastIncluded())));
		}
		return offers;
	}

	private List<SupplierHotel> normalize(HotelsResponse response) {
		if (response.items() == null) {
			throw new SupplierCallException(Supplier.A, FailureReason.INVALID_RESPONSE, "missing items");
		}
		List<SupplierHotel> hotels = new ArrayList<>();
		for (HotelItem item : response.items()) {
			if (isBlank(item.hotelCode()) || isBlank(item.hotelName())) {
				log.warn("supplier=A hotel skipped: missing hotelCode or hotelName (hotelCode={})", item.hotelCode());
				continue;
			}
			hotels.add(new SupplierHotel(Supplier.A, item.hotelCode(), item.hotelName(), normalizeRoomTypes(item)));
		}
		return hotels;
	}

	private List<SupplierRoomType> normalizeRoomTypes(HotelItem item) {
		List<SupplierRoomType> roomTypes = new ArrayList<>();
		if (item.roomTypes() == null) {
			return roomTypes;
		}
		for (RoomTypeItem roomType : item.roomTypes()) {
			if (isBlank(roomType.roomTypeCode()) || isBlank(roomType.roomTypeName())) {
				log.warn("supplier=A roomType skipped: missing roomTypeCode or roomTypeName (hotelCode={}, roomTypeCode={})",
						item.hotelCode(), roomType.roomTypeCode());
				continue;
			}
			roomTypes.add(new SupplierRoomType(roomType.roomTypeCode(), roomType.roomTypeName(),
					occupancyOrUnknown(item.hotelCode(), roomType.roomTypeCode(), roomType.maxOccupancy())));
		}
		return roomTypes;
	}

	/**
	 * 최대 인원이 없거나 1 미만이면 기본값을 넣지 않고 미상(null)으로 둔다. 인원 필터에 추측한 값을 쓰지 않기 위해서다.
	 */
	private static Integer occupancyOrUnknown(String hotelCode, String roomTypeCode, Integer maxOccupancy) {
		if (maxOccupancy == null || maxOccupancy < 1) {
			log.warn("supplier=A maxOccupancy unknown, stored as null (hotelCode={}, roomTypeCode={}, value={})", hotelCode,
					roomTypeCode, maxOccupancy);
			return null;
		}
		return maxOccupancy;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
