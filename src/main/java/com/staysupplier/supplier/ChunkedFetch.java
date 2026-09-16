package com.staysupplier.supplier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.staysupplier.stay.SupplierFetchResult;
import com.staysupplier.stay.SupplierFetchResult.ChunkFailure;
import com.staysupplier.stay.SupplierRoomOffer;

/**
 * 숙소 코드 목록을 공급사 한도만큼 잘라 병렬로 호출하고, 묶음 단위로 실패를 가둔 뒤 하나의 결과로 합친다.
 * 묶음 하나가 실패해도 다른 묶음의 결과는 살리고, 전부 실패했을 때만 예외를 던진다.
 */
public final class ChunkedFetch {

	private static final Logger log = LoggerFactory.getLogger(ChunkedFetch.class);

	private ChunkedFetch() {
	}

	public static Mono<SupplierFetchResult> fetch(Supplier supplier, List<String> hotelCodes, int chunkSize,
			Function<List<String>, Mono<List<SupplierRoomOffer>>> call) {
		List<List<String>> chunks = split(hotelCodes, chunkSize);
		if (chunks.isEmpty()) {
			return Mono.just(SupplierFetchResult.empty());
		}
		return Flux.fromIterable(chunks)
			.flatMap(chunk -> call.apply(chunk)
				.map(offers -> new ChunkOutcome(chunk, offers, null))
				.onErrorResume(SupplierCallException.class, ex -> {
					log.warn("supplier={} chunk failed hotels={} reason={}", supplier, chunk.size(), ex.getReason());
					return Mono.just(new ChunkOutcome(chunk, List.of(), ex));
				}))
			.collectList()
			.flatMap(outcomes -> assemble(supplier, chunks.size(), outcomes));
	}

	private static Mono<SupplierFetchResult> assemble(Supplier supplier, int chunkCount, List<ChunkOutcome> outcomes) {
		List<SupplierRoomOffer> offers = new ArrayList<>();
		List<ChunkFailure> failures = new ArrayList<>();
		SupplierCallException firstFailure = null;
		for (ChunkOutcome outcome : outcomes) {
			if (outcome.failure() == null) {
				offers.addAll(outcome.offers());
			}
			else {
				failures.add(new ChunkFailure(supplier, outcome.hotelCodes(), outcome.failure().getReason()));
				firstFailure = (firstFailure == null) ? outcome.failure() : firstFailure;
			}
		}
		if (failures.size() == chunkCount) {
			return Mono.error(firstFailure);
		}
		return Mono.just(new SupplierFetchResult(offers, failures));
	}

	static List<List<String>> split(List<String> codes, int chunkSize) {
		List<List<String>> chunks = new ArrayList<>();
		for (int from = 0; from < codes.size(); from += chunkSize) {
			chunks.add(List.copyOf(codes.subList(from, Math.min(from + chunkSize, codes.size()))));
		}
		return chunks;
	}

	private record ChunkOutcome(List<String> hotelCodes, List<SupplierRoomOffer> offers, SupplierCallException failure) {
	}

}
