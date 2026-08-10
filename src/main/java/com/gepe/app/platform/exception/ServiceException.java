package com.gepe.app.platform.exception;

import lombok.Getter;

@Getter
public class ServiceException extends RuntimeException {

	private final ErrorCode errorCode;
	private final Object[] args;

	public ServiceException(ErrorCode errorCode, Object... args) {
		super(errorCode.getMessageKey());
		this.errorCode = errorCode;
		this.args = args;
	}
}
