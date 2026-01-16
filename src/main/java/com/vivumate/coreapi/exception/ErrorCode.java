package com.vivumate.coreapi.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    UNCATEGORIZED_EXCEPTION(9999, "error.internal", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_INPUT(1001, "error.validation", HttpStatus.BAD_REQUEST),

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
