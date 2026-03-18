package com.islemesh.app.components.PermissionsCheck;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

public class PermissionsCheckController {

    @FXML
    private Label statusLabel;

    @FXML
    private Button grantButton;

    @FXML
    private Button retryButton;

    private Runnable onPermissionsGranted;

    public void init(Runnable onPermissionsGranted) {
        this.onPermissionsGranted = onPermissionsGranted;
        checkPermissions();
    }

    private void checkPermissions() {
        // TODO: Use polkit to check if we have the necessary privileges
        //   e.g. org.freedesktop.policykit.exec for managing VMs, bridges, etc.
        boolean hasPermissions = false; // Stubbed — always false for now

        if (hasPermissions) {
            onPermissionsGranted.run();
        } else {
            statusLabel.setText("Isle Mesh requires elevated permissions to manage networks and virtual machines.");
            grantButton.setVisible(true);
            retryButton.setVisible(true);
        }
    }

    @FXML
    private void onGrantPermissions() {
        // TODO: Trigger polkit authentication agent
        //   e.g. pkexec or DBus call to org.freedesktop.PolicyKit1
        System.out.println("Grant permissions clicked — would trigger polkit prompt");

        // Simulate success for now
        statusLabel.setText("Permissions granted.");
        grantButton.setVisible(false);
        retryButton.setVisible(false);

        if (onPermissionsGranted != null) {
            onPermissionsGranted.run();
        }
    }

    @FXML
    private void onRetry() {
        statusLabel.setText("Checking permissions...");
        grantButton.setVisible(false);
        retryButton.setVisible(false);
        checkPermissions();
    }
}
