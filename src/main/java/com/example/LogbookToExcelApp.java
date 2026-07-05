package com.example;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class LogbookToExcelApp {

    public static void main(String[] args) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose logbook log file");

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

        File inputFile = chooser.getSelectedFile();
        Path input = inputFile.toPath();

        String fileName = inputFile.getName();
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        Path output = inputFile.toPath()
                .getParent()
                .resolve(baseName + ".xlsx");

        try {
            LogbookParser parser = new LogbookParser();
            List<LogbookRecord> records = parser.parse(input);

            ExcelWriter writer = new ExcelWriter();
            writer.write(records, output);

            JOptionPane.showMessageDialog(
                    null,
                    "Excel file saved successfully:\n" + output.toAbsolutePath(),
                    "Success",
                    JOptionPane.INFORMATION_MESSAGE
            );
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                    null,
                    "Failed to process file:\n" + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }
}