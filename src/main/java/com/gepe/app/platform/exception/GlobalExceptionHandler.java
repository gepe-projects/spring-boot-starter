package com.gepe.app.platform.exception;

import com.gepe.app.platform.config.i18n.MessageHelper;
import com.gepe.app.platform.web.response.ErrorResponse;
import com.gepe.app.platform.web.response.ValidationError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidFormatException;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

	private final MessageHelper messageHelper;

	/** DTO validation error */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		List<ValidationError> errors =
				exception.getBindingResult().getFieldErrors().stream()
						.map(this::toValidationError)
						.toList();

		ErrorResponse response = new ErrorResponse(messageHelper.get("validation.failed"), errors);

		return ResponseEntity.badRequest().body(response);
	}

	/** Authorization denied error */
	@ExceptionHandler(AuthorizationDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAuthorizationDeniedException(
			AuthorizationDeniedException exception) {
		var errorResponse = new ErrorResponse(messageHelper.get("http.forbidden"), null);
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
	}

	/** Resource not found error */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleValidation(NoResourceFoundException exception) {
		var errorResponse = new ErrorResponse(messageHelper.get("http.not_found"), null);
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
	}

	/** Request param validation error */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(
			ConstraintViolationException exception) {
		List<ValidationError> errors =
				exception.getConstraintViolations().stream().map(this::toValidationError).toList();

		ErrorResponse response = new ErrorResponse(messageHelper.get("validation.failed"), errors);

		return ResponseEntity.badRequest().body(response);
	}

	/** Invalid JSON body */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleInvalidBody(
			HttpMessageNotReadableException exception) {
		Throwable cause = exception.getCause();
		log.warn("cause class: {}", cause == null ? "null" : cause.getClass().getName());
		log.warn("cause message: {}", cause == null ? "null" : cause.getMessage());

		InvalidFormatException ife = null;
		if (cause instanceof InvalidFormatException e) {
			ife = e;
		} else if (cause instanceof DatabindException jme) {
			if (jme.getCause() instanceof InvalidFormatException e) {
				ife = e;
			}
		}

		if (ife != null) {
			String fieldName =
					ife.getPath().isEmpty() ? "unknown" : ife.getPath().get(0).getPropertyName();

			String message;
			if (ife.getTargetType() != null && ife.getTargetType().isEnum()) {

				@SuppressWarnings("null")
				String accepted =
						java.util.Arrays.stream(ife.getTargetType().getEnumConstants())
								.map(Object::toString)
								.collect(Collectors.joining(", "));

				message = messageHelper.get("http.invalid_enum_field", accepted);
			} else {
				message = messageHelper.get("http.invalid_field_value", fieldName, ife.getValue());
			}

			return ResponseEntity.badRequest()
					.body(new ErrorResponse(
							messageHelper.get("validation.failed"),
							List.of(new ValidationError(fieldName, message))));
		}

		return ResponseEntity.badRequest()
				.body(new ErrorResponse(messageHelper.get("http.bad_request"), null));
	}

	/** Missing request parameter */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParam(
			MissingServletRequestParameterException exception) {

		ErrorResponse response =
				new ErrorResponse(
						messageHelper.get("http.bad_request"),
						List.of(new ValidationError(
								exception.getParameterName(),
								messageHelper.get("http.parameter_required"))));

		return ResponseEntity.badRequest().body(response);
	}

	/** Invalid parameter type */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(
			MethodArgumentTypeMismatchException exception) {

		ErrorResponse response =
				new ErrorResponse(
						messageHelper.get("http.bad_request"),
						List.of(new ValidationError(
								exception.getName(),
								messageHelper.get("http.invalid_param_value", exception.getName()))));

		return ResponseEntity.badRequest().body(response);
	}

	/** HTTP method not allowed */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
			HttpRequestMethodNotSupportedException exception) {

		ErrorResponse response = new ErrorResponse(messageHelper.get("http.method_not_allowed"), null);

		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
	}

	/** Unsupported media type */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMediaType(
			HttpMediaTypeNotSupportedException exception) {

		ErrorResponse response =
				new ErrorResponse(messageHelper.get("http.unsupported_media_type"), null);

		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(response);
	}

	/** Endpoint not found */
	@ExceptionHandler(NoHandlerFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoHandler(NoHandlerFoundException exception) {

		ErrorResponse response = new ErrorResponse(messageHelper.get("http.not_found"), null);

		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

	/** Illegal argument */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {

		ErrorResponse response = new ErrorResponse(exception.getMessage(), null);

		return ResponseEntity.badRequest().body(response);
	}

	/** Fallback error */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException(Exception exception) {
		log.error(exception.getMessage(), exception);
		ErrorResponse response = new ErrorResponse(messageHelper.get("system.error"), null);

		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
	}

	/** Service exception error */
	@ExceptionHandler(ServiceException.class)
	public ResponseEntity<ErrorResponse> handleServiceException(ServiceException exception) {
		String localizedMessage =
				messageHelper.get(exception.getErrorCode().getMessageKey(), exception.getArgs());
		log.error(exception.toString());
		ErrorResponse response = new ErrorResponse(localizedMessage, null);
		return ResponseEntity.status(exception.getErrorCode().getHttpStatus()).body(response);
	}

	/** Validation error thrown from the service layer */
	@ExceptionHandler(ValidationException.class)
	public ResponseEntity<ErrorResponse> handleServiceValidation(ValidationException exception) {
		ErrorResponse response =
				new ErrorResponse(messageHelper.get("validation.failed"), exception.getErrors());
		return ResponseEntity.badRequest().body(response);
	}

	private ValidationError toValidationError(FieldError error) {
		ConstraintViolation<?> violation = error.unwrap(ConstraintViolation.class);
		return toValidationError(violation, error.getField());
	}

	private ValidationError toValidationError(ConstraintViolation<?> violation) {
		return toValidationError(violation, violation.getPropertyPath().toString());
	}

	private static final Map<String, List<String>> CONSTRAINT_ARGS =
			Map.of(
					"Min", List.of("value"),
					"Max", List.of("value"),
					"Size", List.of("min", "max"),
					"Range", List.of("min", "max"),
					"DecimalMin", List.of("value"),
					"DecimalMax", List.of("value"));

	private ValidationError toValidationError(ConstraintViolation<?> violation, String field) {
		String constraintName =
				violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();

		Map<String, Object> attrs = violation.getConstraintDescriptor().getAttributes();

		Object[] args =
				CONSTRAINT_ARGS.getOrDefault(constraintName, List.of()).stream().map(attrs::get).toArray();

		return new ValidationError(field, messageHelper.get(violation.getMessage(), args));
	}
}
