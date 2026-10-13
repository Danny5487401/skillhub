package com.iflytek.skillhub.service;

/** A deployment-selected image reviewer; implementations must never log credentials or raw URLs. */
public interface AvatarModerationProvider {
    enum Verdict { PASS, BLOCK, ERROR }

    /** API/policy evidence is not a claim that the supplier's underlying model is immutable. */
    record Context(String serviceId, String apiVersion, String policyRevision, String modelVersion) {
        public static final Context UNKNOWN = new Context("UNKNOWN", "UNKNOWN", "UNKNOWN", null);
    }

    default Context context() { return Context.UNKNOWN; }

    String code();
    boolean configured();
    Verdict moderate(byte[] normalizedPng);
}
