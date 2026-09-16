package com.staysupplier.mapping;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.staysupplier.supplier.Supplier;

@Mapper
public interface HotelMappingMapper {

	/**
	 * 없으면 삽입, 있으면 이름·active 갱신. 어느 쪽이든 mapping.id 에 그 행의 id 가 채워진다.
	 */
	int upsert(HotelMapping mapping);

	List<HotelMapping> findActiveBySupplier(@Param("supplier") Supplier supplier);

	List<HotelMapping> findAllActive();

	/**
	 * 이번 목록에 없던 숙소를 비활성화한다. 행은 삭제하지 않는다.
	 */
	int deactivate(@Param("supplier") Supplier supplier, @Param("codes") Collection<String> codes);

}
