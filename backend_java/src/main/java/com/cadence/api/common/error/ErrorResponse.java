package com.cadence.api.common.error;

/** Wire shape for every non-2xx response: {@code {"error": {"type","code","message","param"}}}. */
public record ErrorResponse(ErrorBody error) {

	public record ErrorBody(String type, String code, String message, String param) {
	}

	public static ErrorResponse of(String type, String code, String message, String param) {
		return new ErrorResponse(new ErrorBody(type, code, message, param));
	}
}
