package com.staysupplier.mapping;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.staysupplier.supplier.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매핑 테이블의 핵심 규칙을 실제 MySQL 에서 검증한다.
 * 같은 공급사 코드는 다시 저장해도 같은 내부 식별자를 받고, 비활성화는 행을 지우지 않는다.
 */
@SpringBootTest
@Testcontainers
class MappingMapperIntegrationTest {

	@Container
	@ServiceConnection
	static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

	@Autowired
	HotelMappingMapper hotelMappingMapper;

	@Autowired
	RoomTypeMappingMapper roomTypeMappingMapper;

	@Test
	void sameSupplierCodeAlwaysGetsSameHotelId() {
		HotelMapping first = new HotelMapping(Supplier.A, "A-10023", "Riverside Hotel Seoul");
		hotelMappingMapper.upsert(first);

		HotelMapping again = new HotelMapping(Supplier.A, "A-10023", "Riverside Hotel Seoul (renamed)");
		hotelMappingMapper.upsert(again);

		assertThat(first.getId()).isNotNull();
		assertThat(again.getId()).isEqualTo(first.getId());

		List<HotelMapping> active = hotelMappingMapper.findActiveBySupplier(Supplier.A);
		assertThat(active).extracting(HotelMapping::getSupplierHotelCode).contains("A-10023");
		assertThat(active).filteredOn(h -> h.getSupplierHotelCode().equals("A-10023"))
			.extracting(HotelMapping::getHotelName)
			.containsExactly("Riverside Hotel Seoul (renamed)");
	}

	@Test
	void differentSuppliersGetDifferentIdsForSameHotel() {
		HotelMapping a = new HotelMapping(Supplier.A, "A-99999", "Same Hotel");
		HotelMapping b = new HotelMapping(Supplier.B, "B99999", "Same Hotel");
		hotelMappingMapper.upsert(a);
		hotelMappingMapper.upsert(b);

		assertThat(a.getId()).isNotEqualTo(b.getId());
	}

	@Test
	void deactivateKeepsRowAndReactivateRestoresSameId() {
		HotelMapping hotel = new HotelMapping(Supplier.A, "A-20001", "Vanishing Stay");
		hotelMappingMapper.upsert(hotel);
		Long originalId = hotel.getId();

		int changed = hotelMappingMapper.deactivate(Supplier.A, List.of("A-20001"));
		assertThat(changed).isEqualTo(1);
		assertThat(hotelMappingMapper.findActiveBySupplier(Supplier.A))
			.extracting(HotelMapping::getSupplierHotelCode)
			.doesNotContain("A-20001");

		HotelMapping back = new HotelMapping(Supplier.A, "A-20001", "Vanishing Stay");
		hotelMappingMapper.upsert(back);
		assertThat(back.getId()).isEqualTo(originalId);
		assertThat(hotelMappingMapper.findActiveBySupplier(Supplier.A))
			.extracting(HotelMapping::getSupplierHotelCode)
			.contains("A-20001");
	}

	@Test
	void roomTypeCodeIsUniqueOnlyWithinHotel() {
		HotelMapping hotel1 = new HotelMapping(Supplier.A, "A-30001", "Hotel One");
		HotelMapping hotel2 = new HotelMapping(Supplier.A, "A-30002", "Hotel Two");
		hotelMappingMapper.upsert(hotel1);
		hotelMappingMapper.upsert(hotel2);

		RoomTypeMapping rt1 = new RoomTypeMapping(hotel1.getId(), "DLX-TWN", "Deluxe Twin", 2);
		RoomTypeMapping rt2 = new RoomTypeMapping(hotel2.getId(), "DLX-TWN", "Deluxe Twin", 3);
		roomTypeMappingMapper.upsert(rt1);
		roomTypeMappingMapper.upsert(rt2);
		assertThat(rt1.getId()).isNotEqualTo(rt2.getId());

		RoomTypeMapping rt1Again = new RoomTypeMapping(hotel1.getId(), "DLX-TWN", "Deluxe Twin", 4);
		roomTypeMappingMapper.upsert(rt1Again);
		assertThat(rt1Again.getId()).isEqualTo(rt1.getId());
		assertThat(roomTypeMappingMapper.findActiveByHotelId(hotel1.getId()))
			.singleElement()
			.satisfies(rt -> assertThat(rt.getMaxOccupancy()).isEqualTo(4));
	}

}
