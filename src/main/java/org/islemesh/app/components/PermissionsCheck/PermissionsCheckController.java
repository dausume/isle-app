package org.islemesh.app.components.PermissionsCheck;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
    private Button backButton;

    private String role; // "core" or "connect"
    private Runnable onBack;

    // group -> "yes" | "no" | "missing"
    private final Map<String, String> permissionStatus = new LinkedHashMap<>();

    public void init(String role, Runnable onBack) {
        this.role = role;
        this.onBack = onBack;

        System.out.println("[PermissionsCheck] init called with role: " + role);
        System.out.println("[PermissionsCheck] permissionsList is null? " + (permissionsList == null));
        System.out.println("[PermissionsCheck] grantButton is null? " + (grantButton == null));

        roleLabel.setText("Role: " + ("core".equals(role) ? "Isle Core" : "Isle Connect"));

        checkPermissions();
    }

    private void checkPermissions() {
        permissionStatus.clear();

        System.out.println("[PermissionsCheck] Checking permissions for role: " + role);

        // Try isle permissions check first (authoritative, reads /etc/group)
        String checkRole = "core".equals(role) ? "core" : "connect";
        boolean gotResult = runIsleCheck(checkRole);

        // Fallback: check /etc/group directly
        if (!gotResult) {
            System.out.println("[PermissionsCheck] isle CLI not found, falling back to /etc/group");
            List<String> groups = "core".equals(role)
                ? List.of("isle-mesh", "docker", "libvirt", "kvm")
                : List.of("isle-mesh", "docker");

            for (String group : groups) {
                permissionStatus.put(group, checkGroupInEtcGroup(group));
            }
        }

        // Log results
        for (var entry : permissionStatus.entrySet()) {
            System.out.println("[PermissionsCheck]   " + entry.getKey() + " = " + entry.getValue());
        }

        renderPermissionRows();

        boolean allGranted = permissionStatus.values().stream().allMatch("yes"::equals);
        grantButton.setVisible(!allGranted);
        grantButton.setManaged(!allGranted);

        if (allGranted) {
            System.out.println("[PermissionsCheck] All permissions granted!");
            statusLabel.setText("All permissions granted. You may need to log out and back in for group changes to take effect.");
            statusLabel.setVisible(true);
            statusLabel.setManaged(true);
        } else {
            System.out.println("[PermissionsCheck] Some permissions missing — showing grant button");
            statusLabel.setVisible(false);
            statusLabel.setManaged(false);
        }
    }

    /**
     * Run `isle permissions check <role>` and parse output lines like:
     *   isle-mesh=yes
     *   docker=no
     *   libvirt=missing
     */
    private boolean runIsleCheck(String checkRole) {
        try {
            String islePath = findIsleCli();
            if (islePath == null) return false;

            String username = System.getProperty("user.name");
            ProcessBuilder pb = new ProcessBuilder(islePath, "permissions", "check", checkRole, username);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String group = line.substring(0, eq);
                        String status = line.substring(eq + 1);
                        permissionStatus.put(group, status);
                    }
                }
            }

            process.waitFor();
            return !permissionStatus.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fallback: read /etc/group to check if user is in a group.
     * This reflects system state, not just the current session.
     */
    private String checkGroupInEtcGroup(String group) {
        try {
            String username = System.getProperty("user.name");
            List<String> lines = Files.readAllLines(Path.of("/etc/group"));
            for (String line : lines) {
                // Format: group:x:gid:user1,user2,...
                String[] parts = line.split(":");
                if (parts.length >= 1 && parts[0].equals(group)) {
                    if (parts.length >= 4) {
                        for (String member : parts[3].split(",")) {
                            if (member.trim().equals(username)) {
                                return "yes";
                            }
                        }
                    }
                    return "no"; // group exists but user not in it
                }
            }
            return "missing"; // group doesn't exist
        } catch (Exception e) {
            return "missing";
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
                default: // "no"
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
            permissionsList.getChildren().add(row);
        }
    }

    @FXML
    private void onGrantPermissions() {
        grantButton.setDisable(true);
        grantButton.setText("Granting...");

        Thread thread = new Thread(() -> {
            String subcommand = "core".equals(role) ? "setup-core" : "setup-connect";
            boolean success = runPkexec(subcommand);

            Platform.runLater(() -> {
                grantButton.setText("Grant Permissions");
                grantButton.setDisable(false);
                // Re-check after grant attempt regardless of success
                checkPermissions();
            });
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

    private boolean runPkexec(String subcommand) {
        try {
            String islePath = findIsleCli();
            if (islePath == null) {
                System.err.println("[PermissionsCheck] Could not find isle CLI");
                return false;
            }

            System.out.println("[PermissionsCheck] Running: pkexec " + islePath + " permissions " + subcommand);

            // pkexec doesn't preserve PATH/env, so call the permissions script directly
            // via bash instead of going through the node.js isle CLI
            String scriptPath = findPermissionsScript(islePath);

            ProcessBuilder pb;
            if (scriptPath != null) {
                System.out.println("[PermissionsCheck] Using direct script: " + scriptPath);
                pb = new ProcessBuilder("pkexec", "bash", scriptPath, subcommand);
            } else {
                pb = new ProcessBuilder("pkexec", islePath, "permissions", subcommand);
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

    /**
     * Resolve the permissions.sh script path from the isle CLI location.
     * This lets us bypass the Node.js wrapper when running via pkexec.
     */
    private String findPermissionsScript(String islePath) {
        try {
            // isle CLI is typically at <project>/isle-cli/index.js or a symlink to it
            // permissions.sh is at <project>/isle-cli/scripts/permissions.sh
            java.nio.file.Path isleRealPath = java.nio.file.Path.of(islePath).toRealPath();
            // Try relative to isle-cli
            java.nio.file.Path candidate = isleRealPath.getParent().resolve("scripts/permissions.sh");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
            // If isle is a wrapper, try common project locations
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

    private String findIsleCli() {
        // Try 'which' first
        try {
            Process p = new ProcessBuilder("which", "isle")
                .redirectErrorStream(true)
                .start();
            String path;
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                path = reader.readLine();
            }
            if (p.waitFor() == 0 && path != null && !path.isEmpty()) {
                return path.trim();
            }
        } catch (Exception e) {
            // Fall through
        }

        // Check common locations
        String[] candidates = {
            "/usr/local/bin/isle",
            "/usr/bin/isle"
        };
        for (String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }
}
