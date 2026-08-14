package com.gepe.app.user.internal.exception;

import com.gepe.app.platform.exception.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum UserProfileError implements ErrorCode {

    NICKNAME_TAKEN(HttpStatus.CONFLICT, "user.nickname_taken"),
    ;

    private final HttpStatus httpStatus;
    private final String messageKey;

    UserProfileError(HttpStatus httpStatus, String messageKey) {
        this.httpStatus = httpStatus;
        this.messageKey = messageKey;
    }
}
