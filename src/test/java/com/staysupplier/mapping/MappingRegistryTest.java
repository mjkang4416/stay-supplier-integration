package com.staysupplier.mapping;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.staysupplier.supplier.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class MappingRegistryTest {

	private final HotelMappingMapper hotelMappingMapper = mock(HotelMappingMapper.class);

	private final RoomTypeMappingMapper roomTypeMappingMapper = mock(RoomTypeMappingMapper.class);

	private final MappingRegistry registry = new MappingRegistry(this.hotelMappingMapper, this.roomTypeMappingMapper);

	@Test
	void isEmptyUntilLoaded() {
		assertThat(this.registry.isLoaded()).isFalse();
		assertThat(this.registry.activeHotels(Supplier.A)).isEmpty();
		assertThat(this.registry.findHotel(Supplier.A, "A-10023")).isEmpty();
	}

	@Test
	void reloadGroupsActiveHotelsBySupplierWithTheirRoomTypes() {
		given(this.hotelMappingMapper.findAllActive()).willReturn(List.of(hotel(3L, Supplier.B, "B77120", "Riverside"),
				hotel(1L, Supplier.A, "A-10023", "Riverside"), hotel(2L, Supplier.A, "A-10044", "Namsan")));
		given(this.roomTypeMappingMapper.findAllActive()).willReturn(List.of(roomType(11L, 1L, "DLX-TWN", "Deluxe Twin", 2),
				roomType(13L, 3L, "R-401", "Deluxe Twin Room", null)));

		this.registry.reload();

		assertThat(this.registry.isLoaded()).isTrue();
		assertThat(this.registry.activeHotels(Supplier.A)).extracting(MappingRegistry.HotelEntry::code)
			.containsExactly("A-10023", "A-10044");
		assertThat(this.registry.findHotel(Supplier.A, "A-10023")).get().satisfies(riverside -> {
			assertThat(riverside.id()).isEqualTo(1L);
			assertThat(riverside.roomTypes()).extracting(MappingRegistry.RoomTypeEntry::id).containsExactly(11L);
		});
		assertThat(this.registry.findRoomType(3L, "R-401")).get().satisfies(roomType -> {
			assertThat(roomType.id()).isEqualTo(13L);
			assertThat(roomType.maxOccupancy()).isNull();
		});
		assertThat(this.registry.findHotel(Supplier.A, "B77120")).isEmpty();
		assertThat(this.registry.findRoomType(1L, "R-401")).isEmpty();
	}

	private static HotelMapping hotel(Long id, Supplier supplier, String code, String name) {
		HotelMapping mapping = new HotelMapping(supplier, code, name);
		mapping.setId(id);
		return mapping;
	}

	private static RoomTypeMapping roomType(Long id, Long hotelId, String code, String name, Integer maxOccupancy) {
		RoomTypeMapping mapping = new RoomTypeMapping(hotelId, code, name, maxOccupancy);
		mapping.setId(id);
		return mapping;
	}

}
