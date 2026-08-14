package com.gepe.app.auth.internal.exception;

import com.gepe.app.platform.exception.ErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum AuthError implements ErrorCode {

    // ── credentials ──
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "auth.invalid_credentials"),

    // ── status akun ──
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "auth.account_suspended"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "auth.account_disabled"),

    // ── access token ──
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "auth.token_expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "auth.token_invalid"),
    TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "auth.token_revoked"),
    TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "auth.token_missing"),
    TOKEN_INVALID_CLAIM(HttpStatus.UNAUTHORIZED, "auth.token_invalid_claim"),

    // ── refresh token / session ──
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "auth.refresh_token_expired"),
    REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "auth.refresh_token_revoked"),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "auth.refresh_token_reused"),
    CURRENT_TOKEN_REQUIRED(HttpStatus.BAD_REQUEST, "auth.current_token_required"),
    CANNOT_REVOKE_CURRENT(HttpStatus.BAD_REQUEST, "auth.cannot_revoke_current"),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "auth.session_not_found"),

    // ── identity / linking ──
    EMAIL_ALREADY_LINKED(HttpStatus.CONFLICT, "auth.email_already_linked"),
    IDENTITY_EXISTS(HttpStatus.CONFLICT, "auth.identity_exists"),
    IDENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "auth.identity_not_found"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "auth.user_not_found"),

    // ── admin (guard operasi akun) — key message ada di bundle i18n/admin (kebijakan
    //    operasi admin), enum tetap di sini karena guard dieksekusi module auth ──
    SELF_OPERATION_FORBIDDEN(HttpStatus.FORBIDDEN, "admin.self_operation_forbidden"),
    INSUFFICIENT_PRIVILEGE(HttpStatus.FORBIDDEN, "admin.insufficient_privilege"),
    ROLE_NOT_FOUND(HttpStatus.BAD_REQUEST, "auth.role_not_found"),
    ROLE_SET_EMPTY(HttpStatus.BAD_REQUEST, "auth.role_set_empty"),
    LAST_ADMIN_REMOVAL(HttpStatus.CONFLICT, "admin.last_admin_removal"),
    LAST_SUPER_ADMIN_REMOVAL(HttpStatus.CONFLICT, "admin.last_super_admin_removal"),
    LAST_SUPER_ADMIN_STATUS(HttpStatus.CONFLICT, "admin.last_super_admin_status"),

    // ── key management ──
    KEY_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "auth.key_generation_failed"),
    KEY_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "auth.key_not_found"),
    ENCRYPTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "auth.encryption_failed"),
    DECRYPTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "auth.decryption_failed"),
    MASTER_KEY_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "auth.master_key_invalid"),

    // ── oauth ──
    OAUTH_STATE_INVALID(HttpStatus.BAD_REQUEST, "auth.oauth_state_invalid"),
    OAUTH_CODE_REUSED(HttpStatus.BAD_REQUEST, "auth.oauth_code_reused"),
    OAUTH_REDIRECT_FORBIDDEN(HttpStatus.BAD_REQUEST, "auth.oauth_redirect_forbidden"),
    OAUTH_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "auth.oauth_provider_error"),
    ;

    private final HttpStatus httpStatus;
    private final String messageKey;

    AuthError(HttpStatus httpStatus, String messageKey) {
        this.httpStatus = httpStatus;
        this.messageKey = messageKey;
    }
}
