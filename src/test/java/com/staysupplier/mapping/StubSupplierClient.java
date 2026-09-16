package com.staysupplier.mapping;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import reactor.core.publisher.Mono;

import com.staysupplier.stay.AvailabilityQuery;
import com.staysupplier.stay.SupplierFetchResult;
import com.staysupplier.stay.SupplierHotel;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;
import com.staysupplier.supplier.SupplierCallException;
import com.staysupplier.supplier.SupplierClient;

/**
 * 크론잡 테스트용 가짜 어댑터. 호출 순서대로 미리 넣어 둔 응답(성공 목록 또는 실패 원인)을 돌려준다.
 */
class StubSupplierClient implements SupplierClient {

	private final Supplier supplier;

	private final Deque<Object> responses = new ArrayDeque<>();

	private final AtomicInteger calls = new AtomicInteger();

	StubSupplierClient(Supplier supplier) {
		this.supplier = supplier;
	}

	StubSupplierClient willReturn(List<SupplierHotel> hotels) {
		this.responses.add(hotels);
		return this;
	}

	StubSupplierClient willFail(FailureReason reason) {
		this.responses.add(reason);
		return this;
	}

	int calls() {
		return this.calls.get();
	}

	@Override
	public Supplier supplier() {
		return this.supplier;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<List<SupplierHotel>> fetchHotels() {
		return Mono.defer(() -> {
			this.calls.incrementAndGet();
			Object next = this.responses.size() > 1 ? this.responses.poll() : this.responses.peek();
			if (next instanceof FailureReason reason) {
				return Mono.error(new SupplierCallException(this.supplier, reason, null));
			}
			return Mono.just((List<SupplierHotel>) next);
		});
	}

	@Override
	public Mono<SupplierFetchResult> fetchAvailability(AvailabilityQuery query) {
		return Mono.just(SupplierFetchResult.empty());
	}

}
