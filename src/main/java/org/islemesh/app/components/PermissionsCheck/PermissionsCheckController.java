package org.islemesh.app.components.PermissionsCheck;

import org.islemesh.app.IsleConfig;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Map;

public class PermissionsCheckController {

    @FXML
    private Label roleLabel;

    @FXML
    private VBox permissionsList;

    @FXML
    private Label statusLabel;

    @FXML
    private Button grantButton;

    @FXML
    private Button revokeAllButton;

    @FXML
    private Button backButton;

    private String role;
    private Runnable onBack;
    private Runnable onAllGranted;
    private boolean manageMode;

    private Map<String, String> permissionStatus;

    /**
     * @param role          "core" or "remote"
     * @param onBack        navigate back (to RoleSelect or HomePage)
     * @param onAllGranted  auto-navigate when all granted (null to stay on page)
     * @param manageMode    true = show revoke options, false = setup flow
     */
    public void init(String role, Runnable onBack, Runnable onAllGranted, boolean manageMode) {
        this.role = role;
        this.onBack = onBack;
        this.onAllGranted = onAllGranted;
        this.manageMode = manageMode;

        System.out.println("[PermissionsCheck] init role=" + role + " manageMode=" + manageMode);

        roleLabel.setText("Role: " + IsleConfig.roleDisplayName(role));

        if (manageMode) {
            backButton.setText("Back to Home");
        }

        checkPermissions();
    }

    private void checkPermissions() {
        System.out.println("[PermissionsCheck] Checking permissions for role: " + role);

        permissionStatus = IsleConfig.checkPermissions(role);

        for (var entry : permissionStatus.entrySet()) {
            System.out.println("[PermissionsCheck]   " + entry.getKey() + " = " + entry.getValue());
        }

        renderPermissionRows();

        boolean allGranted = permissionStatus.values().stream().allMatch("yes"::equals);
        boolean anyGranted = permissionStatus.values().stream().anyMatch("yes"::equals);

        // Grant button: show if anything is missing
        grantButton.setVisible(!allGranted);
        grantButton.setManaged(!allGranted);

        // Revoke all button: show in manage mode if any permissions are granted
        revokeAllButton.setVisible(manageMode && anyGranted);
        revokeAllButton.setManaged(manageMode && anyGranted);

        if (allGranted && !manageMode && onAllGranted != null) {
            System.out.println("[PermissionsCheck] All permissions granted — navigating forward");
            statusLabel.setText("All permissions granted.");
            statusLabel.setVisible(true);
            statusLabel.setManaged(true);
            onAllGranted.run();
        } else if (allGranted) {
            statusLabel.setText("All permissions granted.");
            statusLabel.setVisible(true);
            statusLabel.setManaged(true);
        } else {
            statusLabel.setVisible(false);
            statusLabel.setManaged(false);
        }
    }

    private void renderPermissionRows() {
        permissionsList.getChildren().clear();

        Map<String, String> descriptions = Map.of(
            "isle-mesh", "Agent configs (/etc/isle-mesh)",
            "docker", "Running containers",
            "libvirt", "Managing router VM",
            "kvm", "KVM hardware virtualization"
        );

        for (var entry : permissionStatus.entrySet()) {
            String group = entry.getKey();
            String status = entry.getValue();

            HBox row = new HBox(10);
            row.getStyleClass().add("permission-row");

            Label icon = new Label();
            icon.getStyleClass().add("permission-icon");

            switch (status) {
                case "yes":
                    icon.setText("[OK]");
                    icon.getStyleClass().add("permission-icon-ok");
                    break;
                case "missing":
                    icon.setText("[--]");
                    icon.getStyleClass().add("permission-icon-warn");
                    break;
                default:
                    icon.setText("[  ]");
                    icon.getStyleClass().add("permission-icon-fail");
                    break;
            }

            Label name = new Label(group);
            name.getStyleClass().add("permission-name");

            String descText = descriptions.getOrDefault(group, "");
            if ("missing".equals(status)) {
                descText += " (not installed)";
            }
            Label desc = new Label(descText);
            desc.getStyleClass().add("permission-desc");

            row.getChildren().addAll(icon, name, desc);

            // Per-group revoke button in manage mode
            if (manageMode && "yes".equals(status)) {
                Button revokeBtn = new Button("Revoke");
                revokeBtn.getStyleClass().addAll("btn-small", "btn-danger");
                revokeBtn.setOnAction(e -> revokeGroup(group));
                row.getChildren().add(revokeBtn);
            }

            permissionsList.getChildren().add(row);
        }
    }

    @FXML
    private void onGrantPermissions() {
        grantButton.setDisable(true);
        grantButton.setText("Granting...");

        Thread thread = new Thread(() -> {
            String subcommand = "core".equals(role) ? "setup-core" : "setup-connect";
            runPkexec("permissions", subcommand);

            Platform.runLater(() -> {
                grantButton.setText("Grant Permissions");
                grantButton.setDisable(false);
                checkPermissions();
            });
        });
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onRevokeAll() {
        revokeAllButton.setDisable(true);
        revokeAllButton.setText("Revoking...");

        Thread thread = new Thread(() -> {
            runPkexec("permissions", "revoke", "all");

            Platform.runLater(() -> {
                revokeAllButton.setText("Revoke All");
                revokeAllButton.setDisable(false);
                checkPermissions();
            });
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void revokeGroup(String group) {
        Thread thread = new Thread(() -> {
            runPkexec("permissions", "revoke", group);

            Platform.runLater(this::checkPermissions);
        });
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onBack() {
        if (onBack != null) {
            onBack.run();
        }
    }

    private boolean runPkexec(String... args) {
        try {
            String islePath = IsleConfig.findIsleCli();
            if (islePath == null) {
                System.err.println("[PermissionsCheck] Could not find isle CLI");
                return false;
            }

            String scriptPath = findPermissionsScript(islePath);

            ProcessBuilder pb;
            if (scriptPath != null && "permissions".equals(args[0])) {
                // Bypass Node.js wrapper — call bash script directly
                // cmd = [pkexec, bash, scriptPath, arg1, arg2, ...]
                int extraArgs = args.length - 1; // args after "permissions"
                String[] cmd = new String[3 + extraArgs];
                cmd[0] = "pkexec";
                cmd[1] = "bash";
                cmd[2] = scriptPath;
                System.arraycopy(args, 1, cmd, 3, extraArgs);
                System.out.println("[PermissionsCheck] Running: " + String.join(" ", cmd));
                pb = new ProcessBuilder(cmd);
            } else {
                String[] cmd = new String[args.length + 2];
                cmd[0] = "pkexec";
                cmd[1] = islePath;
                System.arraycopy(args, 0, cmd, 2, args.length);
                pb = new ProcessBuilder(cmd);
            }
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            System.out.println("[PermissionsCheck] pkexec exit code: " + exitCode);
            return exitCode == 0;
        } catch (Exception e) {
            System.err.println("[PermissionsCheck] pkexec failed: " + e.getMessage());
            return false;
        }
    }

    private String findPermissionsScript(String islePath) {
        try {
            java.nio.file.Path isleRealPath = java.nio.file.Path.of(islePath).toRealPath();
            java.nio.file.Path candidate = isleRealPath.getParent().resolve("scripts/permissions.sh");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
            String home = System.getProperty("user.home");
            java.nio.file.Path projectCandidate = java.nio.file.Path.of(home, "Isle-Mesh/isle-cli/scripts/permissions.sh");
            if (Files.isRegularFile(projectCandidate)) {
                return projectCandidate.toString();
            }
        } catch (Exception e) {
            System.err.println("[PermissionsCheck] Could not resolve permissions.sh: " + e.getMessage());
        }
        return null;
    }
}
