package com.example;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ExcelWriter {

    private static final List<String> COLUMNS = List.of(
            "requestId",
            "correlation-id",
            "timestamp",
            "http_method",
            "endpoint",
            "query_param",
            "status",
            "Content-Type",
            "user-agent",
            "Duration",
            "error"
    );

    public void write(List<LogbookRecord> records, Path outputFile) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("logbook");

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setVerticalAlignment(VerticalAlignment.TOP);
            headerStyle.setAlignment(HorizontalAlignment.LEFT);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(COLUMNS.get(i));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, widthFor(COLUMNS.get(i)));
            }

            int rowIndex = 1;
            for (LogbookRecord record : records) {
                Row row = sheet.createRow(rowIndex++);

                set(row, 0, record.getRequestId());
                set(row, 1, record.getCorrelationId());
                set(row, 2, record.getTimestamp());
                set(row, 3, record.getHeaders().get("http_method"));
                set(row, 4, record.getHeaders().get("endpoint"));
                set(row, 5, record.getHeaders().get("query_param"));
                setNumber(row, 6, record.getHeaders().get("status"));
                set(row, 7, record.getHeaders().get("Content-Type"));
                set(row, 8, record.getHeaders().get("user-agent"));
                set(row, 9, record.getHeaders().get("Duration"));
                set(row, 10, compactError(record.getError()));
            }

            try (OutputStream os = Files.newOutputStream(outputFile)) {
                workbook.write(os);
            }
        }
    }

    private void set(Row row, int col, String value) {
        row.createCell(col).setCellValue(nvl(value));
    }

    private void setNumber(Row row, int col, String value) {
        Cell cell = row.createCell(col);
        if (value == null || value.isBlank()) {
            cell.setBlank();
            return;
        }

        try {
            cell.setCellValue(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            cell.setCellValue(value);
        }
    }

    private String compactError(String error) {
        if (error == null) {
            return "";
        }
        return error.replace("\r", " ")
                .replace("\n", " | ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private int widthFor(String text) {
        return Math.min(255 * 256, Math.max(8, text.length() + 2) * 256);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}