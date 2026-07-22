package com.example;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogbookParser {

    private static final List<String> HTTP_METHODS = List.of("GET", "POST", "PUT", "DELETE", "PATCH");
    private static final Pattern TRACE_ID_WITH_SPACES_PATTERN = Pattern.compile("\\[([^\\s]+)(?=\\s{2,})");
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[a-fA-F0-9\\-]{8,}");
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*");

    // primary mapping: requestId -> traceId
    private final Map<String, String> requestIdToTraceId = new LinkedHashMap<>();

    // primary records keyed by requestId (one row per request)
    private final Map<String, LogbookRecord> recordsByRequestId = new LinkedHashMap<>();

    public List<LogbookRecord> parse(Path logFile) throws IOException {
        try {
            List<String> lines = Files.readAllLines(logFile);

            requestIdToTraceId.clear();
            recordsByRequestId.clear();

            buildRequestIdToTraceId(lines);           // first pass: gather requestId -> traceId
            parseIncomingRequests(lines);             // second pass: populate incoming details per request
            parseErrors(lines);                       // third pass: attach errors based on trace id
            parseOutgoingResponses(lines);            // fourth pass: attach response details per request

            // filter out actuator endpoints
            recordsByRequestId.entrySet().removeIf(entry -> {
                String endpoint = entry.getValue().getHeaders().get("endpoint");
                return endpoint != null && endpoint.contains("actuator/health");
            });
        } catch (MalformedInputException e) {
            throw new RuntimeException("This does not seem to be a log file.", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read the file.", e);
        }

        return new ArrayList<>(recordsByRequestId.values());
    }

    // --- FIRST PASS -----------------------------------------------------
    // Find Incoming Request blocks and read trace-id (if present) to build requestIdToTraceId.
    private void buildRequestIdToTraceId(List<String> lines) {
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
                i++;

                // strip optional "+[" prefix if present
                String content = normalizeContent(lines.get(i));

                if (content.startsWith("correlation-id:")) {
                    String traceId = valueAfterColon(content);
                    if (!requestId.isEmpty() && !traceId.isEmpty()) {
                        requestIdToTraceId.put(requestId, traceId);
                    }
                    // we can break because correlation-id appears at most once per request block
                    break;
                } else if (content.startsWith("tracestate: in=")) {
                    String traceId = extractTraceStateTraceId(content);
                    if (!requestId.isEmpty() && !traceId.isEmpty()) {
                        requestIdToTraceId.put(requestId, traceId);
                    }
                    // we can break because trace-id appears at most once per request block
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
            String traceId = requestIdToTraceId.get(requestId);

            LogbookRecord record = getOrCreateByRequestId(requestId);
            if (record.getTimestamp() == null) {
                record.setTimestamp(timestamp);
            }
            record.setRequestId(requestId);
            record.setTraceId(traceId);

            i++;

            while (i < lines.size()) {
                String content = normalizeContent(lines.get(i)); // strip optional "+[" prefix if present

                if (isNextTimestampLine(content)) {
                    break;
                }

                i++;

                // if correlation-id discovered here, record it and update mapping
                if (content.startsWith("correlation-id:")) {
                    traceId = valueAfterColon(content);
                    if (!traceId.isEmpty()) {
                        record.setTraceId(traceId);
                        requestIdToTraceId.put(requestId, traceId);
                    }
                    continue;
                } else if (content.startsWith("tracestate: in=")) {
                    traceId = extractTraceStateTraceId(content);
                    if (!requestId.isEmpty() && !traceId.isEmpty()) {
                        requestIdToTraceId.put(requestId, traceId);
                    }
                    continue;
                }

                applyRequestLine(record, content);
            }
        }
    }

    // --- THIRD PASS -----------------------------------------------------
    // Parse error lines and attach errors to all requests that map to the trace id found in the error line.
    private void parseErrors(List<String> lines) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.contains(" ERROR ")) {
                continue;
            }

            String traceId = extractTraceId(trimmed);
            if (traceId == null || traceId.isEmpty()) {
                continue;
            }

            // attach error to every requestId that maps to this trace id
            for (Entry<String, String> e : requestIdToTraceId.entrySet()) {
                if (traceId.equals(e.getValue())) {
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
            record.setTraceId(requestIdToTraceId.get(requestId));

            i++;

            String lastBodyLine = null;

            while (i < lines.size()) {
                String content = normalizeContent(lines.get(i)); // strip optional "+[" prefix if present

                if (isNextTimestampLine(content)) {
                    break;
                }

                i++;

                // if the response contains a correlation-id header for some reason, keep it in sync
                if (content.startsWith("correlation-id:")) {
                    String traceId = valueAfterColon(content);
                    if (!traceId.isEmpty()) {
                        record.setTraceId(traceId);
                        requestIdToTraceId.put(requestId, traceId);
                    }
                    continue;
                } else if (content.startsWith("tracestate: in=")) {
                    String traceId = extractTraceStateTraceId(content);
                    if (!requestId.isEmpty() && !traceId.isEmpty()) {
                        requestIdToTraceId.put(requestId, traceId);
                    }
                    continue;
                }

                // Attach errors that may be in payload lines (e.g. JSON with "ERROR")
                if (content.toLowerCase().contains("error")) {
                    record.addError(content);
                }

                if (!content.isEmpty() && (content.startsWith("{") || content.startsWith("["))) { // look for json type responses
                    lastBodyLine = content; // once exiting the while loop lastBodyLine will be the response body
                }

                applyResponseLine(record, content);
            }

            // After parsing the block, check if the status is a non-2xx failure
            String statusStr = record.getHeaders().get("status");
            boolean nonSuccessfulResponse = statusStr != null && !statusStr.isEmpty() && !statusStr.startsWith("2");
            if (nonSuccessfulResponse) {
                if (lastBodyLine != null) {
                    record.addError(lastBodyLine);
                }
            }
        }
    }

    private String normalizeContent(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("+[")
                ? trimmed.substring(2).trim()
                : trimmed;
    }

    private void applyRequestLine(LogbookRecord record, String content) {
        if (content.startsWith("Remote:")) {
            record.putHeader("Remote", valueAfterColon(content));
        } else if (content.startsWith("user-agent:")) {
            record.putHeader("user-agent", valueAfterColon(content));
        } else {
            for (String method : HTTP_METHODS) {
                if (content.startsWith(method + " ")) {
                    extractHttpMethodAndEndpointAndQuery(record, content);
                    break;
                }
            }
        }
    }

    private void applyResponseLine(LogbookRecord record, String content) {
        if (content.startsWith("Content-Type:")) {
            record.putHeader("Content-Type", valueAfterColon(content));
        } else if (content.startsWith("Duration:")) {
            record.putHeader("Duration", valueAfterColon(content));
        } else if (content.startsWith("HTTP/1.1")) {
            record.putHeader("status", extractStatusCode(content));
        }
    }

    private void extractHttpMethodAndEndpointAndQuery(LogbookRecord record, String content) {
        String s = content.trim();

        String method = "";

        for (String candidate : HTTP_METHODS) {
            if (s.startsWith(candidate + " ")) {
                method = candidate;
                s = s.substring(candidate.length()).trim();
                break;
            }
        }

        int httpIndex = s.indexOf("HTTP/1.1");
        if (httpIndex >= 0) {
            s = s.substring(0, httpIndex).trim();
        }

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
        return line != null && TIMESTAMP_PATTERN.matcher(line).matches();
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

    private String extractTraceStateTraceId(String input) {
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

    // Attempt to extract a trace-id from an ERROR line using bracketed parts.
    // Returns the first bracketed part that looks like a trace id (hex-ish) from positions 1 or 2,
    // otherwise returns null.
    private String extractTraceId(String line) {
        String[] parts = extractBracketParts(line);
        if (parts != null) {
            // Prefer second then third element
            if (parts.length >= 2) {
                String candidate = parts[1].trim();
                if (isTraceId(candidate)) {
                    return candidate;
                }
            }

            if (parts.length >= 3) {
                String candidate = parts[2].trim();
                if (isTraceId(candidate)) {
                    return candidate;
                }
            }

            // Fallback: try any part
            for (String part : parts) {
                String candidate = part.trim();
                if (isTraceId(candidate)) {
                    return candidate;
                }
            }
        }

        // [traceId        otherId]
        // use regex trace id is after "[" and before 2 or more spaces
        Matcher matcher = TRACE_ID_WITH_SPACES_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }


    private boolean isTraceId(String value) {
        return value != null && TRACE_ID_PATTERN.matcher(value).matches();
    }

    private LogbookRecord getOrCreateByRequestId(String requestId) {
        return recordsByRequestId.computeIfAbsent(requestId, id -> {
            LogbookRecord r = new LogbookRecord();
            r.setRequestId(id);
            return r;
        });
    }

}