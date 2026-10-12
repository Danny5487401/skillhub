package com.iflytek.skillhub.service;

/** A deployment-selected image reviewer; implementations must never log credentials or raw URLs. */
public interface AvatarModerationProvider {
    enum Verdict { PASS, BLOCK, ERROR }

    String code();
    boolean configured();
    Verdict moderate(byte[] normalizedPng);
}
