package com.iflytek.skillhub.storage;

import java.util.Map;

public record PresignedUpload(String url, Map<String, String> headers) {
}
