package com.staysupplier.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import com.staysupplier.mapping.MappingRegistry;
import com.staysupplier.mapping.MappingRegistry.HotelEntry;
import com.staysupplier.mapping.MappingRegistry.RoomTypeEntry;
import com.staysupplier.stay.SupplierDailyFetchResult;
import com.staysupplier.stay.SupplierDailyOffer;
import com.staysupplier.stay.SupplierFetchResult.ChunkFailure;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;
import com.staysupplier.supplier.SupplierCallException;
import com.staysupplier.supplier.SupplierClient;

/**
 * 요금·재고 갱신 잡. 주기마다 인메모리 매핑의 공급사별 숙소 코드를 50개씩 어댑터에 넘겨 오늘~+N일의 날짜별 값을 받아 Redis 에 미리 채운다.
 * 공급사 하나가 실패하면 그 공급사만 건너뛰고 상태 키에 원인을 남긴다. 검색은 이 상태를 읽어 failures 로 드러낸다.
 */
public class AvailabilityRefreshJob {

	private static final Logger log = LoggerFactory.getLogger(AvailabilityRefreshJob.class);

	private final List<SupplierClient> clients;

	private final MappingRegistry registry;

	private final AvailabilityCache cache;

	private final CacheProperties properties;

	private final Clock clock;

	public AvailabilityRefreshJob(List<SupplierClient> clients, MappingRegistry registry, AvailabilityCache cache,
			CacheProperties properties, Clock clock) {
		this.clients = List.copyOf(clients);
		this.registry = registry;
		this.cache = cache;
		this.properties = properties;
		this.clock = clock;
	}

	public void refresh() {
		LocalDate from = LocalDate.now(this.clock);
		LocalDate to = from.plusDays(this.properties.windowDays());
		List<Outcome> outcomes = Flux.fromIterable(this.clients)
			.flatMap(client -> fetch(client, from, to))
			.collectList()
			.block();
		Instant now = Instant.now(this.clock);
		for (Outcome outcome : outcomes) {
			if (outcome.skipped()) {
				continue;
			}
			try {
				if (outcome.failure() != null) {
					log.error("cache refresh supplier={} failed reason={} (values kept until TTL)", outcome.supplier(),
							outcome.failure());
					this.cache.recordStatus(outcome.supplier(), false, outcome.failure(), now);
					continue;
				}
				int hotels = store(outcome.supplier(), outcome.result(), now);
				for (ChunkFailure chunk : outcome.result().failures()) {
					log.error("cache refresh supplier={} chunk failed hotels={} reason={}", outcome.supplier(),
							chunk.hotelCodes().size(), chunk.reason());
				}
				this.cache.recordStatus(outcome.supplier(), true, null, now);
				log.info("cache refresh supplier={} hotels={} window={}~{} chunkFailures={}", outcome.supplier(), hotels,
						from, to, outcome.result().failures().size());
			}
			catch (RuntimeException ex) {
				// Redis 장애: 이번 바퀴만 실패로 두고 다음 주기에 다시 한다. 검색은 저하 모드로 공급사를 직접 부른다
				log.error("cache refresh supplier={} could not write to redis", outcome.supplier(), ex);
			}
		}
	}

	private Mono<Outcome> fetch(SupplierClient client, LocalDate from, LocalDate to) {
		Supplier supplier = client.supplier();
		List<String> codes = this.registry.activeHotels(supplier).stream().map(HotelEntry::code).toList();
		if (codes.isEmpty()) {
			return Mono.just(Outcome.skipped(supplier));
		}
		return client.fetchDailyAvailability(codes, from, to)
			.retryWhen(rateLimitBackoff(supplier))
			.map(result -> Outcome.success(supplier, result))
			.onErrorResume(SupplierCallException.class, ex -> Mono.just(Outcome.failure(supplier, ex.getReason())));
	}

	/**
	 * 429 만 지수 백오프(1→2→4→… 초, 60초 상한, 최대 5회)로 물러났다 다시 한다. 예산 절반은 SupplierRateLimiter 가 429 마다 적용한다.
	 * 다른 실패는 다음 주기에 맡긴다. Retry-After 값을 대기에 쓰는 것은 확장 항목이다.
	 */
	private Retry rateLimitBackoff(Supplier supplier) {
		return Retry.backoff(this.properties.rateLimitRetries(), this.properties.rateLimitBackoff())
			.maxBackoff(Duration.ofSeconds(60))
			.jitter(0)
			.filter(error -> error instanceof SupplierCallException ex && ex.getReason() == FailureReason.RATE_LIMITED)
			.doBeforeRetry(signal -> log.warn("cache refresh supplier={} rate limited, backing off (attempt {})", supplier,
					signal.totalRetries() + 1))
			.onRetryExhaustedThrow((spec, signal) -> signal.failure());
	}

	/** 공급사 코드를 내부 식별자로 바꿔 숙소별 Hash 로 쓴다. 매핑에 없는 코드는 버린다 */
	private int store(Supplier supplier, SupplierDailyFetchResult result, Instant now) {
		Map<Long, Map<String, CachedRate>> perHotel = new HashMap<>();
		for (SupplierDailyOffer offer : result.offers()) {
			Optional<HotelEntry> hotel = this.registry.findHotel(supplier, offer.hotelCode());
			Optional<RoomTypeEntry> roomType = hotel.flatMap(h -> this.registry.findRoomType(h.id(), offer.roomTypeCode()));
			if (hotel.isEmpty() || roomType.isEmpty()) {
				log.warn("cache refresh offer skipped: unmapped supplier={} hotelCode={} roomTypeCode={}", supplier,
						offer.hotelCode(), offer.roomTypeCode());
				continue;
			}
			perHotel.computeIfAbsent(hotel.get().id(), id -> new HashMap<>())
				.put(AvailabilityCache.field(roomType.get().id(), offer.date()),
						new CachedRate(offer.remainingRooms(), offer.nightlyTotal(), offer.currency(),
								offer.breakfastIncluded(), offer.maxOccupancy()));
		}
		perHotel.forEach((hotelId, fields) -> this.cache.write(hotelId, fields, now));
		return perHotel.size();
	}

	private record Outcome(Supplier supplier, boolean skipped, SupplierDailyFetchResult result, FailureReason failure) {

		static Outcome skipped(Supplier supplier) {
			return new Outcome(supplier, true, null, null);
		}

		static Outcome success(Supplier supplier, SupplierDailyFetchResult result) {
			return new Outcome(supplier, false, result, null);
		}

		static Outcome failure(Supplier supplier, FailureReason reason) {
			return new Outcome(supplier, false, null, reason);
		}

	}

}
