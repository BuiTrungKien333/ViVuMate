package com.vivumate.coreapi.exception;

import com.vivumate.coreapi.dto.response.ApiResponse;
import com.vivumate.coreapi.utils.Translator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Objects;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final Translator translator;

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
                ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParams(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getParameterName());

        String message = translator.toLocale("error.param.missing") + ": " + e.getParameterName();

        return ResponseEntity.badRequest().body(
                ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Parameter type Mismatch: param={}, value={}", e.getName(), e.getValue());

        String message = translator.toLocale("error.param.type") + ": " + e.getName();

        return ResponseEntity.badRequest().body(
                ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message));
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
                ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidationException(
            HandlerMethodValidationException e) {
        String message = e.getAllValidationResults().stream()
                .flatMap(result -> {
                    String paramName = result.getMethodParameter().getParameterName();
                    return result.getResolvableErrors().stream()
                            .map(error -> paramName + ": " + error.getDefaultMessage());
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));

        if (message.isBlank()) {
            message = translator.toLocale("error.validation");
        }

        log.warn("Method parameter validation failed: {}", message);
        return ResponseEntity.badRequest().body(
                ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message));
    }


    /*
     * Invalid value for enum exception
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        String message;
        Throwable cause = ex.getCause();

        if (cause instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException ife
                && ife.getTargetType() != null
                && ife.getTargetType().isEnum()) {

            String invalidValue = ife.getValue() != null ? ife.getValue().toString() : "unknown";
            String enumName = ife.getTargetType().getSimpleName();
            String accepted = java.util.Arrays.stream(ife.getTargetType().getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            message = "Invalid value '" + invalidValue + "' for field '" + enumName
                    + "'. Accepted values: [" + accepted + "]";
        } else {
            message = translator.toLocale("error.json.unreadable");
        }

        log.warn("HttpMessageNotReadable: {}", message);
        return ResponseEntity.badRequest().body(
                ApiResponse.error(ErrorCode.INVALID_INPUT.getCode(), message));
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

        ErrorCode errorCode = switch (e.getMessage()) {
            case "ACCOUNT_DELETED" -> ErrorCode.ACCOUNT_DELETED;
            case "ACCOUNT_UNVERIFIED" -> ErrorCode.ACCOUNT_UNVERIFIED;
            case "ACCOUNT_BANNED" -> ErrorCode.ACCOUNT_LOCKED;
            default -> ErrorCode.ACCOUNT_DISABLED;
        };

        return buildErrorResponse(errorCode);
    }

    /*
     * JWT Exceptions
     */
    @ExceptionHandler({io.jsonwebtoken.security.SignatureException.class,
            io.jsonwebtoken.MalformedJwtException.class})
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
     * API Endpoint Not Found Exceptions
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleEndpointNotFound(Exception e) {
        String path = "unknown";

        if (e instanceof NoResourceFoundException ex) {
            path = ex.getResourcePath();
        } else if (e instanceof NoHandlerFoundException ex) {
            path = ex.getRequestURL();
        }

        log.warn("Endpoint not found: {}", path);

        String message = translator.toLocale(ErrorCode.ENDPOINT_NOT_FOUND.getMessageKey()) + ": " + path;
        return ResponseEntity.status(ErrorCode.ENDPOINT_NOT_FOUND.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.ENDPOINT_NOT_FOUND.getCode(), message));
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
                .body(ApiResponse.error(errorCode.getCode(), translator.toLocale(errorCode.getMessageKey())));
    }

}
