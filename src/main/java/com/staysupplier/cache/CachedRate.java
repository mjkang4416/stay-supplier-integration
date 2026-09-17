package com.staysupplier.cache;

/**
 * Redis Hash 필드 값. 약 25B 구분자 문자열로 두어 listpack 압축을 유지한다.
 * 형식: 재고|세금 포함 1박 요금|통화|조식(0/1)|최대 인원(없으면 빈 칸)
 */
public record CachedRate(int remainingRooms, long nightlyTotal, String currency, boolean breakfastIncluded,
		Integer maxOccupancy) {

	public String encode() {
		return this.remainingRooms + "|" + this.nightlyTotal + "|" + this.currency + "|"
				+ (this.breakfastIncluded ? "1" : "0") + "|" + ((this.maxOccupancy == null) ? "" : this.maxOccupancy);
	}

	public static CachedRate decode(String value) {
		String[] parts = value.split("\\|", -1);
		if (parts.length != 5) {
			throw new IllegalArgumentException("malformed cached rate: " + value);
		}
		return new CachedRate(Integer.parseInt(parts[0]), Long.parseLong(parts[1]), parts[2], "1".equals(parts[3]),
				parts[4].isEmpty() ? null : Integer.parseInt(parts[4]));
	}

}
