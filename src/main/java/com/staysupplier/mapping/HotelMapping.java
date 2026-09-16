package com.staysupplier.mapping;

import java.time.LocalDateTime;

import com.staysupplier.supplier.Supplier;

/**
 * hotel_mapping 행. (supplier, supplierHotelCode) 는 항상 같은 id 에 대응한다.
 */
public class HotelMapping {

	private Long id;

	private Supplier supplier;

	private String supplierHotelCode;

	private String hotelName;

	private boolean active = true;

	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;

	public HotelMapping() {
	}

	public HotelMapping(Supplier supplier, String supplierHotelCode, String hotelName) {
		this.supplier = supplier;
		this.supplierHotelCode = supplierHotelCode;
		this.hotelName = hotelName;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Supplier getSupplier() {
		return supplier;
	}

	public void setSupplier(Supplier supplier) {
		this.supplier = supplier;
	}

	public String getSupplierHotelCode() {
		return supplierHotelCode;
	}

	public void setSupplierHotelCode(String supplierHotelCode) {
		this.supplierHotelCode = supplierHotelCode;
	}

	public String getHotelName() {
		return hotelName;
	}

	public void setHotelName(String hotelName) {
		this.hotelName = hotelName;
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
