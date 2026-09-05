package com.mendez.ram.exception;

import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private final Clock clock;

	public GlobalExceptionHandler(Clock clock) {
		this.clock = clock;
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
		return build(exception.getStatus(), exception.getCode(), exception.getStatus().getReasonPhrase(),
				exception.getMessage(), request, Map.of());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
			HttpServletRequest request) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		exception.getBindingResult().getFieldErrors()
				.forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));
		return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Bad Request",
				"La solicitud contiene datos invalidos.", request, fieldErrors);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception,
			HttpServletRequest request) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		exception.getConstraintViolations().forEach(violation ->
				fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage()));
		return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Bad Request",
				"La solicitud contiene datos invalidos.", request, fieldErrors);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ProblemDetail> handleUnreadableMessage(HttpMessageNotReadableException exception,
			HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Bad Request",
				"El cuerpo de la solicitud no es valido.", request, Map.of());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
			HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_PARAMETER", "Bad Request",
				"Parametro de solicitud no valido: " + exception.getName() + ".", request, Map.of());
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
		return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Forbidden",
				"No tienes permisos para realizar esta accion.", request, Map.of());
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ProblemDetail> handleOptimisticLocking(OptimisticLockingFailureException exception,
			HttpServletRequest request) {
		return build(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT", "Conflict",
				"La reclamacion fue modificada por otro proceso. Recarga los datos e intentalo de nuevo.",
				request, Map.of());
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException exception,
			HttpServletRequest request) {
		return build(HttpStatus.CONFLICT, "DATA_INTEGRITY_CONFLICT", "Conflict",
				"La operacion entra en conflicto con los datos existentes.", request, Map.of());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
		return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal Server Error",
				"Se ha producido un error inesperado.", request, Map.of());
	}

	private ResponseEntity<ProblemDetail> build(HttpStatus status, String code, String title, String detail,
			HttpServletRequest request, Map<String, String> fieldErrors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("urn:ram:error:" + code.toLowerCase().replace('_', '-')));
		problem.setTitle(title);
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		problem.setProperty("timestamp", OffsetDateTime.now(clock));
		if (!fieldErrors.isEmpty()) {
			problem.setProperty("fieldErrors", fieldErrors);
		}
		return ResponseEntity.status(status).body(problem);
	}
}
