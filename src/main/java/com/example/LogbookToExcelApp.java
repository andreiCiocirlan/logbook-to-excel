package com.example;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class LogbookToExcelApp {

    public static void main(String[] args) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose logbook log file(s)");
        chooser.setMultiSelectionEnabled(true);

        int result = chooser.showOpenDialog(null);
        if (result != JFileChooser.APPROVE_OPTION) {
            JOptionPane.showMessageDialog(
                    null,
                    "No file selected.",
                    "Cancelled",
                    JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        File[] inputFiles = chooser.getSelectedFiles();

        LogbookParser parser = new LogbookParser();
        ExcelWriter writer = new ExcelWriter();

        StringBuilder summary = new StringBuilder();

        for (File inputFile : inputFiles) {
            Path input = inputFile.toPath();

            String fileName = inputFile.getName();
            int dot = fileName.lastIndexOf('.');
            String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;

            Path output = input.getParent().resolve(baseName + ".xlsx");

            try {
                List<LogbookRecord> records = parser.parse(input);

                if (records.isEmpty()) {
                    summary.append("❌ ")
                            .append(fileName)
                            .append(": No Logbook requests found.\n");
                    continue;
                }

                writer.write(records, output);

                summary.append("✅ ")
                        .append(fileName)
                        .append(" -> ")
                        .append(output.getFileName())
                        .append('\n');

            } catch (Exception e) {
                summary.append("❌ ")
                        .append(fileName)
                        .append(": ")
                        .append(e.getMessage())
                        .append('\n');
            }
        }

        JOptionPane.showMessageDialog(
                null,
                summary.toString(),
                "Finished",
                JOptionPane.INFORMATION_MESSAGE
        );
    }
}