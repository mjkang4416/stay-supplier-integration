package com.staysupplier.mapping;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RoomTypeMappingMapper {

	/**
	 * 없으면 삽입, 있으면 이름·최대 인원·active 갱신. 어느 쪽이든 mapping.id 에 그 행의 id 가 채워진다.
	 */
	int upsert(RoomTypeMapping mapping);

	List<RoomTypeMapping> findActiveByHotelId(@Param("hotelId") Long hotelId);

	List<RoomTypeMapping> findAllActive();

	/**
	 * 이번 목록에 없던 객실 타입을 비활성화한다. 행은 삭제하지 않는다.
	 */
	int deactivate(@Param("hotelId") Long hotelId, @Param("codes") Collection<String> codes);

}
