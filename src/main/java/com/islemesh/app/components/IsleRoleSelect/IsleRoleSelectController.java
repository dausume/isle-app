package com.islemesh.app.components.IsleRoleSelect;

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
    }

    @FXML
    private void onSetupCore() {
        System.out.println("Set up as Isle Core selected");
        if (onCoreSelected != null) {
            onCoreSelected.run();
        }
    }

    @FXML
    private void onConnectToIsle() {
        System.out.println("Connect to Isle selected");
        if (onConnectSelected != null) {
            onConnectSelected.run();
        }
    }
}
