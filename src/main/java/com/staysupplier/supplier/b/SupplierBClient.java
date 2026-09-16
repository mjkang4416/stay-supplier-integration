package com.staysupplier.supplier.b;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import com.staysupplier.stay.SupplierHotel;
import com.staysupplier.stay.SupplierRoomType;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;
import com.staysupplier.supplier.SupplierCallException;
import com.staysupplier.supplier.SupplierClient;
import com.staysupplier.supplier.SupplierFailures;
import com.staysupplier.supplier.SupplierWebClients;
import com.staysupplier.supplier.b.SupplierBResponses.Envelope;
import com.staysupplier.supplier.b.SupplierBResponses.PropertiesData;
import com.staysupplier.supplier.b.SupplierBResponses.PropertyItem;
import com.staysupplier.supplier.b.SupplierBResponses.RoomItem;

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

	private final WebClient webClient;

	public SupplierBClient(SupplierWebClients webClients) {
		this.webClient = webClients.of(Supplier.B);
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
			.map(this::normalize);
	}

	private List<SupplierHotel> normalize(Envelope<PropertiesData> envelope) {
		String resultCode = envelope.resultCode();
		if (resultCode == null) {
			throw new SupplierCallException(Supplier.B, FailureReason.INVALID_RESPONSE, "missing resultCode");
		}
		if (!SupplierBResponses.SUCCESS_CODE.equals(resultCode)) {
			throw new SupplierCallException(Supplier.B, reasonOf(resultCode), resultCode);
		}
		// 성공인데 data.items 구조가 없으면 "0건"이 아니라 깨진 응답이다. 0건은 items 가 빈 목록으로 온다
		if (envelope.data() == null || envelope.data().items() == null) {
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
