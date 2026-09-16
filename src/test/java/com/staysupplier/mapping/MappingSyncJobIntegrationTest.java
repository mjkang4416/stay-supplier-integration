package com.staysupplier.mapping;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.staysupplier.mapping.MappingSyncResult.SupplierSyncResult;
import com.staysupplier.stay.SupplierHotel;
import com.staysupplier.stay.SupplierRoomType;
import com.staysupplier.supplier.FailureReason;
import com.staysupplier.supplier.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 크론잡 델타 반영을 실제 MySQL 에서 검증한다: 추가·변경·사라짐, 실패 공급사 건너뛰기, 재시도, 비활성화 보류.
 */
@SpringBootTest
@Testcontainers
class MappingSyncJobIntegrationTest {

	@Container
	@ServiceConnection
	static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

	static final SupplierHotel RIVERSIDE_A = new SupplierHotel(Supplier.A, "A-10023", "Riverside Hotel Seoul",
			List.of(new SupplierRoomType("DLX-TWN", "Deluxe Twin", 2)));

	static final SupplierHotel NAMSAN_A = new SupplierHotel(Supplier.A, "A-10044", "Namsan Garden Stay",
			List.of(new SupplierRoomType("STD-DBL", "Standard Double", 2)));

	static final SupplierHotel RIVERSIDE_B = new SupplierHotel(Supplier.B, "B77120", "Riverside Hotel Seoul",
			List.of(new SupplierRoomType("R-401", "Deluxe Twin Room", 2)));

	@Autowired
	HotelMappingMapper hotelMappingMapper;

	@Autowired
	RoomTypeMappingMapper roomTypeMappingMapper;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanTables() {
		this.jdbcTemplate.update("DELETE FROM room_type_mapping");
		this.jdbcTemplate.update("DELETE FROM hotel_mapping");
	}

	private MappingSyncJob job(StubSupplierClient... clients) {
		MappingSyncProperties properties = new MappingSyncProperties(2, Duration.ofMillis(1), 0.5);
		return new MappingSyncJob(List.of(clients), this.hotelMappingMapper, this.roomTypeMappingMapper,
				this.transactionManager, properties);
	}

	@Test
	void firstRunInsertsHotelsAndRoomTypesOfEverySupplier() {
		MappingSyncResult result = job(new StubSupplierClient(Supplier.A).willReturn(List.of(RIVERSIDE_A, NAMSAN_A)),
				new StubSupplierClient(Supplier.B).willReturn(List.of(RIVERSIDE_B))).run();

		assertThat(result.allSucceeded()).isTrue();
		assertThat(resultOf(result, Supplier.A)).satisfies(a -> {
			assertThat(a.hotelsAdded()).isEqualTo(2);
			assertThat(a.roomTypesAdded()).isEqualTo(2);
		});
		assertThat(resultOf(result, Supplier.B).hotelsAdded()).isEqualTo(1);
		assertThat(this.hotelMappingMapper.findAllActive()).extracting(HotelMapping::getSupplierHotelCode)
			.containsExactlyInAnyOrder("A-10023", "A-10044", "B77120");
	}

	@Test
	void secondRunAppliesOnlyTheDeltaAndKeepsIdentifiers() {
		job(new StubSupplierClient(Supplier.A).willReturn(List.of(RIVERSIDE_A, NAMSAN_A))).run();
		Long riversideId = activeHotel("A-10023").getId();

		SupplierHotel renamed = new SupplierHotel(Supplier.A, "A-10023", "Riverside Hotel Seoul (new)",
				List.of(new SupplierRoomType("DLX-TWN", "Deluxe Twin", 3), new SupplierRoomType("STE", "Suite", 4)));
		MappingSyncResult result = job(new StubSupplierClient(Supplier.A).willReturn(List.of(renamed))).run();

		SupplierSyncResult a = resultOf(result, Supplier.A);
		assertThat(a.hotelsAdded()).isZero();
		assertThat(a.hotelsChanged()).isEqualTo(1);
		assertThat(a.hotelsDeactivated()).isEqualTo(1);
		assertThat(a.roomTypesAdded()).isEqualTo(1);
		assertThat(a.roomTypesChanged()).isEqualTo(1);
		assertThat(a.deactivationHeld()).isFalse();
		assertThat(activeHotel("A-10023").getId()).isEqualTo(riversideId);
		assertThat(activeHotel("A-10023").getHotelName()).isEqualTo("Riverside Hotel Seoul (new)");
		assertThat(this.hotelMappingMapper.findActiveBySupplier(Supplier.A))
			.extracting(HotelMapping::getSupplierHotelCode)
			.containsExactly("A-10023");
		assertThat(this.roomTypeMappingMapper.findActiveByHotelId(riversideId))
			.extracting(RoomTypeMapping::getSupplierRoomTypeCode)
			.containsExactlyInAnyOrder("DLX-TWN", "STE");
	}

	@Test
	void failedSupplierIsSkippedAndItsExistingMappingIsKept() {
		job(new StubSupplierClient(Supplier.B).willReturn(List.of(RIVERSIDE_B))).run();

		MappingSyncResult result = job(new StubSupplierClient(Supplier.A).willReturn(List.of(RIVERSIDE_A)),
				new StubSupplierClient(Supplier.B).willFail(FailureReason.UNAVAILABLE)).run();

		assertThat(result.allSucceeded()).isFalse();
		assertThat(resultOf(result, Supplier.B).failureReason()).isEqualTo(FailureReason.UNAVAILABLE);
		assertThat(resultOf(result, Supplier.A).hotelsAdded()).isEqualTo(1);
		assertThat(this.hotelMappingMapper.findActiveBySupplier(Supplier.B))
			.extracting(HotelMapping::getSupplierHotelCode)
			.containsExactly("B77120");
	}

	@Test
	void retriesTransientFailureWithFixedDelayThenSucceeds() {
		StubSupplierClient a = new StubSupplierClient(Supplier.A).willFail(FailureReason.TIMEOUT)
			.willReturn(List.of(RIVERSIDE_A));

		MappingSyncResult result = job(a).run();

		assertThat(a.calls()).isEqualTo(2);
		assertThat(resultOf(result, Supplier.A).succeeded()).isTrue();
	}

	@Test
	void doesNotRetryFailuresThatWouldRepeat() {
		StubSupplierClient a = new StubSupplierClient(Supplier.A).willFail(FailureReason.INVALID_REQUEST)
			.willReturn(List.of(RIVERSIDE_A));

		MappingSyncResult result = job(a).run();

		assertThat(a.calls()).isEqualTo(1);
		assertThat(resultOf(result, Supplier.A).failureReason()).isEqualTo(FailureReason.INVALID_REQUEST);
	}

	@Test
	void holdsDeactivationWhenMoreThanHalfOfActiveHotelsDisappear() {
		job(new StubSupplierClient(Supplier.A).willReturn(List.of(RIVERSIDE_A, NAMSAN_A))).run();

		MappingSyncResult result = job(new StubSupplierClient(Supplier.A).willReturn(List.of())).run();

		SupplierSyncResult a = resultOf(result, Supplier.A);
		assertThat(a.deactivationHeld()).isTrue();
		assertThat(a.hotelsDeactivated()).isZero();
		assertThat(this.hotelMappingMapper.findActiveBySupplier(Supplier.A)).hasSize(2);
	}

	private HotelMapping activeHotel(String code) {
		return this.hotelMappingMapper.findAllActive()
			.stream()
			.filter(hotel -> hotel.getSupplierHotelCode().equals(code))
			.findFirst()
			.orElseThrow();
	}

	private static SupplierSyncResult resultOf(MappingSyncResult result, Supplier supplier) {
		return result.results().stream().filter(r -> r.supplier() == supplier).findFirst().orElseThrow();
	}

}
