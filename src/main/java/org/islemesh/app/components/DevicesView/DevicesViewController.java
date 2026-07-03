package org.islemesh.app.components.DevicesView;

import org.islemesh.app.IsleConfig;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Device discovery + guided onboarding. Leverages the isle CLI:
 *   isle discovery check / start / stop   — discovery-mode gate
 *   isle scan --save                      — populate the ledger
 *   isle devices check / add / onboard    — the known-devices ledger
 *   isle onboard <id> steps               — per-situation walkthrough
 */
public class DevicesViewController {

    @FXML private Label discoveryStatusLabel;
    @FXML private Button toggleDiscoveryButton;
    @FXML private Button scanButton;
    @FXML private Label summaryLabel;
    @FXML private VBox deviceList;
    @FXML private VBox consoleSection;
    @FXML private ScrollPane consoleScroll;
    @FXML private Label consoleOutput;

    private Runnable onBack;
    private boolean discoveryActive = false;
    private boolean busy = false;

    public void init(String role, Runnable onBack) {
        this.onBack = onBack;
        refresh();
    }

    // ── Refresh ──────────────────────────────────────────────
    private void refresh() {
        loadDiscoveryState();
        loadDevices();
    }

    private void loadDiscoveryState() {
        discoveryActive = false;
        String session = null, expires = null;
        for (String line : IsleConfig.runIsle("discovery", "check")) {
            if (!line.startsWith("discovery")) continue;
            Map<String, String> kv = IsleConfig.parseKvTokens(line);
            discoveryActive = "true".equals(kv.get("active"));
            session = kv.get("session");
            expires = kv.get("expires_epoch");
        }
        if (discoveryActive) {
            String extra = session != null ? "  (" + session + ")" : "";
            discoveryStatusLabel.setText("Discovery mode is ON" + extra
                + "\nNew devices detected on plug-in are tracked this session.");
            discoveryStatusLabel.setStyle("-fx-text-fill: #27ae60;");
            toggleDiscoveryButton.setText("Stop Discovery");
            scanButton.setDisable(false);
        } else {
            discoveryStatusLabel.setText("Discovery mode is OFF"
                + "\nTurn it on, then plug in a device or press Scan Now.");
            discoveryStatusLabel.setStyle("-fx-text-fill: #e67e22;");
            toggleDiscoveryButton.setText("Start Discovery");
        }
    }

    private void loadDevices() {
        deviceList.getChildren().clear();
        List<Map<String, String>> devices = new ArrayList<>();
        String summary = "";
        for (String line : IsleConfig.runIsle("devices", "check")) {
            if (line.startsWith("device ")) {
                devices.add(IsleConfig.parseKvTokens(line.substring("device ".length())));
            } else if (line.startsWith("summary")) {
                Map<String, String> kv = IsleConfig.parseKvTokens(line.substring("summary".length()));
                summary = kv.getOrDefault("devices", "0") + " device(s), "
                        + kv.getOrDefault("pending", "0") + " awaiting a decision";
            }
        }
        summaryLabel.setText(summary);

        if (devices.isEmpty()) {
            Label empty = new Label("No devices recorded yet. Start discovery and press Scan Now.");
            empty.getStyleClass().add("status-text");
            deviceList.getChildren().add(empty);
            return;
        }
        for (Map<String, String> d : devices) deviceList.getChildren().add(deviceCard(d));
    }

    // ── Device card ─────────────────────────────────────────
    private VBox deviceCard(Map<String, String> d) {
        String ip = d.getOrDefault("ip", "");
        String mac = d.getOrDefault("mac", "");
        String situation = d.getOrDefault("situation", "?");
        String decision = d.getOrDefault("decision", "none");
        String services = d.getOrDefault("services", "");

        VBox card = new VBox(6);
        card.getStyleClass().add("status-card");
        card.setPadding(new Insets(10, 12, 10, 12));

        HBox top = new HBox(10);
        Label title = new Label(ip.isEmpty() ? mac : ip);
        title.getStyleClass().add("permission-name");
        Label badge = situationBadge(situation);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        top.getChildren().addAll(title, badge, spacer);

        Label sub = new Label(mac + (services.isEmpty() ? "" : "   ports: " + services)
                + "   decision: " + decision);
        sub.getStyleClass().add("permission-desc");

        card.getChildren().addAll(top, sub);

        if (!"onboarded".equals(situation)) {
            Button onboardBtn = new Button(situationAction(situation));
            onboardBtn.getStyleClass().addAll("btn", "btn-primary", "btn-small");
            String id = !mac.isEmpty() ? mac : ip;
            onboardBtn.setOnAction(e -> openWizard(id, ip, situation));
            HBox actions = new HBox(onboardBtn);
            actions.setPadding(new Insets(4, 0, 0, 0));
            card.getChildren().add(actions);
        }
        return card;
    }

    private Label situationBadge(String situation) {
        Label b = new Label(situationLabel(situation));
        String color;
        switch (situation) {
            case "onboarded":          color = "#27ae60"; break;
            case "installable_remote": color = "#2980b9"; break;
            case "manual_install":     color = "#e67e22"; break;
            case "firewalled":         color = "#c0392b"; break;
            default:                   color = "#7f8c8d"; break;
        }
        b.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; "
                + "-fx-padding: 2 8 2 8; -fx-background-radius: 10; -fx-font-size: 11;");
        return b;
    }

    private String situationLabel(String s) {
        switch (s) {
            case "onboarded":          return "On mesh";
            case "installable_remote": return "Remote install";
            case "manual_install":     return "Manual install";
            case "firewalled":         return "Firewalled";
            default:                   return s;
        }
    }

    private String situationAction(String s) {
        return "installable_remote".equals(s) ? "Onboard (remote)" : "Onboard (guide)";
    }

    // ── Guided onboarding wizard ─────────────────────────────
    private void openWizard(String id, String ip, String situation) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Onboard " + (ip.isEmpty() ? id : ip));
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (deviceList.getScene() != null) dialog.initOwner(deviceList.getScene().getWindow());

        VBox content = new VBox(10);
        content.setPadding(new Insets(16));
        content.setPrefWidth(580);

        Label header = new Label("Guided onboarding — " + situationLabel(situation));
        header.getStyleClass().add("section-header");
        content.getChildren().add(header);

        // Walkthrough steps from the CLI (single source of truth).
        for (String line : IsleConfig.runIsle("onboard", id, "steps")) {
            if (!line.startsWith("step|")) continue;
            String[] parts = line.split("\\|", 4);
            if (parts.length < 4) continue;
            VBox step = new VBox(2);
            Label t = new Label(parts[1] + ". " + parts[2]);
            t.getStyleClass().add("permission-name");
            Label dsc = new Label(parts[3]);
            dsc.getStyleClass().add("permission-desc");
            dsc.setWrapText(true);
            step.getChildren().addAll(t, dsc);
            content.getChildren().add(step);
        }

        TextArea console = new TextArea();
        console.setEditable(false);
        console.setPrefRowCount(8);
        console.setVisible(false);
        console.setManaged(false);

        if ("installable_remote".equals(situation)) {
            content.getChildren().add(new Separator());
            TextField userField = new TextField(System.getProperty("user.name"));
            userField.setPromptText("SSH username on the device");
            PasswordField pwField = new PasswordField();
            pwField.setPromptText("SSH password (used once, never stored)");
            Button installBtn = new Button("Install Now");
            installBtn.getStyleClass().addAll("btn", "btn-primary");

            installBtn.setOnAction(e -> {
                console.setVisible(true);
                console.setManaged(true);
                console.clear();
                installBtn.setDisable(true);
                runRemoteInstall(id, userField.getText().trim(), pwField.getText(), console, installBtn);
            });
            content.getChildren().addAll(
                labeled("SSH username", userField),
                labeled("SSH password", pwField),
                installBtn, console);
        } else {
            // manual_install / firewalled — follow steps on the device, track here.
            content.getChildren().add(new Separator());
            Button markBtn = new Button("Mark as onboarding");
            markBtn.getStyleClass().addAll("btn", "btn-success");
            Label note = new Label("");
            note.getStyleClass().add("status-text");
            markBtn.setOnAction(e -> {
                IsleConfig.runIsle("devices", "onboard", id);
                note.setText("Marked. It will appear as onboarded once it runs 'isle join'.");
            });
            content.getChildren().addAll(markBtn, note);
        }

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        dialog.getDialogPane().setContent(sp);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);
        dialog.showAndWait();
        refresh();
    }

    private VBox labeled(String label, Region field) {
        VBox box = new VBox(3);
        Label l = new Label(label);
        l.getStyleClass().add("permission-desc");
        box.getChildren().addAll(l, field);
        return box;
    }

    private void runRemoteInstall(String id, String user, String password, TextArea console, Button installBtn) {
        Thread t = new Thread(() -> {
            try {
                String isle = IsleConfig.findIsleCli();
                if (isle == null) {
                    Platform.runLater(() -> console.appendText("ERROR: isle CLI not found\n"));
                    return;
                }
                ProcessBuilder pb = new ProcessBuilder(isle, "onboard", id, "--user", user, "--yes");
                pb.environment().put("ISLE_SSH_PASSWORD", password);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        final String clean = line.replaceAll("\\[[;\\d]*m", "");
                        Platform.runLater(() -> console.appendText(clean + "\n"));
                    }
                }
                int code = p.waitFor();
                Platform.runLater(() -> console.appendText(
                    code == 0 ? "\nDone.\n" : "\nFinished with exit " + code + ".\n"));
            } catch (Exception ex) {
                Platform.runLater(() -> console.appendText("ERROR: " + ex.getMessage() + "\n"));
            } finally {
                Platform.runLater(() -> installBtn.setDisable(false));
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ── Actions ─────────────────────────────────────────────
    @FXML
    private void onToggleDiscovery() {
        if (discoveryActive) {
            IsleConfig.runIsle("discovery", "stop");
        } else {
            IsleConfig.runIsle("discovery", "start", "--timeout", "30");
        }
        refresh();
    }

    @FXML
    private void onScan() {
        if (busy) return;
        busy = true;
        consoleSection.setVisible(true);
        consoleSection.setManaged(true);
        consoleOutput.setText("Scanning the isle for devices...\n");
        scanButton.setDisable(true);
        scanButton.setText("Scanning...");

        Thread t = new Thread(() -> {
            String islePath = IsleConfig.findIsleCli();
            try {
                if (islePath != null) {
                    ProcessBuilder pb = new ProcessBuilder(islePath, "scan", "--save");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            final String clean = line.replaceAll("\\[[;\\d]*m", "");
                            Platform.runLater(() -> appendConsole(clean + "\n"));
                        }
                    }
                    p.waitFor();
                }
            } catch (Exception e) {
                Platform.runLater(() -> appendConsole("ERROR: " + e.getMessage() + "\n"));
            } finally {
                Platform.runLater(() -> {
                    busy = false;
                    scanButton.setDisable(false);
                    scanButton.setText("Scan Now");
                    loadDevices();
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onAddManual() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Add device manually");
        dialog.setHeaderText("Add a device you couldn't auto-detect (firewalled)");
        dialog.setContentText("Device IP address:");
        dialog.showAndWait().ifPresent(ip -> {
            ip = ip.trim();
            if (!ip.isEmpty()) {
                IsleConfig.runIsle("devices", "add", "--ip", ip);
                refresh();
            }
        });
    }

    @FXML
    private void onRefresh() { refresh(); }

    @FXML
    private void onBack() { if (onBack != null) onBack.run(); }

    private void appendConsole(String text) {
        consoleOutput.setText(consoleOutput.getText() + text);
        consoleScroll.setVvalue(1.0);
    }
}
