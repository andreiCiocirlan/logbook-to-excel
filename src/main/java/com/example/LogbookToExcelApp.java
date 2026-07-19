package com.example;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

import javax.swing.*;
import java.awt.*;

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

        JDialog loadingDialog = createLoadingDialog();

        new Thread(() -> {
            StringBuilder summary = new StringBuilder();
            try {
                LogbookParser parser = new LogbookParser();
                ExcelWriter writer = new ExcelWriter();

                for (File inputFile : inputFiles) {
                    Path input = inputFile.toPath();

                    String fileName = inputFile.getName();
                    int dot = fileName.lastIndexOf('.');
                    String baseName = dot > 0
                            ? fileName.substring(0, dot)
                            : fileName;

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
                    } catch (Exception ex) {
                        summary.append("❌ ")
                                .append(fileName)
                                .append(": ")
                                .append(ex.getMessage())
                                .append('\n');
                    }
                }
            } finally {
                SwingUtilities.invokeLater(() -> {
                    loadingDialog.dispose();

                    JOptionPane.showMessageDialog(
                            null,
                            summary.toString(),
                            "Finished",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                });
            }
        }).start();

        loadingDialog.setVisible(true);
    }

    private static JDialog createLoadingDialog() {
        JDialog dialog = new JDialog((Frame) null, "Processing...", true);

        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        panel.add(new JLabel("Please wait while the log file(s) are processed..."),
                BorderLayout.NORTH);
        panel.add(progressBar, BorderLayout.CENTER);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(null);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        return dialog;
    }
}