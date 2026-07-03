package org.islemesh.app.components.SecurityView;

import org.islemesh.app.IsleConfig;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

public class SecurityViewController {

    @FXML private Label ispInfoLabel;
    @FXML private VBox exposureList;
    @FXML private VBox routerList;
    @FXML private VBox configList;
    @FXML private Label summaryLabel;
    @FXML private Button scanButton;
    @FXML private Button hardenButton;
    @FXML private VBox consoleSection;
    @FXML private ScrollPane consoleScroll;
    @FXML private Label consoleOutput;

    private Runnable onBack;
    private boolean running = false;

    // Check keys grouped by section
    private static final String[][] EXPOSURE_CHECKS = {
        {"port_exposure",      "Ports 80/443 not exposed to ISP"},
        {"mdns_exposure",      "mDNS not broadcasting to ISP"},
        {"discovery_exposure", "Discovery beacon not on host"},
        {"firewall_rules",     "Firewall blocks isle ports on ISP"},
        {"route_isolation",    "No route from isle to ISP"},
        {"no_nat",             "No NAT from isle to ISP"},
    };

    private static final String[][] ROUTER_CHECKS = {
        {"router_airgap",       "Router VM air-gapped (no internet)"},
        {"router_dns_isolated", "Router DNS is local only"},
    };

    private static final String[][] CONFIG_CHECKS = {
        {"compose_bindings", "Docker ports bound to localhost"},
        {"cert_names",       "SSL certs use local domains only"},
        {"dns_leakage",      "No .isle DNS leaking upstream"},
    };

    public void init(Runnable onBack) {
        this.onBack = onBack;
        onScan();
    }

    @FXML
    private void onScan() {
        if (running) return;
        running = true;
        scanButton.setDisable(true);
        scanButton.setText("Scanning...");
        summaryLabel.setText("Scanning...");

        Thread thread = new Thread(() -> {
            Map<String, String> results = runSecurityCheck();
            Platform.runLater(() -> {
                renderResults(results);
                running = false;
                scanButton.setDisable(false);
                scanButton.setText("Scan");
            });
        });
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onHarden() {
        consoleSection.setVisible(true);
        consoleSection.setManaged(true);
        consoleOutput.setText("");
        hardenButton.setDisable(true);
        hardenButton.setText("Running...");

        Thread thread = new Thread(() -> {
            try {
                String islePath = IsleConfig.findIsleCli();
                if (islePath == null) {
                    Platform.runLater(() -> appendConsole("ERROR: Isle CLI not found\n"));
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder("pkexec", islePath, "security", "harden");
                pb.redirectErrorStream(true);
                Process process = pb.start();

                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String output = line;
                        Platform.runLater(() -> appendConsole(output + "\n"));
                    }
                }

                int exitCode = process.waitFor();
                Platform.runLater(() -> {
                    if (exitCode == 0) {
                        appendConsole("\nHardening complete. Re-scanning...\n");
                        onScan();
                    } else {
                        appendConsole("\nHardening failed (exit " + exitCode + ")\n");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> appendConsole("ERROR: " + e.getMessage() + "\n"));
            } finally {
                Platform.runLater(() -> {
                    hardenButton.setDisable(false);
                    hardenButton.setText("Harden");
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void appendConsole(String text) {
        String clean = text.replaceAll("\u001B\\[[;\\d]*m", "");
        consoleOutput.setText(consoleOutput.getText() + clean);
        consoleScroll.setVvalue(1.0);
    }

    private Map<String, String> runSecurityCheck() {
        Map<String, String> results = new LinkedHashMap<>();
        try {
            String islePath = IsleConfig.findIsleCli();
            if (islePath == null) {
                results.put("error", "Isle CLI not found");
                return results;
            }

            ProcessBuilder pb = new ProcessBuilder(islePath, "security", "check");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        results.put(line.substring(0, eq), line.substring(eq + 1));
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            results.put("error", e.getMessage());
        }
        return results;
    }

    private void renderResults(Map<String, String> results) {
        exposureList.getChildren().clear();
        routerList.getChildren().clear();
        configList.getChildren().clear();

        if (results.containsKey("error")) {
            summaryLabel.setText("Error: " + results.get("error"));
            return;
        }

        // ISP info
        String ispIface = results.getOrDefault("security.isp_interface", "unknown");
        String ispIp = results.getOrDefault("security.isp_ip", "unknown");
        ispInfoLabel.setText("ISP interface: " + ispIface + " (" + ispIp + ")");

        int passed = 0, failed = 0;

        // Render each section
        passed += renderSection(exposureList, EXPOSURE_CHECKS, results);
        failed += countFails(EXPOSURE_CHECKS, results);

        passed += renderSection(routerList, ROUTER_CHECKS, results);
        failed += countFails(ROUTER_CHECKS, results);

        passed += renderSection(configList, CONFIG_CHECKS, results);
        failed += countFails(CONFIG_CHECKS, results);

        int total = passed + failed;
        if (failed == 0) {
            summaryLabel.setText("All " + total + " checks passed. Isle-mesh is not visible to ISP.");
            summaryLabel.setStyle("-fx-text-fill: #27ae60;");
            hardenButton.setVisible(false);
            hardenButton.setManaged(false);
        } else {
            summaryLabel.setText(passed + " passed, " + failed + " failed. Run Harden to fix.");
            summaryLabel.setStyle("-fx-text-fill: #e74c3c;");
            hardenButton.setVisible(true);
            hardenButton.setManaged(true);
        }
    }

    private int renderSection(VBox list, String[][] checks, Map<String, String> results) {
        int passed = 0;
        for (String[] check : checks) {
            String key = "security." + check[0];
            String label = check[1];
            String value = results.getOrDefault(key, "skip");

            String icon, iconStyle;
            switch (value) {
                case "pass": icon = "[OK]"; iconStyle = "permission-icon-ok"; passed++; break;
                case "fail": icon = "[!!]"; iconStyle = "permission-icon-fail"; break;
                case "warn": icon = "[??]"; iconStyle = "permission-icon-warn"; break;
                default:     icon = "[--]"; iconStyle = "permission-icon-warn"; break;
            }

            HBox row = new HBox(10);
            row.getStyleClass().add("permission-row");

            Label iconLabel = new Label(icon);
            iconLabel.getStyleClass().addAll("permission-icon", iconStyle);

            Label nameLabel = new Label(label);
            nameLabel.getStyleClass().add("permission-name");

            Label statusLabel = new Label(value.substring(0, 1).toUpperCase() + value.substring(1));
            statusLabel.getStyleClass().add("permission-desc");

            row.getChildren().addAll(iconLabel, nameLabel, statusLabel);
            list.getChildren().add(row);
        }
        return passed;
    }

    private int countFails(String[][] checks, Map<String, String> results) {
        int fails = 0;
        for (String[] check : checks) {
            if ("fail".equals(results.get("security." + check[0]))) fails++;
        }
        return fails;
    }

    @FXML
    private void onBack() {
        if (onBack != null) onBack.run();
    }
}
