package com.iflytek.skillhub.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {GithubSyncInternalController.class, GithubSyncUploadController.class, GithubPlatformControlController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GithubSyncInternalErrorHandler {
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<GithubSyncInternalController.ApiError> badRequest(Exception ignored) {
        return ResponseEntity.badRequest()
                .body(new GithubSyncInternalController.ApiError("INVALID_REQUEST", "Invalid request"));
    }
}
