package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.githubsync.GithubOfficialProtocolException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
@RestControllerAdvice(assignableTypes = {GithubSyncInternalController.class, GithubOfficialControlController.class, GithubPlatformControlController.class})
public class GithubOfficialProtocolAdvice {
    @ExceptionHandler(GithubOfficialProtocolException.class)
    public ResponseEntity<?> officialFailure(GithubOfficialProtocolException exception) {
        return ResponseEntity.status(exception.status()).body(
                new GithubSyncInternalController.ApiError(exception.code(), exception.code()));
    }
}
