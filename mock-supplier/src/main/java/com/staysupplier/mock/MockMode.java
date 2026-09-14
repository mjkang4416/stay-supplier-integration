package com.staysupplier.mock;

import java.util.Arrays;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

enum MockMode {

	NORMAL("normal"),
	ERROR("error"),
	NO_RESPONSE("no-response");

	private final String value;

	MockMode(String value) {
		this.value = value;
	}

	String value() {
		return value;
	}

	static MockMode from(String value) {
		return Arrays.stream(values())
			.filter(mode -> mode.value.equals(value))
			.findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown mode: " + value));
	}

}
