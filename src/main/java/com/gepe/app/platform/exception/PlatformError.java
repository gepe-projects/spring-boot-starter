package com.gepe.app.platform.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Error code milik platform (shared infrastructure) — dipakai lintas modul.
 * Message key-nya ada di bundle global {@code i18n/messages/messages*.properties},
 * bukan di bundle module tertentu, karena tidak terkait domain module mana pun.
 */
@Getter
public enum PlatformError implements ErrorCode {

    /** Cursor pagination rusak / tidak bisa di-decode. */
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "pagination.invalid_cursor"),

    ;

    private final HttpStatus httpStatus;
    private final String messageKey;

    PlatformError(HttpStatus httpStatus, String messageKey) {
        this.httpStatus = httpStatus;
        this.messageKey = messageKey;
    }
}
