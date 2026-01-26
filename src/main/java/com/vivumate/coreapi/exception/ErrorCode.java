package com.vivumate.coreapi.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // 1xxx: Validation
    // 2xxx: External API
    // 9xxx: System

    UNCATEGORIZED_EXCEPTION(9999, "error.internal", HttpStatus.INTERNAL_SERVER_ERROR),

    INVALID_INPUT(1001, "error.validation", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(1002, "error.unauthorized", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(1003, "error.forbidden", HttpStatus.FORBIDDEN),
    LOGIN_BAD_CREDENTIALS(1004, "error.login.bad_credentials", HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(1005, "error.account.locked", HttpStatus.FORBIDDEN),
    ACCOUNT_DISABLED(1006, "error.account.disabled", HttpStatus.FORBIDDEN),
    TOKEN_INVALID(1007, "error.token.invalid", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(1008, "error.token.expired", HttpStatus.UNAUTHORIZED),
    ACCOUNT_DELETED(1009, "error.account.deleted", HttpStatus.FORBIDDEN),
    TOKEN_REVOKED(1010, "error.token.revoked", HttpStatus.UNAUTHORIZED),

    WEATHER_API_ERROR(2001, "error.weather.api", HttpStatus.BAD_GATEWAY),
    WEATHER_DATA_PARSE_ERROR(2002, "error.internal", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String messageKey;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String messageKey, HttpStatus httpStatus) {
        this.code = code;
        this.messageKey = messageKey;
        this.httpStatus = httpStatus;
    }
}
