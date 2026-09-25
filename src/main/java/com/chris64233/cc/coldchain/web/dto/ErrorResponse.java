package com.chris64233.cc.coldchain.web.dto;

import java.time.Instant;

public record ErrorResponse(
        String code,
        String message,
        int status,
        Instant timestamp) {

    public static ErrorResponse of(String code, String message, int status) {
        return new ErrorResponse(code, message, status, Instant.now());
    }
}
