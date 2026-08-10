package com.gepe.app.platform.exception;

import com.gepe.app.platform.web.response.ValidationError;
import java.util.List;

public class ValidationException extends RuntimeException {

	private final List<ValidationError> errors;

	public ValidationException(List<ValidationError> errors) {
		super("Validation failed");
		this.errors = List.copyOf(errors);
	}

	public List<ValidationError> getErrors() {
		return errors;
	}
}
