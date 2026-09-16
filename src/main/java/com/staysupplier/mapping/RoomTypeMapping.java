package com.staysupplier.mapping;

import java.time.LocalDateTime;

/**
 * room_type_mapping 행. 객실 타입 코드는 숙소 안에서만 유일하므로 (hotelId, supplierRoomTypeCode) 가 키다.
 */
public class RoomTypeMapping {

	private Long id;

	private Long hotelId;

	private String supplierRoomTypeCode;

	private String roomTypeName;

	/** 최대 수용 인원. 공급사가 주지 않으면 null (미상) */
	private Integer maxOccupancy;

	private boolean active = true;

	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;

	public RoomTypeMapping() {
	}

	public RoomTypeMapping(Long hotelId, String supplierRoomTypeCode, String roomTypeName, Integer maxOccupancy) {
		this.hotelId = hotelId;
		this.supplierRoomTypeCode = supplierRoomTypeCode;
		this.roomTypeName = roomTypeName;
		this.maxOccupancy = maxOccupancy;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getHotelId() {
		return hotelId;
	}

	public void setHotelId(Long hotelId) {
		this.hotelId = hotelId;
	}

	public String getSupplierRoomTypeCode() {
		return supplierRoomTypeCode;
	}

	public void setSupplierRoomTypeCode(String supplierRoomTypeCode) {
		this.supplierRoomTypeCode = supplierRoomTypeCode;
	}

	public String getRoomTypeName() {
		return roomTypeName;
	}

	public void setRoomTypeName(String roomTypeName) {
		this.roomTypeName = roomTypeName;
	}

	public Integer getMaxOccupancy() {
		return maxOccupancy;
	}

	public void setMaxOccupancy(Integer maxOccupancy) {
		this.maxOccupancy = maxOccupancy;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

}
