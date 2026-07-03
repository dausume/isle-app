package org.islemesh.app.components.PortsView;

import org.islemesh.app.IsleConfig;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Network Ports" — see wired ethernet cables and switch them onto/off the isle,
 * with no CLI use. Backed by `isle ports list|attach|detach`. The internet/normal-
 * network uplink is shown but locked (bridging it would break isolation).
 */
public class PortsViewController {

    @FXML private Label bridgeNote;
    @FXML private VBox portList;
    @FXML private Label statusLabel;

    private Runnable onBack;
    private boolean busy = false;

    public void init(String role, Runnable onBack) {
        this.onBack = onBack;
        refresh();
    }

    private void refresh() {
        portList.getChildren().clear();
        statusLabel.setText("");

        List<Map<String, String>> ports = new ArrayList<>();
        String isleBridge = "none";
        for (String line : IsleConfig.runIsle("ports", "list")) {
            line = line.trim();
            if (line.startsWith("port ")) {
                ports.add(IsleConfig.parseKvTokens(line.substring("port ".length())));
            } else if (line.startsWith("summary")) {
                isleBridge = IsleConfig.parseKvTokens(line.substring("summary".length()))
                        .getOrDefault("isle_bridge", "none");
            }
        }

        final boolean isleUp = !"none".equals(isleBridge);
        if (isleUp) {
            bridgeNote.setText("Isle is up. Flip a cable onto it to put a device on the mesh.");
            bridgeNote.setStyle("-fx-text-fill: #27ae60;");
        } else {
            bridgeNote.setText("The isle isn't running yet — start it (Isle Create) before adding a cable.");
            bridgeNote.setStyle("-fx-text-fill: #e67e22;");
        }

        if (ports.isEmpty()) {
            Label empty = new Label("No wired ethernet ports found.");
            empty.getStyleClass().add("status-text");
            portList.getChildren().add(empty);
            return;
        }
        for (Map<String, String> p : ports) portList.getChildren().add(portCard(p, isleUp));
    }

    private VBox portCard(Map<String, String> p, boolean isleUp) {
        String name = p.getOrDefault("name", "?");
        boolean cable = "1".equals(p.getOrDefault("carrier", "0"));
        boolean onIsle = "true".equals(p.getOrDefault("on_isle", "false"));
        boolean uplink = "true".equals(p.getOrDefault("uplink", "false"));

        VBox card = new VBox(6);
        card.getStyleClass().add("status-card");
        card.setPadding(new Insets(10, 12, 10, 12));

        HBox top = new HBox(10);
        Label title = new Label(name);
        title.getStyleClass().add("permission-name");
        Label state = badge(
            uplink ? "Internet uplink" : (onIsle ? "On isle" : (cable ? "Available" : "No cable")),
            uplink ? "#7f8c8d" : (onIsle ? "#27ae60" : (cable ? "#2980b9" : "#95a5a6")));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        top.getChildren().addAll(title, state, spacer);

        Label sub = new Label(cable ? "Cable connected" : "No cable detected");
        sub.getStyleClass().add("permission-desc");
        card.getChildren().addAll(top, sub);

        HBox actions = new HBox(10);
        actions.setPadding(new Insets(4, 0, 0, 0));
        if (uplink) {
            Label locked = new Label("Protected — this carries your normal internet; it can't be put on the isle.");
            locked.getStyleClass().add("permission-desc");
            locked.setWrapText(true);
            actions.getChildren().add(locked);
        } else if (onIsle) {
            Button off = new Button("Remove from isle");
            off.getStyleClass().addAll("btn", "btn-secondary", "btn-small");
            off.setOnAction(e -> togglePort("detach", name));
            actions.getChildren().add(off);
        } else {
            Button on = new Button("Add to isle");
            on.getStyleClass().addAll("btn", "btn-primary", "btn-small");
            on.setDisable(!isleUp);
            on.setOnAction(e -> togglePort("attach", name));
            actions.getChildren().add(on);
            if (!isleUp) {
                Label hint = new Label("(start the isle first)");
                hint.getStyleClass().add("permission-desc");
                actions.getChildren().add(hint);
            }
        }
        card.getChildren().add(actions);
        return card;
    }

    private Label badge(String text, String color) {
        Label b = new Label(text);
        b.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; "
                + "-fx-padding: 2 8 2 8; -fx-background-radius: 10; -fx-font-size: 11;");
        return b;
    }

    private void togglePort(String action, String iface) {
        if (busy) return;
        busy = true;
        statusLabel.setText(("attach".equals(action) ? "Adding " : "Removing ") + iface + "…");

        Thread t = new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try {
                String islePath = IsleConfig.findIsleCli();
                if (islePath == null) {
                    Platform.runLater(() -> { statusLabel.setText("Error: isle CLI not found"); busy = false; });
                    return;
                }
                // ip link changes need root → pkexec (graphical password prompt).
                ProcessBuilder pb = new ProcessBuilder("pkexec", islePath, "ports", action, iface);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) out.append(line).append("\n");
                }
                int code = p.waitFor();
                final String msg = out.toString().trim();
                Platform.runLater(() -> {
                    statusLabel.setText(code == 0
                        ? (msg.isEmpty() ? "Done." : msg)
                        : "Could not " + action + " " + iface + ": " + msg);
                    busy = false;
                    refresh();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> { statusLabel.setText("Error: " + ex.getMessage()); busy = false; });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @FXML private void onRefresh() { refresh(); }
    @FXML private void onBack() { if (onBack != null) onBack.run(); }
}
