package org.islemesh.app.components.HomePage;

import org.islemesh.app.IsleConfig;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class HomePageController {

    @FXML private Label modeBanner;
    @FXML private VBox componentsList;
    @FXML private VBox createSection;
    @FXML private Label createStatusLabel;
    @FXML private Button createButton;
    @FXML private VBox joinIsleSection;
    @FXML private Label joinIsleLabel;
    @FXML private Button joinIsleButton;
    @FXML private VBox consoleSection;
    @FXML private ScrollPane consoleScroll;
    @FXML private Label consoleOutput;
    @FXML private Button refreshButton;

    private String role;
    private Runnable onChangeRole;
    private Runnable onManagePermissions;
    private Runnable onViewApps;
    private Runnable onDiagnostics;
    private Runnable onSecurity;
    private Runnable onDiscoverDevices;
    private Runnable onNetworkPorts;
    private boolean createRunning = false;

    private static final Map<String, String> COMPONENT_LABELS = new LinkedHashMap<>();
    static {
        COMPONENT_LABELS.put("router", "Router VM");
        COMPONENT_LABELS.put("agent", "Isle Agent");
        COMPONENT_LABELS.put("host-agent", "Host Agent");
        COMPONENT_LABELS.put("mdns", "mDNS Service");
        COMPONENT_LABELS.put("bridge", "Isle Bridge");
        COMPONENT_LABELS.put("apps", "Registered Apps");
    }

    public void init(String role, Runnable onChangeRole, Runnable onManagePermissions, Runnable onViewApps, Runnable onDiagnostics, Runnable onSecurity, Runnable onDiscoverDevices, Runnable onNetworkPorts) {
        this.role = role;
        this.onChangeRole = onChangeRole;
        this.onManagePermissions = onManagePermissions;
        this.onViewApps = onViewApps;
        this.onDiagnostics = onDiagnostics;
        this.onSecurity = onSecurity;
        this.onDiscoverDevices = onDiscoverDevices;
        this.onNetworkPorts = onNetworkPorts;

        String displayName = IsleConfig.roleDisplayName(role);
        modeBanner.setText(displayName);

        if ("core".equals(role)) {
            modeBanner.getStyleClass().add("banner-core");
        } else {
            modeBanner.getStyleClass().add("banner-connect");
            // A remote/connect node doesn't "create" an island — it joins one.
            createSection.setVisible(false);
            createSection.setManaged(false);
        }

        refreshStatus();

        // On a node that isn't a running core, look for an existing isle to join.
        if (!"core".equals(role)) {
            checkForIsleToJoin();
        }
    }

    /**
     * Route 2: the device discovers an existing isle over mDNS and SUGGESTS joining.
     * The device joins itself — nothing reaches into any other machine.
     */
    private void checkForIsleToJoin() {
        Thread t = new Thread(() -> {
            Map<String, String> isle = IsleConfig.detectIsle();
            boolean found = "true".equals(isle.get("found"));
            String router = isle.getOrDefault("router", "");
            Platform.runLater(() -> {
                // Only suggest if we're not already running an agent here.
                Map<String, String> status = IsleConfig.checkComponentStatus();
                boolean alreadyOnMesh = "running".equals(status.get("agent"))
                        || "running".equals(status.get("host-agent"));
                if (found && !alreadyOnMesh) {
                    joinIsleLabel.setText("An isle was detected on this network"
                        + (router.isEmpty() ? "" : " (router " + router + ")")
                        + ".\nJoin it to connect this device to the mesh.");
                    joinIsleSection.setVisible(true);
                    joinIsleSection.setManaged(true);
                } else {
                    joinIsleSection.setVisible(false);
                    joinIsleSection.setManaged(false);
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onJoinIsle() {
        joinIsleButton.setDisable(true);
        joinIsleButton.setText("Joining...");
        consoleSection.setVisible(true);
        consoleSection.setManaged(true);
        consoleOutput.setText("");

        Thread thread = new Thread(() -> {
            try {
                String islePath = IsleConfig.findIsleCli();
                if (islePath == null) {
                    Platform.runLater(() -> appendConsole("ERROR: Could not find isle CLI\n"));
                    return;
                }
                // The device joins ITSELF — runs its own join, locally.
                ProcessBuilder pb = new ProcessBuilder("pkexec", islePath, "join");
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
                    appendConsole(exitCode == 0
                        ? "\n=== Joined the isle ===\n"
                        : "\n=== Join exited with code " + exitCode + " ===\n");
                    joinIsleButton.setDisable(false);
                    joinIsleButton.setText("Join this Isle");
                    if (exitCode == 0) {
                        joinIsleSection.setVisible(false);
                        joinIsleSection.setManaged(false);
                    }
                    refreshStatus();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    appendConsole("ERROR: " + e.getMessage() + "\n");
                    joinIsleButton.setDisable(false);
                    joinIsleButton.setText("Join this Isle");
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshStatus() {
        System.out.println("[HomePage] Refreshing component status...");
        Map<String, String> status = IsleConfig.checkComponentStatus();

        for (var entry : status.entrySet()) {
            System.out.println("[HomePage]   " + entry.getKey() + " = " + entry.getValue());
        }

        renderComponents(status);
        updateCreateButton(status);
    }

    private void renderComponents(Map<String, String> status) {
        componentsList.getChildren().clear();

        for (var entry : COMPONENT_LABELS.entrySet()) {
            String key = entry.getKey();
            String label = entry.getValue();
            String value = status.getOrDefault(key, "unknown");

            HBox row = new HBox(10);
            row.getStyleClass().add("permission-row");

            Label icon = new Label();
            icon.getStyleClass().add("permission-icon");

            String displayValue;
            switch (value) {
                case "running":
                case "up":
                    icon.setText("[OK]");
                    icon.getStyleClass().add("permission-icon-ok");
                    displayValue = "Running";
                    break;
                case "remote":
                    icon.setText("[OK]");
                    icon.getStyleClass().add("permission-icon-ok");
                    displayValue = "Remote";
                    break;
                case "stopped":
                    icon.setText("[--]");
                    icon.getStyleClass().add("permission-icon-fail");
                    displayValue = "Stopped";
                    break;
                case "down":
                case "none":
                    icon.setText("[  ]");
                    icon.getStyleClass().add("permission-icon-fail");
                    displayValue = "Not set up";
                    break;
                default:
                    // For apps count
                    if (key.equals("apps")) {
                        int count = 0;
                        try { count = Integer.parseInt(value); } catch (Exception ignored) {}
                        icon.setText(count > 0 ? "[OK]" : "[--]");
                        icon.getStyleClass().add(count > 0 ? "permission-icon-ok" : "permission-icon-warn");
                        displayValue = value + " app" + (count == 1 ? "" : "s");
                    } else {
                        icon.setText("[??]");
                        icon.getStyleClass().add("permission-icon-warn");
                        displayValue = value;
                    }
                    break;
            }

            Label nameLabel = new Label(label);
            nameLabel.getStyleClass().add("permission-name");

            Label statusLabel = new Label(displayValue);
            statusLabel.getStyleClass().add("permission-desc");

            row.getChildren().addAll(icon, nameLabel, statusLabel);
            componentsList.getChildren().add(row);
        }
    }

    private void updateCreateButton(Map<String, String> status) {
        // Check if core components are all running
        boolean routerOk = "running".equals(status.get("router")) || "remote".equals(status.get("router"));
        boolean agentOk = "running".equals(status.get("agent"));
        boolean mdnsOk = "running".equals(status.get("mdns"));

        if (routerOk && agentOk && mdnsOk) {
            createButton.setVisible(false);
            createButton.setManaged(false);
            createStatusLabel.setText("All core components are running.");
            createStatusLabel.setVisible(true);
            createStatusLabel.setManaged(true);
        } else {
            createButton.setVisible(true);
            createButton.setManaged(true);
            createStatusLabel.setVisible(false);
            createStatusLabel.setManaged(false);
        }
    }

    @FXML
    private void onIsleCreate() {
        if (createRunning) return;
        createRunning = true;

        createButton.setDisable(true);
        createButton.setText("Running...");
        consoleSection.setVisible(true);
        consoleSection.setManaged(true);
        consoleOutput.setText("");

        Thread thread = new Thread(() -> {
            try {
                String islePath = IsleConfig.findIsleCli();
                if (islePath == null) {
                    appendConsole("ERROR: Could not find isle CLI\n");
                    return;
                }

                // isle create requires sudo — use pkexec
                String scriptPath = findCreateScript(islePath);
                ProcessBuilder pb;
                if (scriptPath != null) {
                    pb = new ProcessBuilder("pkexec", "bash", scriptPath);
                } else {
                    pb = new ProcessBuilder("pkexec", islePath, "create");
                }
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
                final String result = exitCode == 0
                    ? "\n=== Isle Create completed successfully ===\n"
                    : "\n=== Isle Create exited with code " + exitCode + " ===\n";

                Platform.runLater(() -> {
                    appendConsole(result);
                    refreshStatus();
                });
            } catch (Exception e) {
                Platform.runLater(() -> appendConsole("ERROR: " + e.getMessage() + "\n"));
            } finally {
                Platform.runLater(() -> {
                    createButton.setDisable(false);
                    createButton.setText("Isle Create");
                    createRunning = false;
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void appendConsole(String text) {
        // Strip ANSI color codes for clean display
        String clean = text.replaceAll("\u001B\\[[;\\d]*m", "");
        consoleOutput.setText(consoleOutput.getText() + clean);
        // Auto-scroll to bottom
        consoleScroll.setVvalue(1.0);
    }

    @FXML
    private void onManagePermissions() {
        if (onManagePermissions != null) {
            onManagePermissions.run();
        }
    }

    @FXML
    private void onChangeRole() {
        if (onChangeRole != null) {
            onChangeRole.run();
        }
    }

    @FXML
    private void onViewApps() {
        if (onViewApps != null) {
            onViewApps.run();
        }
    }

    @FXML
    private void onDiagnostics() {
        if (onDiagnostics != null) {
            onDiagnostics.run();
        }
    }

    @FXML
    private void onSecurity() {
        if (onSecurity != null) {
            onSecurity.run();
        }
    }

    @FXML
    private void onDiscoverDevices() {
        if (onDiscoverDevices != null) {
            onDiscoverDevices.run();
        }
    }

    @FXML
    private void onNetworkPorts() {
        if (onNetworkPorts != null) {
            onNetworkPorts.run();
        }
    }

    @FXML
    private void onRefresh() {
        refreshStatus();
    }

    @FXML
    private void onCreateUsb() {
        Thread t = new Thread(() -> {
            List<java.util.Map<String, String>> drives = IsleConfig.listUsbDrives();
            Platform.runLater(() -> {
                if (drives.isEmpty()) {
                    new Alert(Alert.AlertType.INFORMATION,
                        "No USB drives found. Plug in a USB drive (make sure it's mounted), then try again.",
                        ButtonType.OK).showAndWait();
                    return;
                }
                List<String> choices = new ArrayList<>();
                HashMap<String, String> mountByChoice = new HashMap<>();
                for (var d : drives) {
                    String label = d.getOrDefault("label", "").replace("_", " ").trim();
                    String name = d.getOrDefault("name", "?");
                    String disp = (label.isEmpty() ? name : label)
                        + "  " + d.getOrDefault("size", "")
                        + "  (" + d.getOrDefault("mount", "") + ")";
                    choices.add(disp);
                    mountByChoice.put(disp, d.get("mount"));
                }
                ChoiceDialog<String> dlg = new ChoiceDialog<>(choices.get(0), choices);
                dlg.setTitle("Create USB Installer");
                dlg.setHeaderText("Make a USB drive into an isle-mesh installer");
                dlg.setContentText("Choose a drive (files are copied — the drive is NOT erased):");
                dlg.showAndWait().ifPresent(sel -> {
                    String mount = mountByChoice.get(sel);
                    if (mount != null && !mount.isEmpty()) runCreateUsb(mount);
                });
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private void runCreateUsb(String mount) {
        consoleSection.setVisible(true);
        consoleSection.setManaged(true);
        consoleOutput.setText("");

        Thread thread = new Thread(() -> {
            try {
                String islePath = IsleConfig.findIsleCli();
                if (islePath == null) {
                    Platform.runLater(() -> appendConsole("ERROR: Could not find isle CLI\n"));
                    return;
                }
                ProcessBuilder pb = new ProcessBuilder(islePath, "usb", "create", mount);
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
                Platform.runLater(() -> appendConsole(exitCode == 0
                    ? "\n=== USB installer created ===\n"
                    : "\n=== USB creation exited with code " + exitCode + " ===\n"));
            } catch (Exception e) {
                Platform.runLater(() -> appendConsole("ERROR: " + e.getMessage() + "\n"));
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onWipeIsland() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "This tears down the entire island on this machine: router VM, agents, "
            + "containers, networks, and agent config. This cannot be undone. Continue?",
            ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Wipe Island");
        confirm.setHeaderText("Wipe Island (complete teardown)");
        confirm.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.YES) runWipe();
        });
    }

    private void runWipe() {
        consoleSection.setVisible(true);
        consoleSection.setManaged(true);
        consoleOutput.setText("");

        Thread thread = new Thread(() -> {
            try {
                String islePath = IsleConfig.findIsleCli();
                if (islePath == null) {
                    Platform.runLater(() -> appendConsole("ERROR: Could not find isle CLI\n"));
                    return;
                }
                // destroy --purge --force: wipe the ENTIRE mesh footprint
                // (configs, DNS, services, networks, state) — keeps the CLI.
                ProcessBuilder pb = new ProcessBuilder("pkexec", islePath, "destroy", "--purge", "--force");
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
                    appendConsole(exitCode == 0
                        ? "\n=== Island wiped ===\n"
                        : "\n=== Wipe exited with code " + exitCode + " ===\n");
                    refreshStatus();
                });
            } catch (Exception e) {
                Platform.runLater(() -> appendConsole("ERROR: " + e.getMessage() + "\n"));
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onUninstallApp() {
        Alert confirm = new Alert(Alert.AlertType.WARNING,
            "This removes the Isle-Mesh desktop app AND the isle CLI tool from this "
            + "computer. It does NOT wipe a running mesh (use Wipe Island first) and "
            + "does NOT touch any development checkout. The app will close when done. Continue?",
            ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Uninstall App & CLI");
        confirm.setHeaderText("Uninstall App & CLI tool");
        confirm.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.YES) runUninstall();
        });
    }

    private void runUninstall() {
        consoleSection.setVisible(true);
        consoleSection.setManaged(true);
        consoleOutput.setText("");

        Thread thread = new Thread(() -> {
            try {
                String islePath = IsleConfig.findIsleCli();
                if (islePath == null) {
                    Platform.runLater(() -> appendConsole("ERROR: Could not find isle CLI\n"));
                    return;
                }
                // Removes the app + CLI (installed files only); leaves dev repo + mesh config.
                ProcessBuilder pb = new ProcessBuilder("pkexec", islePath, "uninstall", "--force");
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
                    appendConsole(exitCode == 0
                        ? "\n=== Uninstall complete — closing app ===\n"
                        : "\n=== Uninstall exited with code " + exitCode + " ===\n");
                    if (exitCode == 0) {
                        // The app's own files are now gone; exit cleanly.
                        javafx.application.Platform.exit();
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> appendConsole("ERROR: " + e.getMessage() + "\n"));
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private String findCreateScript(String islePath) {
        try {
            java.nio.file.Path isleRealPath = java.nio.file.Path.of(islePath).toRealPath();
            java.nio.file.Path candidate = isleRealPath.getParent().resolve("scripts/create.sh");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
            String home = System.getProperty("user.home");
            java.nio.file.Path projectCandidate = java.nio.file.Path.of(home, "Isle-Mesh/isle-cli/scripts/create.sh");
            if (Files.isRegularFile(projectCandidate)) {
                return projectCandidate.toString();
            }
        } catch (Exception e) {
            System.err.println("[HomePage] Could not resolve create.sh: " + e.getMessage());
        }
        return null;
    }
}
