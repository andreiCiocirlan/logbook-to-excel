package com.example;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Setter
@Getter
public class LogbookRecord {
    private String requestId;
    private String correlationId;
    private String timestamp;
    private final List<String> errors = new ArrayList<>();
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
        return String.join(" | ", errors);
    }
}