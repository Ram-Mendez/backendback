package com.mendez.ram.exception;

import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final Clock clock;

	public GlobalExceptionHandler(Clock clock) {
		this.clock = clock;
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
		LOGGER.warn("API error method={} path={} status={} code={} detail={}",
				request.getMethod(), request.getRequestURI(), exception.getStatus().value(),
				exception.getCode(), exception.getMessage());

		return build(exception.getStatus(), exception.getCode(), exception.getStatus().getReasonPhrase(),
				exception.getMessage(), request, Map.of());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
			HttpServletRequest request) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		exception.getBindingResult().getFieldErrors()
				.forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

		LOGGER.warn("Validation error method={} path={} fields={}",
				request.getMethod(), request.getRequestURI(), fieldErrors);

		return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Bad Request",
				"La solicitud contiene datos invalidos.", request, fieldErrors);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception,
			HttpServletRequest request) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		exception.getConstraintViolations().forEach(violation ->
				fieldErrors.put(violation.getPropertyPath().toString(), violation.getMessage()));

		LOGGER.warn("Constraint violation method={} path={} fields={}",
				request.getMethod(), request.getRequestURI(), fieldErrors);

		return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Bad Request",
				"La solicitud contiene datos invalidos.", request, fieldErrors);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ProblemDetail> handleUnreadableMessage(HttpMessageNotReadableException exception,
			HttpServletRequest request) {
		LOGGER.warn("Malformed request method={} path={} cause={}",
				request.getMethod(), request.getRequestURI(), rootCauseMessage(exception));

		return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Bad Request",
				"El cuerpo de la solicitud no es valido.", request, Map.of());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
			HttpServletRequest request) {
		LOGGER.warn("Type mismatch method={} path={} parameter={} value={}",
				request.getMethod(), request.getRequestURI(), exception.getName(), exception.getValue());

		return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_PARAMETER", "Bad Request",
				"Parametro de solicitud no valido: " + exception.getName() + ".", request, Map.of());
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<ProblemDetail> handleMissingParameter(MissingServletRequestParameterException exception,
			HttpServletRequest request) {
		LOGGER.warn("Missing request parameter method={} path={} parameter={}",
				request.getMethod(), request.getRequestURI(), exception.getParameterName());

		return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_PARAMETER", "Bad Request",
				"Parametro de solicitud requerido ausente: " + exception.getParameterName() + ".", request, Map.of());
	}

	@ExceptionHandler(MissingServletRequestPartException.class)
	ResponseEntity<ProblemDetail> handleMissingPart(MissingServletRequestPartException exception,
			HttpServletRequest request) {
		LOGGER.warn("Missing multipart part method={} path={} part={}",
				request.getMethod(), request.getRequestURI(), exception.getRequestPartName());

		String missingPartName = exception.getRequestPartName();
		if ("files".equals(missingPartName)) {
			return build(HttpStatus.BAD_REQUEST, "ATTACHMENT_REQUIRED", "Bad Request",
					"Debes adjuntar al menos un archivo.", request, Map.of());
		}

		String detail = "Falta una parte multipart requerida: " + missingPartName + ".";
		return build(HttpStatus.BAD_REQUEST, "MISSING_MULTIPART_PART", "Bad Request", detail, request, Map.of());
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
		LOGGER.warn("Access denied method={} path={} message={}",
				request.getMethod(), request.getRequestURI(), exception.getMessage());

		return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Forbidden",
				"No tienes permisos para realizar esta accion.", request, Map.of());
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ProblemDetail> handleOptimisticLocking(OptimisticLockingFailureException exception,
			HttpServletRequest request) {
		LOGGER.warn("Optimistic lock conflict method={} path={} cause={}",
				request.getMethod(), request.getRequestURI(), rootCauseMessage(exception), exception);

		return build(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT", "Conflict",
				"La reclamacion fue modificada por otro proceso. Recarga los datos e intentalo de nuevo.",
				request, Map.of());
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException exception,
			HttpServletRequest request) {
		LOGGER.error("Data integrity conflict method={} path={} cause={}",
				request.getMethod(), request.getRequestURI(), rootCauseMessage(exception), exception);

		return build(HttpStatus.CONFLICT, "DATA_INTEGRITY_CONFLICT", "Conflict",
				"La operacion entra en conflicto con los datos existentes.", request, Map.of());
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ProblemDetail> handleMaxUploadSize(MaxUploadSizeExceededException exception,
			HttpServletRequest request) {
		LOGGER.warn("Multipart upload too large method={} path={} message={}",
				request.getMethod(), request.getRequestURI(), exception.getMessage());

		return build(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_FILE_TOO_LARGE", "Payload Too Large",
				"El archivo adjunto supera el tamano maximo permitido.", request, Map.of());
	}

	@ExceptionHandler(MultipartException.class)
	ResponseEntity<ProblemDetail> handleMultipart(MultipartException exception, HttpServletRequest request) {
		LOGGER.warn("Malformed multipart request method={} path={} cause={}",
				request.getMethod(), request.getRequestURI(), rootCauseMessage(exception));

		return build(HttpStatus.BAD_REQUEST, "MALFORMED_MULTIPART_REQUEST", "Bad Request",
				"La solicitud multipart no es valida.", request, Map.of());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
		LOGGER.error("Unexpected error method={} path={} exception={} message={}",
				request.getMethod(), request.getRequestURI(),
				exception.getClass().getName(), exception.getMessage(), exception);

		return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal Server Error",
				"Se ha producido un error inesperado.", request, Map.of());
	}

	private ResponseEntity<ProblemDetail> build(HttpStatus status, String code, String title, String detail,
			HttpServletRequest request, Map<String, String> fieldErrors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		String errorTypeName = code.toLowerCase().replace('_', '-');
		URI errorType = URI.create("urn:ram:error:" + errorTypeName);

		problem.setType(errorType);
		problem.setTitle(title);
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		problem.setProperty("timestamp", OffsetDateTime.now(clock));

		if (!fieldErrors.isEmpty()) {
			problem.setProperty("fieldErrors", fieldErrors);
		}

		return ResponseEntity.status(status).body(problem);
	}

	private static String rootCauseMessage(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null) {
			current = current.getCause();
		}

		return current.getClass().getSimpleName() + ": " + current.getMessage();
	}
}
