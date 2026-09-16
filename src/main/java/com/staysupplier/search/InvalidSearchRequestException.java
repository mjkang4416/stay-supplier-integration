package com.staysupplier.search;

/** 검색 요청이 잘못됨 (400). 공급사를 부르기 전에 던진다 */
public class InvalidSearchRequestException extends RuntimeException {

	public InvalidSearchRequestException(String message) {
		super(message);
	}

}
