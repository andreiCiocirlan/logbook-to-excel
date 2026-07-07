package com.example;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class LogbookParser {

    // primary mapping: requestId -> correlationId
    private final Map<String, String> requestIdToCorrelationId = new LinkedHashMap<>();

    // primary records keyed by requestId (one row per request)
    private final Map<String, LogbookRecord> recordsByRequestId = new LinkedHashMap<>();

    public List<LogbookRecord> parse(Path logFile) throws IOException {
        List<String> lines = Files.readAllLines(logFile);

        requestIdToCorrelationId.clear();
        recordsByRequestId.clear();

        buildRequestIdToCorrelationId(lines);     // first pass: gather requestId -> correlationId
        parseIncomingRequests(lines);             // second pass: populate incoming details per request
        parseErrors(lines);                       // third pass: attach errors based on correlation id
        parseOutgoingResponses(lines);            // fourth pass: attach response details per request

        // filter out actuator endpoints
        recordsByRequestId.entrySet().removeIf(entry -> {
            String endpoint = entry.getValue().getHeaders().get("endpoint");
            return endpoint != null && endpoint.contains("actuator/health");
        });

        return new ArrayList<>(recordsByRequestId.values());
    }

    // --- FIRST PASS -----------------------------------------------------
    // Find Incoming Request blocks and read correlation-id (if present) to build requestIdToCorrelationId.
    private void buildRequestIdToCorrelationId(List<String> lines) {
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (!isIncomingRequestLine(line)) {
                i++;
                continue;
            }

            String requestId = extractRequestId(line);
            i++;

            // scan block until next timestamp line
            while (i < lines.size() && !isNextTimestampLine(lines.get(i))) {
                String trimmed = lines.get(i).trim();
                i++;

                // strip optional "+[" prefix if present
                String content;
                if (trimmed.startsWith("+[")) {
                    content = trimmed.substring(2).trim();
                } else {
                    content = trimmed;
                }

                if (content.startsWith("correlation-id:")) {
                    String correlationId = valueAfterColon(content);
                    if (!requestId.isEmpty() && !correlationId.isEmpty()) {
                        requestIdToCorrelationId.put(requestId, correlationId);
                    }
                    // we can break because correlation-id appears at most once per request block
                    break;
                } else if (content.startsWith("tracestate: in=")) {
                    String correlationId = etractTraceStateCorrelationId(content);
                    if (!requestId.isEmpty() && !correlationId.isEmpty()) {
                        requestIdToCorrelationId.put(requestId, correlationId);
                    }
                    // we can break because correlation-id appears at most once per request block
                    break;
                }
            }
        }
    }

    // --- SECOND PASS ----------------------------------------------------
    // Parse incoming request blocks and create/update LogbookRecord per requestId.
    private void parseIncomingRequests(List<String> lines) {
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (!isIncomingRequestLine(line)) {
                i++;
                continue;
            }

            String timestamp = extractTimestamp(line);
            String requestId = extractRequestId(line);
            String correlationId = requestIdToCorrelationId.get(requestId);

            LogbookRecord record = getOrCreateByRequestId(requestId);
            if (record.getTimestamp() == null) {
                record.setTimestamp(timestamp);
            }
            record.setRequestId(requestId);
            record.setCorrelationId(correlationId);

            i++;

            while (i < lines.size() && !isNextTimestampLine(lines.get(i))) {
                String trimmed = lines.get(i).trim();
                i++;

                // strip optional "+[" prefix if present
                String content;
                if (trimmed.startsWith("+[")) {
                    content = trimmed.substring(2).trim();
                } else {
                    content = trimmed;
                }

                // if correlation-id discovered here, record it and update mapping
                if (content.startsWith("correlation-id:")) {
                    correlationId = valueAfterColon(content);
                    if (!correlationId.isEmpty()) {
                        record.setCorrelationId(correlationId);
                        requestIdToCorrelationId.put(requestId, correlationId);
                    }
                    continue;
                } else if (content.startsWith("tracestate: in=")) {
                    correlationId = etractTraceStateCorrelationId(content);
                    if (!requestId.isEmpty() && !correlationId.isEmpty()) {
                        requestIdToCorrelationId.put(requestId, correlationId);
                    }
                    continue;
                }

                applyRequestLine(record, content);
            }
        }
    }

    // --- THIRD PASS -----------------------------------------------------
    // Parse error lines and attach errors to all requests that map to the correlation id found in the error line.
    private void parseErrors(List<String> lines) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.contains(" ERROR ")) {
                continue;
            }

            String correlationId = extractCorrelationIdFromError(trimmed);
            if (correlationId == null || correlationId.isEmpty()) {
                continue;
            }

            // attach error to every requestId that maps to this correlation id
            for (Entry<String, String> e : requestIdToCorrelationId.entrySet()) {
                if (correlationId.equals(e.getValue())) {
                    LogbookRecord record = recordsByRequestId.get(e.getKey());
                    if (record != null) {
                        record.addError(trimmed);
                    }
                }
            }
        }
    }

    // --- FOURTH PASS ----------------------------------------------------
    // Parse outgoing response blocks and attach response attributes to the record keyed by requestId.
    private void parseOutgoingResponses(List<String> lines) {
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (!isOutgoingResponseLine(line)) {
                i++;
                continue;
            }

            String timestamp = extractTimestamp(line);
            String requestId = extractRequestId(line);

            LogbookRecord record = getOrCreateByRequestId(requestId);
            if (record.getTimestamp() == null) {
                record.setTimestamp(timestamp);
            }
            record.setRequestId(requestId);
            record.setCorrelationId(requestIdToCorrelationId.get(requestId));

            i++;

            while (i < lines.size() && !isNextTimestampLine(lines.get(i))) {
                String trimmed = lines.get(i).trim();
                i++;

                // strip optional "+[" prefix if present
                String content;
                if (trimmed.startsWith("+[")) {
                    content = trimmed.substring(2).trim();
                } else {
                    content = trimmed;
                }

                // if the response contains a correlation-id header for some reason, keep it in sync
                if (content.startsWith("correlation-id:")) {
                    String correlationId = valueAfterColon(content);
                    if (!correlationId.isEmpty()) {
                        record.setCorrelationId(correlationId);
                        requestIdToCorrelationId.put(requestId, correlationId);
                    }
                    continue;
                } else if (content.startsWith("tracestate: in=")) {
                    String correlationId = etractTraceStateCorrelationId(content);
                    if (!requestId.isEmpty() && !correlationId.isEmpty()) {
                        requestIdToCorrelationId.put(requestId, correlationId);
                    }
                    continue;
                }

                // Attach errors that may be in payload lines (e.g. JSON with "ERROR")
                if (content.contains("\"ERROR\"") || content.contains("\"message\":\"ERROR\"") || content.toLowerCase().contains("error")) {
                    record.addError(content);
                }

                applyResponseLine(record, content);
            }
        }
    }

    // ---------------- helpers and existing logic (kept / slightly adapted) ----------------
    private void applyRequestLine(LogbookRecord record, String content) {
        if (content.startsWith("Remote:")) {
            record.putHeader("Remote", valueAfterColon(content));
            return;
        }

        if (content.startsWith("user-agent:")) {
            record.putHeader("user-agent", valueAfterColon(content));
            return;
        }

        if (content.startsWith("GET ")
            || content.startsWith("POST ")
            || content.startsWith("PUT ")
            || content.startsWith("DELETE ")
            || content.startsWith("PATCH ")) {
            extractHttpMethodAndEndpointAndQuery(record, content);
        }
    }

    private void applyResponseLine(LogbookRecord record, String content) {
        if (content.startsWith("Content-Type:")) {
            record.putHeader("Content-Type", valueAfterColon(content));
            return;
        }

        if (content.startsWith("Duration:")) {
            record.putHeader("Duration", valueAfterColon(content));
            return;
        }

        if (content.startsWith("HTTP/1.1")) {
            record.putHeader("status", extractStatusCode(content));
        }
    }

    private void extractHttpMethodAndEndpointAndQuery(LogbookRecord record, String content) {
        String s = content.trim();

        String method = "";
        if (s.startsWith("GET ")) {
            method = "GET";
            s = s.substring(4).trim();
        } else if (s.startsWith("POST ")) {
            method = "POST";
            s = s.substring(5).trim();
        } else if (s.startsWith("PUT ")) {
            method = "PUT";
            s = s.substring(4).trim();
        } else if (s.startsWith("DELETE ")) {
            method = "DELETE";
            s = s.substring(7).trim();
        } else if (s.startsWith("PATCH ")) {
            method = "PATCH";
            s = s.substring(6).trim();
        }

        int httpIndex = s.indexOf("HTTP/1.1");
        if (httpIndex >= 0) {
            s = s.substring(0, httpIndex).trim();
        }

        s = stripMarkdownLink(s);

        try {
            URI uri = new URI(s);
            record.putHeader("http_method", method);
            record.putHeader("endpoint", uri.getPath() == null ? "" : uri.getPath());
            record.putHeader("query_param", uri.getQuery() == null ? "" : uri.getQuery());
        } catch (Exception e) {
            record.putHeader("http_method", method);
            record.putHeader("endpoint", s);
            record.putHeader("query_param", "");
        }
    }

    private String extractStatusCode(String statusLine) {
        int idx = statusLine.indexOf("HTTP/1.1");
        if (idx < 0) {
            return "";
        }
        String after = statusLine.substring(idx + "HTTP/1.1".length()).trim();
        String[] parts = after.split("\\s+");
        return parts.length > 0 ? parts[0] : "";
    }

    private boolean isIncomingRequestLine(String line) {
        return line != null
               && line.contains("Logbook")
               && line.contains("Incoming Request:");
    }

    private boolean isOutgoingResponseLine(String line) {
        return line != null
               && line.contains("Logbook")
               && line.contains("Outgoing Response:");
    }

    private boolean isNextTimestampLine(String line) {
        return line != null && (line.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*") || line.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*"));
    }

    private String extractTimestamp(String line) {
        if (line == null || line.length() < 23) {
            return "";
        }
        return line.substring(0, 23);
    }

    private String extractRequestId(String line) {
        int idx = line.indexOf("Incoming Request:");
        if (idx >= 0) {
            return line.substring(idx + "Incoming Request:".length()).trim();
        }

        idx = line.indexOf("Outgoing Response:");
        if (idx >= 0) {
            return line.substring(idx + "Outgoing Response:".length()).trim();
        }

        return "";
    }

    private String etractTraceStateCorrelationId(String input) {
        int start = input.indexOf("=");
        int end = input.indexOf(";");

        if (start == -1 || end == -1) {
            return "";
        }

        return input.substring(start + 1, end).trim();
    }

    private String valueAfterColon(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return "";
        }
        return line.substring(colon + 1).trim();
    }

    private String stripMarkdownLink(String value) {
        String s = value.trim();
        int markdownStart = s.indexOf("](");
        if (s.startsWith("[") && markdownStart > 0) {
            int markdownEnd = s.indexOf(")", markdownStart);
            if (markdownEnd > markdownStart) {
                return s.substring(markdownStart + 2, markdownEnd).trim();
            }
        }
        return s;
    }

    private String[] extractBracketParts(String line) {
        if (line == null) {
            return null;
        }

        int open = line.indexOf('[');
        int close = line.indexOf(']', open + 1);
        if (open < 0 || close < 0) {
            return null;
        }

        String inside = line.substring(open + 1, close);
        String[] parts = inside.split(",");
        return parts.length >= 1 ? parts : null;
    }

    // Attempt to extract a correlation-id from an ERROR line using bracketed parts.
    // Returns the first bracketed part that looks like a correlation id (hex-ish) from positions 1 or 2,
    // otherwise returns null.
    private String extractCorrelationIdFromError(String line) {
        String[] parts = extractBracketParts(line);
        if (parts == null) {
            return null;
        }

        // prefer second then third element if present (as you noted)
        if (parts.length >= 2) {
            String candidate = parts[1].trim();
            if (isCorrelationId(candidate)) {
                return candidate;
            }
        }
        if (parts.length >= 3) {
            String candidate = parts[2].trim();
            if (isCorrelationId(candidate)) {
                return candidate;
            }
        }

        // fallback: try any part
        for (String p : parts) {
            String candidate = p.trim();
            if (isCorrelationId(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private boolean isCorrelationId(String value) {
        return value != null && value.matches("[a-fA-F0-9\\-]{8,}"); // basic heuristic, adjust to your ids
    }

    private LogbookRecord getOrCreateByRequestId(String requestId) {
        return recordsByRequestId.computeIfAbsent(requestId, id -> {
            LogbookRecord r = new LogbookRecord();
            r.setRequestId(id);
            return r;
        });
    }

}