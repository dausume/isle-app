package org.islemesh.app.components.DiagnosticsView;

import org.islemesh.app.IsleConfig;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

public class DiagnosticsViewController {

    @FXML private VBox isleTestsList;
    @FXML private VBox mdnsTestsList;
    @FXML private VBox remoteTestsList;
    @FXML private Label remoteHeader;
    @FXML private Label summaryLabel;
    @FXML private Button runButton;

    private Runnable onBack;
    private boolean running = false;

    // Human-readable labels for each test key
    private static final Map<String, String> TEST_LABELS = new LinkedHashMap<>();
    static {
        // Isle routing tests
        TEST_LABELS.put("isle.no_localhost", "No localhost .isle resolution");
        TEST_LABELS.put("isle.forwarding", "DNS forwarding to router");
        TEST_LABELS.put("isle.router_reachable", "Router is reachable");
        TEST_LABELS.put("isle.router_dns", "Router DNS responding");
        TEST_LABELS.put("isle.bridge", "isle-br-0 bridge exists");
        TEST_LABELS.put("isle.agent_macvlan", "Agent has isle DHCP lease");

        // mDNS / agent tests
        TEST_LABELS.put("mdns.agent_running", "Agent container running");
        TEST_LABELS.put("mdns.agent_health", "Agent health endpoint");
        TEST_LABELS.put("mdns.service_running", "mDNS service active");
        TEST_LABELS.put("mdns.host_agent", "Host agent service active");
        TEST_LABELS.put("mdns.registry", "Registry file valid");
        TEST_LABELS.put("mdns.router_vm", "Router VM running");

        // Remote reachability tests (shown conditionally based on mode)
        TEST_LABELS.put("remote.agent_running", "Remote agent running");
        TEST_LABELS.put("remote.has_isle_ip", "Has isle DHCP lease");
        TEST_LABELS.put("remote.avahi_publishing", "Avahi publishing mDNS");
        TEST_LABELS.put("remote.router_knows_us", "Router has .isle DNS entry");
    }

    // Informational keys (not pass/fail tests)
    private static boolean isInfoKey(String key) {
        return key.endsWith("_ip") || key.endsWith("_count") || key.equals("isle.router_ip")
            || key.equals("isle.agent_ip") || key.equals("mdns.app_count")
            || key.equals("remote.mode") || key.equals("remote.isle_ip")
            || key.equals("remote.router_entry") || key.equals("remote.visible_devices");
    }

    public void init(Runnable onBack) {
        this.onBack = onBack;
        // Show placeholder state
        showPlaceholder(isleTestsList, "isle");
        showPlaceholder(mdnsTestsList, "mdns");
        // Remote section starts hidden — shown after results come back
        summaryLabel.setText("Press Run Tests to start diagnostics.");
        // Auto-run on open
        onRunTests();
    }

    private void showPlaceholder(VBox list, String prefix) {
        list.getChildren().clear();
        for (var entry : TEST_LABELS.entrySet()) {
            if (entry.getKey().startsWith(prefix + ".")) {
                list.getChildren().add(buildRow("[  ]", entry.getValue(), "Pending", "permission-icon-warn"));
            }
        }
    }

    @FXML
    private void onRunTests() {
        if (running) return;
        running = true;
        runButton.setDisable(true);
        runButton.setText("Running...");
        summaryLabel.setText("Running diagnostics...");

        Thread thread = new Thread(() -> {
            Map<String, String> results = runDiagnostics();

            Platform.runLater(() -> {
                renderResults(results);
                running = false;
                runButton.setDisable(false);
                runButton.setText("Run Tests");
            });
        });
        thread.setDaemon(true);
        thread.start();
    }

    private Map<String, String> runDiagnostics() {
        Map<String, String> results = new LinkedHashMap<>();
        try {
            String islePath = IsleConfig.findIsleCli();
            if (islePath == null) {
                results.put("error", "Isle CLI not found");
                return results;
            }

            ProcessBuilder pb = new ProcessBuilder(islePath, "test", "check");
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
        isleTestsList.getChildren().clear();
        mdnsTestsList.getChildren().clear();
        remoteTestsList.getChildren().clear();

        if (results.containsKey("error")) {
            summaryLabel.setText("Error: " + results.get("error"));
            return;
        }

        // Determine mode for remote section header
        String mode = results.getOrDefault("remote.mode", "none");
        if ("remote".equals(mode)) {
            remoteHeader.setText("Remote Visibility (this device)");
        } else if ("core".equals(mode)) {
            remoteHeader.setText("Remote Devices (visible from core)");
        } else {
            remoteHeader.setText("Remote Devices");
        }

        // Show/hide remote section based on whether we have remote data
        boolean hasRemoteData = results.keySet().stream().anyMatch(k ->
            k.startsWith("remote.") && !k.equals("remote.mode"));
        remoteTestsList.setVisible(hasRemoteData);
        remoteTestsList.setManaged(hasRemoteData);
        remoteHeader.setVisible(hasRemoteData);
        remoteHeader.setManaged(hasRemoteData);

        int passed = 0;
        int failed = 0;
        int skipped = 0;

        for (var entry : TEST_LABELS.entrySet()) {
            String key = entry.getKey();
            String label = entry.getValue();
            String value = results.getOrDefault(key, "skip");

            // Skip remote tests that aren't applicable to current mode
            if (key.startsWith("remote.") && "core".equals(mode)) continue;
            if (key.startsWith("remote.") && "none".equals(mode)) continue;

            // Append info values (like IP addresses) to the label
            String extra = "";
            if (key.equals("isle.forwarding")) {
                String ip = results.get("isle.router_ip");
                if (ip != null) extra = " (" + ip + ")";
            } else if (key.equals("isle.agent_macvlan")) {
                String ip = results.get("isle.agent_ip");
                if (ip != null) extra = " (" + ip + ")";
            } else if (key.equals("mdns.registry")) {
                String count = results.get("mdns.app_count");
                if (count != null) extra = " (" + count + " apps)";
            } else if (key.equals("remote.has_isle_ip")) {
                String ip = results.get("remote.isle_ip");
                if (ip != null) extra = " (" + ip + ")";
            } else if (key.equals("remote.router_knows_us")) {
                String entry2 = results.get("remote.router_entry");
                if (entry2 != null) extra = " (" + entry2 + ")";
            }

            String icon;
            String displayValue;
            String iconStyle;

            switch (value) {
                case "pass":
                    icon = "[OK]";
                    displayValue = "Pass" + extra;
                    iconStyle = "permission-icon-ok";
                    passed++;
                    break;
                case "fail":
                    icon = "[!!]";
                    displayValue = "Fail" + extra;
                    iconStyle = "permission-icon-fail";
                    failed++;
                    break;
                case "skip":
                    icon = "[--]";
                    displayValue = "Skipped";
                    iconStyle = "permission-icon-warn";
                    skipped++;
                    break;
                default:
                    icon = "[??]";
                    displayValue = value + extra;
                    iconStyle = "permission-icon-warn";
                    skipped++;
                    break;
            }

            HBox row = buildRow(icon, label, displayValue, iconStyle);

            if (key.startsWith("isle.")) {
                isleTestsList.getChildren().add(row);
            } else if (key.startsWith("mdns.")) {
                mdnsTestsList.getChildren().add(row);
            } else if (key.startsWith("remote.")) {
                remoteTestsList.getChildren().add(row);
            }
        }

        // For core mode, show visible device count instead of per-test results
        if ("core".equals(mode)) {
            String deviceCount = results.getOrDefault("remote.visible_devices", "0");
            String dnsReadable = results.getOrDefault("remote.router_dns_readable", "skip");
            remoteTestsList.getChildren().clear();
            remoteTestsList.getChildren().add(buildRow(
                "pass".equals(dnsReadable) ? "[OK]" : "[!!]",
                "Router DNS readable",
                "pass".equals(dnsReadable) ? "Pass" : "Fail",
                "pass".equals(dnsReadable) ? "permission-icon-ok" : "permission-icon-fail"));
            remoteTestsList.getChildren().add(buildRow(
                Integer.parseInt(deviceCount) > 0 ? "[OK]" : "[--]",
                "Visible .isle devices",
                deviceCount + " device(s)",
                Integer.parseInt(deviceCount) > 0 ? "permission-icon-ok" : "permission-icon-warn"));
            remoteTestsList.setVisible(true);
            remoteTestsList.setManaged(true);
            remoteHeader.setVisible(true);
            remoteHeader.setManaged(true);
        }

        int total = passed + failed + skipped;
        if (failed == 0) {
            summaryLabel.setText("All " + passed + "/" + total + " tests passed.");
            summaryLabel.setStyle("-fx-text-fill: #27ae60;");
        } else {
            summaryLabel.setText(passed + " passed, " + failed + " failed, " + skipped + " skipped.");
            summaryLabel.setStyle("-fx-text-fill: #e74c3c;");
        }
    }

    private HBox buildRow(String icon, String label, String value, String iconStyle) {
        HBox row = new HBox(10);
        row.getStyleClass().add("permission-row");

        Label iconLabel = new Label(icon);
        iconLabel.getStyleClass().addAll("permission-icon", iconStyle);

        Label nameLabel = new Label(label);
        nameLabel.getStyleClass().add("permission-name");

        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("permission-desc");

        row.getChildren().addAll(iconLabel, nameLabel, valueLabel);
        return row;
    }

    @FXML
    private void onBack() {
        if (onBack != null) {
            onBack.run();
        }
    }
}
