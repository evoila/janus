package com.evoila.janus.common.model;

/** Error response for proxy request failures. */
public record ProxyErrorResponse(String error, String message) {}
