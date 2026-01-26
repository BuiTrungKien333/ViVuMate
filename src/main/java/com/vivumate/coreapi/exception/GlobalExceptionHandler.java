package com.vivumate.coreapi.exception;

import com.vivumate.coreapi.dto.response.ApiResponse;
import com.vivumate.coreapi.utils.Translator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /*
     * Business Exception
     */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(AppException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.error("AppException: Code {} - MessageKey {}", errorCode.getCode(), errorCode.getMessageKey());

        return buildErrorResponse(errorCode);
    }

    /*
     * Validation Exceptions
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException e) {
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
        log.warn("Missing request parameter: {}", e.getParameterName());

        String message = Translator.toLocale("error.param.missing") + ": " + e.getParameterName();

        return ResponseEntity.badRequest().body(
                ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message)
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Parameter type Mismatch: param={}, value={}", e.getName(), e.getValue());

        String message = Translator.toLocale("error.param.type") + ": " + e.getName();

        return ResponseEntity.badRequest().body(
                ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message)
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e) {

        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));

        log.warn("Request body validation failed: {}", message);

        return ResponseEntity.badRequest().body(
                ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message)
        );
    }

    /*
     * Security & Authentication
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("Access Denied: {}", e.getMessage());
        return buildErrorResponse(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentialsException(BadCredentialsException e) {
        log.warn("Bad credentials attempt");
        return buildErrorResponse(ErrorCode.LOGIN_BAD_CREDENTIALS);
    }

    @ExceptionHandler({LockedException.class, DisabledException.class})
    public ResponseEntity<ApiResponse<Void>> handleAccountStatusException(Exception e) {
        log.warn("Account status invalid: {}", e.getClass().getSimpleName());
        return buildErrorResponse(e.getMessage().equals("ACCOUNT_DELETED") ? ErrorCode.ACCOUNT_DELETED : ErrorCode.ACCOUNT_DISABLED);
    }

    /*
     * JWT Exceptions
     */

    @ExceptionHandler({io.jsonwebtoken.security.SignatureException.class, io.jsonwebtoken.MalformedJwtException.class})
    public ResponseEntity<ApiResponse<Void>> handleTokenInvalid(Exception e) {
        log.warn("Invalid JWT token");
        return buildErrorResponse(ErrorCode.TOKEN_INVALID);
    }

    @ExceptionHandler(io.jsonwebtoken.ExpiredJwtException.class)
    public ResponseEntity<ApiResponse<Void>> handleTokenExpired(Exception e) {
        log.warn("Expired JWT token");
        return buildErrorResponse(ErrorCode.TOKEN_EXPIRED);
    }

    /*
     * Fallback Exception
      */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception e) {
        log.error("Uncategorized Error", e);
        return buildErrorResponse(ErrorCode.UNCATEGORIZED_EXCEPTION);
    }

    private ResponseEntity<ApiResponse<Void>> buildErrorResponse(ErrorCode errorCode) {
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode.getCode(), Translator.toLocale(errorCode.getMessageKey())));
    }

}
