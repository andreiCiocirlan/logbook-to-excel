package com.example;

import lombok.Getter;
import lombok.Setter;

import java.util.*;

@Setter
@Getter
public class LogbookRecord {
    private String requestId;
    private String traceId;
    private String timestamp;
    private final Set<String> errors = new HashSet<>();
    private final LinkedHashMap<String, String> headers = new LinkedHashMap<>();

    public void putHeader(String key, String value) {
        headers.put(key, value);
    }

    public void addError(String errorLine) {
        if (errorLine != null && !errorLine.isBlank()) {
            errors.add(errorLine);
        }
    }

    public String getError() {
        return String.join("\r\n", errors);
    }
}