package com.example;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class LogbookToExcelApp {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: <input-log-file> <output-xlsx-file>");
            return;
        }

        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);

        LogbookParser parser = new LogbookParser();
        List<LogbookRecord> records = parser.parse(input);

        System.out.println("Parsed records: " + records.size());

        ExcelWriter writer = new ExcelWriter();
        writer.write(records, output);

        System.out.println("Done: " + output.toAbsolutePath());
    }
}