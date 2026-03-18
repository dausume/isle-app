package com.islemesh.app.components.MainLayout;

import com.islemesh.app.ComponentLoader;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

public class MainLayoutController {

    @FXML
    private StackPane contentArea;

    @FXML
    private void initialize() {
        showPermissionsCheck();
    }

    private void showPermissionsCheck() {
        Node view = ComponentLoader.load(
            "/com/islemesh/app/components/PermissionsCheck/PermissionsCheck.fxml",
            ctrl -> {
                var c = (com.islemesh.app.components.PermissionsCheck.PermissionsCheckController) ctrl;
                c.init(this::showRoleSelect);
            }
        );
        setContent(view);
    }

    private void showRoleSelect() {
        Node view = ComponentLoader.load(
            "/com/islemesh/app/components/IsleRoleSelect/IsleRoleSelect.fxml",
            ctrl -> {
                var c = (com.islemesh.app.components.IsleRoleSelect.IsleRoleSelectController) ctrl;
                c.init(
                    () -> System.out.println("TODO: navigate to Isle Core setup"),
                    () -> System.out.println("TODO: navigate to Isle Connect flow")
                );
            }
        );
        setContent(view);
    }

    private void setContent(Node node) {
        contentArea.getChildren().setAll(node);
    }
}
