package org.islemesh.app.components.IsleRoleSelect;

import javafx.fxml.FXML;
import javafx.scene.control.Button;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class IsleRoleSelectController {

    private static final Path MODE_FILE = Path.of("/etc/isle-mesh/agent/agent.mode");

    @FXML
    private Button coreButton;

    @FXML
    private Button connectButton;

    private Runnable onCoreSelected;
    private Runnable onConnectSelected;

    public void init(Runnable onCoreSelected, Runnable onConnectSelected) {
        this.onCoreSelected = onCoreSelected;
        this.onConnectSelected = onConnectSelected;

        // Highlight current role if already set
        String currentRole = readCurrentRole();
        if ("core".equals(currentRole)) {
            coreButton.getStyleClass().add("btn-active");
        } else if ("remote".equals(currentRole)) {
            connectButton.getStyleClass().add("btn-active");
        }
    }

    @FXML
    private void onSetupCore() {
        writeRole("core");
        if (onCoreSelected != null) {
            onCoreSelected.run();
        }
    }

    @FXML
    private void onConnectToIsle() {
        writeRole("remote");
        if (onConnectSelected != null) {
            onConnectSelected.run();
        }
    }

    private String readCurrentRole() {
        try {
            if (Files.exists(MODE_FILE)) {
                return Files.readString(MODE_FILE).trim();
            }
        } catch (IOException e) {
            // File may not be readable without permissions yet
        }
        return null;
    }

    private void writeRole(String role) {
        try {
            Files.createDirectories(MODE_FILE.getParent());
            Files.writeString(MODE_FILE, role + "\n");
        } catch (IOException e) {
            // Will fail without permissions — that's OK, permissions step handles it
            System.out.println("Note: Could not write agent.mode (permissions not yet granted)");
        }
    }
}
