package org.islemesh.app.components.MainLayout;

import org.islemesh.app.ComponentLoader;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

public class MainLayoutController {

    @FXML
    private StackPane contentArea;

    @FXML
    private void initialize() {
        showRoleSelect();
    }

    private void showRoleSelect() {
        Node view = ComponentLoader.load(
            "/org/islemesh/app/components/IsleRoleSelect/IsleRoleSelect.fxml",
            ctrl -> {
                var c = (org.islemesh.app.components.IsleRoleSelect.IsleRoleSelectController) ctrl;
                c.init(
                    () -> showPermissionsCheck("core"),
                    () -> showPermissionsCheck("connect")
                );
            }
        );
        setContent(view);
    }

    private void showPermissionsCheck(String role) {
        Node view = ComponentLoader.load(
            "/org/islemesh/app/components/PermissionsCheck/PermissionsCheck.fxml",
            ctrl -> {
                var c = (org.islemesh.app.components.PermissionsCheck.PermissionsCheckController) ctrl;
                c.init(role, this::showRoleSelect);
            }
        );
        setContent(view);
    }

    private void setContent(Node node) {
        contentArea.getChildren().setAll(node);
    }
}
