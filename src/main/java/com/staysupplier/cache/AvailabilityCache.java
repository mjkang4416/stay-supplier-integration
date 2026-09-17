package com.staysupplier.cache;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;

/**
 * 요금·재고 캐시. 숙소당 Hash 하나(키 stay:v1:{hotelId}), 필드 {roomTypeId}:{yyyyMMdd}, 값 {@link CachedRate}.
 * 검색은 HMGET 으로 날짜 범위를 한 번에 읽고, 갱신 잡은 HSET 파이프라인으로 채운다. 요금·재고는 DB 에 저장하지 않는다.
 */
public class AvailabilityCache {

	static final String KEY_PREFIX = "stay:v1:";

	static final String STATUS_PREFIX = "stay:v1:status:";

	static final String REFRESHED_AT = "_refreshedAt";

	static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private final StringRedisTemplate redis;

	private final Duration ttl;

	public AvailabilityCache(StringRedisTemplate redis, Duration ttl) {
		this.redis = redis;
		this.ttl = ttl;
	}

	public static String field(long roomTypeId, LocalDate date) {
		return roomTypeId + ":" + DATE.format(date);
	}

	/**
	 * 숙소 하나의 Hash 를 통째로 교체하고 TTL 을 다시 건다 (MULTI: DEL → HSET → EXPIRE).
	 * 필드를 덧쓰기만 하면 TTL 이 매번 연장되어 지난 날짜·사라진 객실 타입 필드가 영원히 남으므로 교체한다.
	 */
	public void write(long hotelId, Map<String, CachedRate> fields, Instant refreshedAt) {
		Map<String, String> encoded = new HashMap<>();
		fields.forEach((field, rate) -> encoded.put(field, rate.encode()));
		encoded.put(REFRESHED_AT, refreshedAt.toString());
		String key = KEY_PREFIX + hotelId;
		Duration expiry = this.ttl;
		this.redis.execute(new SessionCallback<List<Object>>() {
			@Override
			@SuppressWarnings({ "unchecked", "rawtypes" })
			public List<Object> execute(RedisOperations operations) {
				operations.multi();
				operations.delete(key);
				operations.opsForHash().putAll(key, encoded);
				operations.expire(key, expiry);
				return operations.exec();
			}
		});
	}

	/**
	 * 여러 숙소의 필드를 파이프라인으로 읽는다. 키가 없는 숙소는 결과에 없고, 값이 없는 필드는 null 이다.
	 */
	public Map<Long, Map<String, CachedRate>> read(Collection<Long> hotelIds, List<String> fields) {
		List<Long> ids = new ArrayList<>(hotelIds);
		if (ids.isEmpty() || fields.isEmpty()) {
			return Map.of();
		}
		byte[][] fieldBytes = fields.stream().map(f -> f.getBytes()).toArray(byte[][]::new);
		List<Object> raw = this.redis.executePipelined((RedisCallback<Object>) connection -> {
			for (Long id : ids) {
				connection.hashCommands().hMGet((KEY_PREFIX + id).getBytes(), fieldBytes);
			}
			return null;
		});
		Map<Long, Map<String, CachedRate>> result = new HashMap<>();
		for (int i = 0; i < ids.size(); i++) {
			@SuppressWarnings("unchecked")
			List<String> values = (List<String>) raw.get(i);
			if (values == null || values.stream().allMatch(v -> v == null)) {
				continue; // 키 자체가 없거나 이 필드가 하나도 없음 → 캐시 없음
			}
			Map<String, CachedRate> perField = new HashMap<>();
			for (int f = 0; f < fields.size(); f++) {
				String value = values.get(f);
				perField.put(fields.get(f), (value == null) ? null : CachedRate.decode(value));
			}
			result.put(ids.get(i), perField);
		}
		return result;
	}

	public void evict(long hotelId) {
		this.redis.delete(KEY_PREFIX + hotelId);
	}

	/** 공급사별 마지막 갱신 결과. 검색이 읽어 failures 에 드러낸다 */
	public void recordStatus(Supplier supplier, boolean succeeded, FailureReason reason, Instant at) {
		Map<String, String> fields = new HashMap<>();
		if (succeeded) {
			fields.put("lastSuccessAt", at.toString());
		}
		else {
			fields.put("lastFailureAt", at.toString());
			fields.put("lastFailureReason", reason.name());
		}
		this.redis.opsForHash().putAll(STATUS_PREFIX + supplier.name(), fields);
	}

	public Optional<RefreshStatus> status(Supplier supplier) {
		Map<Object, Object> raw = this.redis.opsForHash().entries(STATUS_PREFIX + supplier.name());
		if (raw.isEmpty()) {
			return Optional.empty();
		}
		Instant success = parse(raw.get("lastSuccessAt"));
		Instant failure = parse(raw.get("lastFailureAt"));
		FailureReason reason = (raw.get("lastFailureReason") == null) ? null
				: FailureReason.valueOf(raw.get("lastFailureReason").toString());
		return Optional.of(new RefreshStatus(success, failure, reason));
	}

	private static Instant parse(Object value) {
		return (value == null) ? null : Instant.parse(value.toString());
	}

	/**
	 * @param lastSuccessAt 마지막 성공 시각. 없으면 null
	 * @param lastFailureAt 마지막 실패 시각. 없으면 null
	 */
	public record RefreshStatus(Instant lastSuccessAt, Instant lastFailureAt, FailureReason lastFailureReason) {

		/** 마지막 시도가 실패였는지 */
		public boolean failing() {
			return this.lastFailureAt != null && (this.lastSuccessAt == null || this.lastFailureAt.isAfter(this.lastSuccessAt));
		}

	}

}
