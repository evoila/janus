package com.evoila.janus.common.model;

/** Error response for global exception handling with detailed context. */
public record GlobalErrorResponse(
    String error, String message, int status, String errorCode, String timestamp, String path) {}
