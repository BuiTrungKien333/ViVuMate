package com.vivumate.coreapi.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // 1xxx: Validation
    // 2xxx: External API
    // 9xxx: System

    UNCATEGORIZED_EXCEPTION(9999, "error.internal", HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_SERVER_ERROR(9000, "error.internal.server", HttpStatus.INTERNAL_SERVER_ERROR),

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
    USER_NOT_FOUND(1011, "error.user.notfound", HttpStatus.NOT_FOUND),
    OLD_PASSWORD_INCORRECT(1012, "error.user.password.incorrect", HttpStatus.BAD_REQUEST),
    PASSWORDS_DO_NOT_MATCH(1013, "error.user.password.notmatch", HttpStatus.BAD_REQUEST),
    USER_EXISTED(1014, "error.user.existed", HttpStatus.CONFLICT),
    EMAIL_EXISTED(1015, "error.user.email.existed", HttpStatus.CONFLICT),
    ROLE_NOT_FOUND(1016, "error.user.role.notfound", HttpStatus.NOT_FOUND),
    USER_NOT_DELETED(1017, "error.user.notdelete", HttpStatus.BAD_REQUEST),
    ENDPOINT_NOT_FOUND(1018, "error.endpoint.notfound", HttpStatus.NOT_FOUND),
    TOO_MANY_REQUESTS(1019, "error.many.request", HttpStatus.TOO_MANY_REQUESTS),
    USER_ALREADY_VERIFIED(1020, "error.account.verified", HttpStatus.BAD_REQUEST),
    ACCOUNT_UNVERIFIED(1021, "error.account.unverify", HttpStatus.FORBIDDEN),
    INVALID_OTP(1022, "error.otp.invalid", HttpStatus.BAD_REQUEST),
    OTP_EXPIRED(1023, "error.otp.expired", HttpStatus.BAD_REQUEST),

    WEATHER_API_ERROR(2001, "error.weather.api", HttpStatus.BAD_GATEWAY),
    WEATHER_DATA_PARSE_ERROR(2002, "error.internal", HttpStatus.INTERNAL_SERVER_ERROR),

    EMAIL_SEND_FAILED(3001, "error.email.send_failed", HttpStatus.INTERNAL_SERVER_ERROR),

    // 4xxx: Chat
    CONVERSATION_NOT_FOUND(4001, "error.chat.conversation.notfound", HttpStatus.NOT_FOUND),
    CONVERSATION_ALREADY_EXISTS(4002, "error.chat.conversation.already_exists", HttpStatus.CONFLICT),
    CONVERSATION_MEMBER_LIMIT(4003, "error.chat.conversation.member_limit", HttpStatus.BAD_REQUEST),
    CONVERSATION_INVALID_MEMBER_COUNT(4004, "error.chat.conversation.invalid_member_count", HttpStatus.BAD_REQUEST),
    CONVERSATION_NOT_GROUP(4005, "error.chat.conversation.not_group", HttpStatus.BAD_REQUEST),
    CONVERSATION_ACCESS_DENIED(4006, "error.chat.conversation.access_denied", HttpStatus.FORBIDDEN),
    CONVERSATION_ADMIN_REQUIRED(4007, "error.chat.conversation.admin_required", HttpStatus.FORBIDDEN),
    PARTICIPANT_ALREADY_EXISTS(4008, "error.chat.participant.already_exists", HttpStatus.CONFLICT),
    PARTICIPANT_NOT_FOUND(4009, "error.chat.participant.notfound", HttpStatus.NOT_FOUND),

    MESSAGE_NOT_FOUND(4101, "error.chat.message.notfound", HttpStatus.NOT_FOUND),
    MESSAGE_EDIT_DENIED(4102, "error.chat.message.edit_denied", HttpStatus.FORBIDDEN),
    MESSAGE_RECALL_DENIED(4103, "error.chat.message.recall_denied", HttpStatus.FORBIDDEN),
    CANNOT_DM_SELF(4104, "error.chat.dm.self", HttpStatus.BAD_REQUEST);

    private final int code;
    private final String messageKey;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String messageKey, HttpStatus httpStatus) {
        this.code = code;
        this.messageKey = messageKey;
        this.httpStatus = httpStatus;
    }
}
