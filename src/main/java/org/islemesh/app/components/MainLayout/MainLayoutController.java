package org.islemesh.app.components.MainLayout;

import org.islemesh.app.ComponentLoader;
import org.islemesh.app.IsleConfig;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

public class MainLayoutController {

    @FXML
    private StackPane contentArea;

    @FXML
    private void initialize() {
        detectAndRoute();
    }

    /**
     * Entry point: detect mode → check permissions → route to the right screen.
     */
    private void detectAndRoute() {
        String role = IsleConfig.readRole();
        System.out.println("[MainLayout] Detected role: " + role);

        if (role == null) {
            showRoleSelect();
            return;
        }

        if (IsleConfig.allPermissionsGranted(role)) {
            System.out.println("[MainLayout] All permissions OK — showing HomePage");
            showHomePage(role);
        } else {
            System.out.println("[MainLayout] Permissions missing — showing PermissionsCheck");
            showPermissionsCheck(role, false);
        }
    }

    private void showRoleSelect() {
        Node view = ComponentLoader.load(
            "/org/islemesh/app/components/IsleRoleSelect/IsleRoleSelect.fxml",
            ctrl -> {
                var c = (org.islemesh.app.components.IsleRoleSelect.IsleRoleSelectController) ctrl;
                c.init(
                    () -> onRoleChosen("core"),
                    () -> onRoleChosen("remote")
                );
            }
        );
        setContent(view);
    }

    private void onRoleChosen(String role) {
        if (IsleConfig.allPermissionsGranted(role)) {
            showHomePage(role);
        } else {
            showPermissionsCheck(role, false);
        }
    }

    /**
     * @param manageMode true when navigating from HomePage (show revoke options),
     *                   false during initial setup flow
     */
    private void showPermissionsCheck(String role, boolean manageMode) {
        Node view = ComponentLoader.load(
            "/org/islemesh/app/components/PermissionsCheck/PermissionsCheck.fxml",
            ctrl -> {
                var c = (org.islemesh.app.components.PermissionsCheck.PermissionsCheckController) ctrl;
                Runnable backAction = manageMode ? () -> showHomePage(role) : this::showRoleSelect;
                Runnable onAllGranted = manageMode ? null : () -> showHomePage(role);
                c.init(role, backAction, onAllGranted, manageMode);
            }
        );
        setContent(view);
    }

    private void showHomePage(String role) {
        Node view = ComponentLoader.load(
            "/org/islemesh/app/components/HomePage/HomePage.fxml",
            ctrl -> {
                var c = (org.islemesh.app.components.HomePage.HomePageController) ctrl;
                c.init(role, this::showRoleSelect,
                    () -> showPermissionsCheck(role, true),
                    () -> showAppsView(role),
                    () -> showDiagnostics(role),
                    () -> showSecurity(role),
                    () -> showDevices(role),
                    () -> showPorts(role));
            }
        );
        setContent(view);
    }

    private void showDiagnostics(String role) {
        Node view = ComponentLoader.load(
            "/org/islemesh/app/components/DiagnosticsView/DiagnosticsView.fxml",
            ctrl -> {
                var c = (org.islemesh.app.components.DiagnosticsView.DiagnosticsViewController) ctrl;
                c.init(() -> showHomePage(role));
            }
        );
        setContent(view);
    }

    private void showSecurity(String role) {
        Node view = ComponentLoader.load(
            "/org/islemesh/app/components/SecurityView/SecurityView.fxml",
            ctrl -> {
                var c = (org.islemesh.app.components.SecurityView.SecurityViewController) ctrl;
                c.init(() -> showHomePage(role));
            }
        );
        setContent(view);
    }

    private void showPorts(String role) {
        Node view = ComponentLoader.load(
            "/org/islemesh/app/components/PortsView/PortsView.fxml",
            ctrl -> {
                var c = (org.islemesh.app.components.PortsView.PortsViewController) ctrl;
                c.init(role, () -> showHomePage(role));
            }
        );
        setContent(view);
    }

    private void showDevices(String role) {
        Node view = ComponentLoader.load(
            "/org/islemesh/app/components/DevicesView/DevicesView.fxml",
            ctrl -> {
                var c = (org.islemesh.app.components.DevicesView.DevicesViewController) ctrl;
                c.init(role, () -> showHomePage(role));
            }
        );
        setContent(view);
    }

    private void showAppsView(String role) {
        Node view = ComponentLoader.load(
            "/org/islemesh/app/components/AppsView/AppsView.fxml",
            ctrl -> {
                var c = (org.islemesh.app.components.AppsView.AppsViewController) ctrl;
                c.init(role, () -> showHomePage(role));
            }
        );
        setContent(view);
    }

    private void setContent(Node node) {
        contentArea.getChildren().setAll(node);
    }
}
