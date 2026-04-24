package com.vivumate.coreapi.websocket.handler;

import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.websocket.dto.WebSocketErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * Global exception handler for STOMP @MessageMapping methods.
 * <p>
 * Similar to {@link com.vivumate.coreapi.exception.GlobalExceptionHandler}
 * for REST controllers, this class catches exceptions thrown during
 * WebSocket message processing and sends error responses back to the
 * client via the user's private error queue.
 * <p>
 * <b>Error delivery:</b> Errors are sent to {@code /queue/errors},
 * which is a user-specific destination. Only the user who triggered
 * the error receives the response (not broadcasted).
 * <p>
 * <b>Client subscription:</b> Clients should subscribe to
 * {@code /user/queue/errors} to receive error notifications.
 */
@ControllerAdvice
@Slf4j(topic = "WEBSOCKET_EXCEPTION_HANDLER")
public class WebSocketExceptionHandler {

    /**
     * Handles {@link AppException} thrown from @MessageMapping handlers.
     * Maps the ErrorCode to a WebSocketErrorResponse and sends it to the user's error queue.
     *
     * @param ex the business exception
     * @return error response sent to /user/queue/errors
     */
    @MessageExceptionHandler(AppException.class)
    @SendToUser("/queue/errors")
    public WebSocketErrorResponse handleAppException(AppException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("WebSocket AppException: code={}, messageKey={}",
                errorCode.getCode(), errorCode.getMessageKey());

        return WebSocketErrorResponse.of(errorCode.getCode(), errorCode.getMessageKey());
    }

    /**
     * Handles validation and argument errors from @MessageMapping handlers.
     * <p>
     * Covers: {@link IllegalArgumentException}, {@link jakarta.validation.ConstraintViolationException},
     * and other common input validation failures.
     *
     * @param ex the validation exception
     * @return error response sent to /user/queue/errors
     */
    @MessageExceptionHandler(IllegalArgumentException.class)
    @SendToUser("/queue/errors")
    public WebSocketErrorResponse handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("WebSocket IllegalArgumentException: {}", ex.getMessage());

        return WebSocketErrorResponse.of(
                ErrorCode.INVALID_INPUT.getCode(),
                ex.getMessage()
        );
    }

    /**
     * Fallback handler for all uncaught exceptions in @MessageMapping methods.
     * <p>
     * Logs the full stack trace for debugging but returns a generic error
     * to the client (no internal details leaked).
     *
     * @param ex the uncaught exception
     * @return generic error response sent to /user/queue/errors
     */
    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errors")
    public WebSocketErrorResponse handleGenericException(Exception ex) {
        log.error("WebSocket unhandled exception", ex);

        return WebSocketErrorResponse.of(
                ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                "An unexpected error occurred"
        );
    }
}
