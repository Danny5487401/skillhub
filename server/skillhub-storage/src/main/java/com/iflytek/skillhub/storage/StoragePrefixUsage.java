package com.iflytek.skillhub.storage;

/** Actual object bytes under one storage prefix, bounded by the caller's scan limit. */
public record StoragePrefixUsage(long bytes, long objectCount, boolean complete) {}
