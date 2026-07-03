package org.islemesh.app.components.IsleRoleSelect;

import org.islemesh.app.IsleConfig;
import javafx.fxml.FXML;
import javafx.scene.control.Button;

public class IsleRoleSelectController {

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
        String currentRole = IsleConfig.readRole();
        if ("core".equals(currentRole)) {
            coreButton.getStyleClass().add("btn-active");
        } else if ("remote".equals(currentRole)) {
            connectButton.getStyleClass().add("btn-active");
        }
    }

    @FXML
    private void onSetupCore() {
        IsleConfig.writeRole("core");
        if (onCoreSelected != null) {
            onCoreSelected.run();
        }
    }

    @FXML
    private void onConnectToIsle() {
        IsleConfig.writeRole("remote");
        if (onConnectSelected != null) {
            onConnectSelected.run();
        }
    }
}
