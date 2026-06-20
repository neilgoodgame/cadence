package com.cadence.api.common.error;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates every exception into the contract's error envelope:
 * {@code {"error": {"type", "code", "message", "param"}}}. One status-to-(type,code)
 * lookup table, one place that knows about the shape - controllers never build error
 * bodies themselves.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private static final Map<HttpStatus, String> CODE_BY_STATUS = Map.of(
			HttpStatus.BAD_REQUEST, "bad_request",
			HttpStatus.UNAUTHORIZED, "unauthorized",
			HttpStatus.FORBIDDEN, "forbidden",
			HttpStatus.NOT_FOUND, "not_found",
			HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed",
			HttpStatus.CONFLICT, "conflict",
			HttpStatus.CONTENT_TOO_LARGE, "payload_too_large",
			HttpStatus.TOO_MANY_REQUESTS, "rate_limited");

	private static final Map<HttpStatus, String> TYPE_BY_STATUS = Map.of(
			HttpStatus.BAD_REQUEST, "invalid_request_error",
			HttpStatus.UNAUTHORIZED, "authentication_error",
			HttpStatus.FORBIDDEN, "authorization_error",
			HttpStatus.NOT_FOUND, "not_found_error",
			HttpStatus.METHOD_NOT_ALLOWED, "invalid_request_error",
			HttpStatus.CONFLICT, "conflict_error",
			HttpStatus.CONTENT_TOO_LARGE, "invalid_request_error",
			HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error");

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
		return envelope(ex.getStatus(), ex.getMessage(), ex.getParam());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		var fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst();
		String message = fieldError.map(f -> f.getField() + ": " + f.getDefaultMessage())
				.orElse("Validation failed.");
		String param = fieldError.map(org.springframework.validation.FieldError::getField).orElse(null);
		return envelope(HttpStatus.BAD_REQUEST, message, param);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
		return envelope(HttpStatus.BAD_REQUEST, "The request body could not be read.", null);
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
		return envelope(HttpStatus.UNAUTHORIZED, ex.getMessage(), null);
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
		return envelope(HttpStatus.FORBIDDEN, ex.getMessage(), null);
	}

	/**
	 * Spring MVC's own built-in status-carrying exceptions (no matching route ->
	 * {@code NoResourceFoundException}, wrong HTTP method, unsupported media type, ...)
	 * implement the {@link org.springframework.web.ErrorResponse} marker interface rather
	 * than sharing a common exception base class, so they're handled here, in the
	 * catch-all, instead of via a dedicated {@code @ExceptionHandler} type.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		if (ex instanceof org.springframework.web.ErrorResponse errorResponse) {
			HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
			if (status == null) {
				status = HttpStatus.INTERNAL_SERVER_ERROR;
			}
			String message = (errorResponse.getBody() != null && errorResponse.getBody().getDetail() != null)
					? errorResponse.getBody().getDetail()
					: ex.getMessage();
			return envelope(status, message, null);
		}
		log.error("Unhandled exception", ex);
		return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", null);
	}

	private ResponseEntity<ErrorResponse> envelope(HttpStatus status, String message, String param) {
		String code = CODE_BY_STATUS.getOrDefault(status, "error");
		String type = TYPE_BY_STATUS.getOrDefault(status, "api_error");
		return ResponseEntity.status(status).body(ErrorResponse.of(type, code, message, param));
	}
}
