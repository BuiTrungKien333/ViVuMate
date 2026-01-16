package com.vivumate.coreapi.exception;

import com.vivumate.coreapi.dto.response.ApiResponse;
import com.vivumate.coreapi.utils.Translator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHanler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(AppException e) {
        ErrorCode errorCode = e.getErrorCode();

        log.error("AppException: Code {} - MessageKey {}", errorCode.getCode(), errorCode.getMessageKey());

        String message = Translator.toLocale(errorCode.getMessageKey());

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .code(errorCode.getCode())
                .message(message)
                .build();

        return ResponseEntity.status(errorCode.getHttpStatus()).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception e) {
        log.error("Uncategorized Error", e);

        ErrorCode errorCode = ErrorCode.UNCATEGORIZED_EXCEPTION;

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.error(
                        errorCode.getCode(),
                        Translator.toLocale(errorCode.getMessageKey())
                ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        log.warn("Validation Error: {}", e.getMessage());

        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));

        return ResponseEntity.badRequest().body(
                ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message)
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParams(MissingServletRequestParameterException e) {
        log.warn("Missing Param: {}", e.getParameterName());

        String message = Translator.toLocale("error.param.missing") + ": " + e.getParameterName();

        return ResponseEntity.badRequest().body(
                ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message)
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Type Mismatch: param={}, value={}", e.getName(), e.getValue());

        String message = Translator.toLocale("error.param.type") + ": " + e.getName();

        return ResponseEntity.badRequest().body(
                ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message)
        );
    }

}
