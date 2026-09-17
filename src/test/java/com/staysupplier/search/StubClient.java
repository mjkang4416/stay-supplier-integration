package com.staysupplier.search;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import reactor.core.publisher.Mono;

import com.staysupplier.stay.AvailabilityQuery;
import com.staysupplier.stay.SupplierDailyFetchResult;
import com.staysupplier.stay.SupplierFetchResult;
import com.staysupplier.stay.SupplierHotel;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;
import com.staysupplier.supplier.SupplierCallException;
import com.staysupplier.supplier.SupplierClient;

/** 검색 테스트용 가짜 어댑터. 호출 순서대로 미리 넣어 둔 결과(성공 결과 또는 실패 원인)를 돌려준다 */
class StubClient implements SupplierClient {

	private final Supplier supplier;

	private final Deque<Object> responses = new ArrayDeque<>();

	private final AtomicInteger calls = new AtomicInteger();

	private AvailabilityQuery lastQuery;

	private Object dailyResponse = SupplierDailyFetchResult.empty();

	private final AtomicInteger dailyCalls = new AtomicInteger();

	StubClient(Supplier supplier) {
		this.supplier = supplier;
	}

	StubClient willReturn(SupplierFetchResult result) {
		this.responses.add(result);
		return this;
	}

	StubClient willFail(FailureReason reason) {
		this.responses.add(reason);
		return this;
	}

	int calls() {
		return this.calls.get();
	}

	StubClient willReturnDaily(SupplierDailyFetchResult result) {
		this.dailyResponse = result;
		return this;
	}

	StubClient willFailDaily(FailureReason reason) {
		this.dailyResponse = reason;
		return this;
	}

	int dailyCalls() {
		return this.dailyCalls.get();
	}

	AvailabilityQuery lastQuery() {
		return this.lastQuery;
	}

	@Override
	public Supplier supplier() {
		return this.supplier;
	}

	@Override
	public Mono<List<SupplierHotel>> fetchHotels() {
		return Mono.just(List.of());
	}

	@Override
	public Mono<SupplierFetchResult> fetchAvailability(AvailabilityQuery query) {
		return Mono.defer(() -> {
			this.calls.incrementAndGet();
			this.lastQuery = query;
			Object next = this.responses.size() > 1 ? this.responses.poll() : this.responses.peek();
			if (next instanceof FailureReason reason) {
				return Mono.error(new SupplierCallException(this.supplier, reason, null));
			}
			return Mono.just((SupplierFetchResult) next);
		});
	}

	@Override
	public Mono<SupplierDailyFetchResult> fetchDailyAvailability(List<String> hotelCodes, LocalDate from, LocalDate to) {
		return Mono.defer(() -> {
			this.dailyCalls.incrementAndGet();
			if (this.dailyResponse instanceof FailureReason reason) {
				return Mono.error(new SupplierCallException(this.supplier, reason, null));
			}
			return Mono.just((SupplierDailyFetchResult) this.dailyResponse);
		});
	}

}
