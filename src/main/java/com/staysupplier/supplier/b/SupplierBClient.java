package com.staysupplier.supplier.b;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.staysupplier.stay.AvailabilityQuery;
import com.staysupplier.stay.SupplierDailyFetchResult;
import com.staysupplier.stay.SupplierDailyOffer;
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
import com.staysupplier.supplier.SupplierRateLimiter;
import com.staysupplier.supplier.SupplierWebClients;
import com.staysupplier.supplier.b.SupplierBResponses.Envelope;
import com.staysupplier.supplier.b.SupplierBResponses.PropertiesData;
import com.staysupplier.supplier.b.SupplierBResponses.PropertyItem;
import com.staysupplier.supplier.b.SupplierBResponses.InventoryItem;
import com.staysupplier.supplier.b.SupplierBResponses.RoomItem;
import com.staysupplier.supplier.b.SupplierBResponses.SearchData;
import com.staysupplier.supplier.b.SupplierBResponses.SearchItem;

/**
 * Supplier B 어댑터. 장애 상황에서도 HTTP 200 을 주고 본문 resultCode 로만 실패를 알리므로,
 * resultCode 를 읽어 A 의 HTTP 상태 코드와 같은 {@link FailureReason} 으로 바꾼다.
 */
@Component
public class SupplierBClient implements SupplierClient {

	private static final Logger log = LoggerFactory.getLogger(SupplierBClient.class);

	private static final ParameterizedTypeReference<Envelope<PropertiesData>> PROPERTIES_TYPE =
			new ParameterizedTypeReference<>() {
			};

	private static final ParameterizedTypeReference<Envelope<SearchData>> SEARCH_TYPE =
			new ParameterizedTypeReference<>() {
			};

	private final WebClient webClient;

	private final SupplierRateLimiter rateLimiter;

	public SupplierBClient(SupplierWebClients webClients, SupplierRateLimiter rateLimiter) {
		this.webClient = webClients.of(Supplier.B);
		this.rateLimiter = rateLimiter;
	}

	@Override
	public Supplier supplier() {
		return Supplier.B;
	}

	@Override
	public Mono<List<SupplierHotel>> fetchHotels() {
		return this.webClient.get()
			.uri("/b/api/properties")
			.retrieve()
			// 스펙과 달리 HTTP 오류로 응답하면 A 와 같은 규칙으로 판정한다
			.onStatus(HttpStatusCode::isError, response -> Mono.just(SupplierFailures.fromStatus(Supplier.B,
					response.statusCode(), null, response.headers().asHttpHeaders())))
			.bodyToMono(PROPERTIES_TYPE)
			.onErrorMap(error -> SupplierFailures.classify(Supplier.B, error))
			.transform(call -> this.rateLimiter.throttle(Supplier.B, call))
			.map(this::normalize);
	}

	@Override
	public Mono<SupplierFetchResult> fetchAvailability(AvailabilityQuery query) {
		return ChunkedFetch.fetch(Supplier.B, query.hotelCodes(), MAX_HOTEL_CODES_PER_REQUEST,
				chunk -> callSearch(chunk, query.checkIn(), query.checkOut(), query.adults(), query.children())
					.map(envelope -> normalizeAvailability(envelope, query)));
	}

	/** B 는 기간 총액만 주므로 날짜마다 1박으로 호출해 그날 값을 받는다 (묶음당 날짜 수만큼 호출) */
	@Override
	public Mono<SupplierDailyFetchResult> fetchDailyAvailability(List<String> hotelCodes, LocalDate from, LocalDate to) {
		List<LocalDate> dates = from.datesUntil(to).toList();
		return ChunkedFetch.fetchDaily(Supplier.B, hotelCodes, MAX_HOTEL_CODES_PER_REQUEST,
				chunk -> Flux.fromIterable(dates)
					.concatMap(date -> callSearch(chunk, date, date.plusDays(1), 1, 0)
						.map(envelope -> normalizeDaily(envelope, date)))
					.collectList()
					.map(perDate -> perDate.stream().flatMap(List::stream).toList()));
	}

	private Mono<Envelope<SearchData>> callSearch(List<String> chunk, LocalDate checkIn, LocalDate checkOut, int adults,
			int children) {
		return this.rateLimiter.throttle(Supplier.B, this.webClient.get()
			.uri(uri -> uri.path("/b/api/search")
				.queryParam("propertyIds", String.join(",", chunk))
				.queryParam("checkIn", checkIn)
				.queryParam("checkOut", checkOut)
				.queryParam("adults", adults)
				.queryParam("children", children)
				.build())
			.retrieve()
			.onStatus(HttpStatusCode::isError, response -> Mono.just(SupplierFailures.fromStatus(Supplier.B,
					response.statusCode(), null, response.headers().asHttpHeaders())))
			.bodyToMono(SEARCH_TYPE)
			.onErrorMap(error -> SupplierFailures.classify(Supplier.B, error)));
	}

	private List<SupplierDailyOffer> normalizeDaily(Envelope<SearchData> envelope, LocalDate date) {
		requireSuccess(envelope);
		if (envelope.data().items() == null) {
			throw new SupplierCallException(Supplier.B, FailureReason.INVALID_RESPONSE, "missing data.items");
		}
		List<SupplierDailyOffer> offers = new ArrayList<>();
		for (SearchItem item : envelope.data().items()) {
			if (isBlank(item.propertyId()) || isBlank(item.roomId()) || isBlank(item.currency()) || item.totalPrice() == null
					|| item.totalPrice() < 0 || item.inventory() == null || item.inventory().size() != 1
					|| item.inventory().get(0).remainingRooms() == null || item.inventory().get(0).remainingRooms() < 0) {
				log.warn("supplier=B daily offer skipped: invalid (propertyId={}, roomId={}, date={})", item.propertyId(),
						item.roomId(), date);
				continue;
			}
			offers.add(new SupplierDailyOffer(Supplier.B, item.propertyId(), item.roomId(),
					occupancyOrUnknown(item.propertyId(), item.roomId(), item.maxOccupancy()), date,
					item.inventory().get(0).remainingRooms(), item.totalPrice(), item.currency(),
					Boolean.TRUE.equals(item.breakfastIncluded())));
		}
		return offers;
	}

	/**
	 * 요청 기간 기준으로 정규화: 예약 가능 객실 수 = 날짜별 재고의 최솟값, 총액 = totalPrice 그대로(세금 포함).
	 * 필수 값이 없거나 날짜 수가 숙박일 수와 다른 항목은 버리고 로그를 남긴다.
	 */
	private List<SupplierRoomOffer> normalizeAvailability(Envelope<SearchData> envelope, AvailabilityQuery query) {
		requireSuccess(envelope);
		if (envelope.data().items() == null) {
			throw new SupplierCallException(Supplier.B, FailureReason.INVALID_RESPONSE, "missing data.items");
		}
		List<SupplierRoomOffer> offers = new ArrayList<>();
		for (SearchItem item : envelope.data().items()) {
			if (isBlank(item.propertyId()) || isBlank(item.roomId()) || isBlank(item.currency()) || item.totalPrice() == null
					|| item.totalPrice() < 0 || item.inventory() == null || item.inventory().size() != query.nights()) {
				log.warn("supplier=B offer skipped: missing code/currency/totalPrice or inventory != nights (propertyId={}, roomId={}, days={}, nights={})",
						item.propertyId(), item.roomId(), (item.inventory() == null) ? null : item.inventory().size(),
						query.nights());
				continue;
			}
			int availableRooms = Integer.MAX_VALUE;
			boolean valid = true;
			for (InventoryItem inventory : item.inventory()) {
				if (inventory.remainingRooms() == null || inventory.remainingRooms() < 0) {
					valid = false;
					break;
				}
				availableRooms = Math.min(availableRooms, inventory.remainingRooms());
			}
			if (!valid) {
				log.warn("supplier=B offer skipped: invalid inventory (propertyId={}, roomId={})", item.propertyId(),
						item.roomId());
				continue;
			}
			if (item.breakfastIncluded() == null) {
				log.warn("supplier=B breakfastIncluded missing, treated as false (propertyId={}, roomId={})",
						item.propertyId(), item.roomId());
			}
			offers.add(new SupplierRoomOffer(Supplier.B, item.propertyId(), item.roomId(), item.roomName(),
					occupancyOrUnknown(item.propertyId(), item.roomId(), item.maxOccupancy()), availableRooms,
					item.totalPrice(), item.currency(), Boolean.TRUE.equals(item.breakfastIncluded())));
		}
		return offers;
	}

	/**
	 * resultCode 가 성공(0000)이고 data 가 있는지 확인한다. 실패 코드는 통일한 원인으로, 구조 누락은 깨진 응답으로 던진다.
	 */
	private static void requireSuccess(Envelope<?> envelope) {
		String resultCode = envelope.resultCode();
		if (resultCode == null) {
			throw new SupplierCallException(Supplier.B, FailureReason.INVALID_RESPONSE, "missing resultCode");
		}
		if (!SupplierBResponses.SUCCESS_CODE.equals(resultCode)) {
			throw new SupplierCallException(Supplier.B, reasonOf(resultCode), resultCode);
		}
		// 성공인데 data 구조가 없으면 "0건"이 아니라 깨진 응답이다. 0건은 items 가 빈 목록으로 온다
		if (envelope.data() == null) {
			throw new SupplierCallException(Supplier.B, FailureReason.INVALID_RESPONSE, "missing data.items");
		}
	}

	private List<SupplierHotel> normalize(Envelope<PropertiesData> envelope) {
		requireSuccess(envelope);
		if (envelope.data().items() == null) {
			throw new SupplierCallException(Supplier.B, FailureReason.INVALID_RESPONSE, "missing data.items");
		}
		List<SupplierHotel> hotels = new ArrayList<>();
		for (PropertyItem item : envelope.data().items()) {
			if (isBlank(item.propertyId()) || isBlank(item.propertyName())) {
				log.warn("supplier=B property skipped: missing propertyId or propertyName (propertyId={})",
						item.propertyId());
				continue;
			}
			hotels.add(new SupplierHotel(Supplier.B, item.propertyId(), item.propertyName(), normalizeRooms(item)));
		}
		return hotels;
	}

	private List<SupplierRoomType> normalizeRooms(PropertyItem item) {
		List<SupplierRoomType> roomTypes = new ArrayList<>();
		if (item.rooms() == null) {
			return roomTypes;
		}
		for (RoomItem room : item.rooms()) {
			if (isBlank(room.roomId()) || isBlank(room.roomName())) {
				log.warn("supplier=B room skipped: missing roomId or roomName (propertyId={}, roomId={})",
						item.propertyId(), room.roomId());
				continue;
			}
			roomTypes.add(new SupplierRoomType(room.roomId(), room.roomName(),
					occupancyOrUnknown(item.propertyId(), room.roomId(), room.maxOccupancy())));
		}
		return roomTypes;
	}

	/**
	 * B 의 resultCode 를 통일한 원인으로 바꾼다. 모르는 코드는 스펙 밖이므로 깨진 응답으로 본다.
	 */
	static FailureReason reasonOf(String resultCode) {
		return switch (resultCode) {
			case "E400" -> FailureReason.INVALID_REQUEST;
			case "E401" -> FailureReason.UNAUTHORIZED;
			case "E429" -> FailureReason.RATE_LIMITED;
			case "E500" -> FailureReason.SUPPLIER_ERROR;
			case "E503" -> FailureReason.UNAVAILABLE;
			default -> FailureReason.INVALID_RESPONSE;
		};
	}

	/**
	 * 최대 인원이 없거나 1 미만이면 기본값을 넣지 않고 미상(null)으로 둔다. 인원 필터에 추측한 값을 쓰지 않기 위해서다.
	 */
	private static Integer occupancyOrUnknown(String propertyId, String roomId, Integer maxOccupancy) {
		if (maxOccupancy == null || maxOccupancy < 1) {
			log.warn("supplier=B maxOccupancy unknown, stored as null (propertyId={}, roomId={}, value={})", propertyId,
					roomId, maxOccupancy);
			return null;
		}
		return maxOccupancy;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
