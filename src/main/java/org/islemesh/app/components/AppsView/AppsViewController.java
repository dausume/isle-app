package org.islemesh.app.components.AppsView;

import org.islemesh.app.IsleConfig;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppsViewController {

    @FXML private Label modeBanner;
    @FXML private VBox appsList;
    @FXML private Label emptyLabel;

    private Runnable onBack;

    public void init(String role, Runnable onBack) {
        this.onBack = onBack;

        String displayName = "core".equals(role) ? "Isle Core" : "Isle Connect";
        modeBanner.setText(displayName);
        modeBanner.getStyleClass().add("core".equals(role) ? "banner-core" : "banner-connect");

        loadApps();
    }

    private void loadApps() {
        appsList.getChildren().clear();

        String json = runListApps();
        System.out.println("[AppsView] isle agent list-apps returned: " + json);

        List<AppInfo> apps = parseAppsJson(json);
        System.out.println("[AppsView] Parsed " + apps.size() + " app(s)");

        if (apps.isEmpty()) {
            emptyLabel.setVisible(true);
            emptyLabel.setManaged(true);
            return;
        }

        emptyLabel.setVisible(false);
        emptyLabel.setManaged(false);

        for (int i = 0; i < apps.size(); i++) {
            appsList.getChildren().add(buildAppCard(apps.get(i)));
            if (i < apps.size() - 1) {
                appsList.getChildren().add(new Separator());
            }
        }
    }

    private String runListApps() {
        try {
            String islePath = IsleConfig.findIsleCli();
            if (islePath == null) return "[]";

            ProcessBuilder pb = new ProcessBuilder(islePath, "agent", "list-apps");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder sb = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            process.waitFor();
            return sb.toString();
        } catch (Exception e) {
            System.err.println("[AppsView] list-apps failed: " + e.getMessage());
            return "[]";
        }
    }

    /**
     * Run an isle CLI action (e.g. agent unregister) and refresh the app list.
     * Mirrors runListApps(); blocking on the FX thread like loadApps() does.
     */
    private void runIsleAction(String actionLabel, String... args) {
        try {
            String islePath = IsleConfig.findIsleCli();
            if (islePath == null) return;

            List<String> cmd = new ArrayList<>();
            cmd.add(islePath);
            for (String a : args) cmd.add(a);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[AppsView][" + actionLabel + "] " + line);
                }
            }
            process.waitFor();
        } catch (Exception e) {
            System.err.println("[AppsView] " + actionLabel + " failed: " + e.getMessage());
        }
        loadApps();
    }

    /**
     * Minimal JSON array parser — extracts app objects from the isle agent list-apps output.
     * Avoids adding a JSON library dependency; uses regex on the known flat structure.
     */
    private List<AppInfo> parseAppsJson(String json) {
        List<AppInfo> apps = new ArrayList<>();
        if (json == null || json.equals("[]")) return apps;

        // Split on },{ to get individual app objects
        // First strip outer [ ]
        json = json.trim();
        if (json.startsWith("[")) json = json.substring(1);
        if (json.endsWith("]")) json = json.substring(0, json.length() - 1);

        // Split app objects — careful with nested arrays
        List<String> appBlocks = splitJsonObjects(json);

        for (String block : appBlocks) {
            AppInfo app = new AppInfo();
            app.name = extractString(block, "name");
            app.domain = extractString(block, "domain");
            app.updatedAt = extractString(block, "updated_at");
            app.modes = extractStringArray(block, "modes");
            app.services = extractServices(block);
            apps.add(app);
        }
        return apps;
    }

    private List<String> splitJsonObjects(String json) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private List<String> extractStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*?)\\]");
        Matcher m = p.matcher(json);
        if (m.find()) {
            String inner = m.group(1);
            Pattern sp = Pattern.compile("\"([^\"]+)\"");
            Matcher sm = sp.matcher(inner);
            while (sm.find()) {
                result.add(sm.group(1));
            }
        }
        return result;
    }

    private List<ServiceInfo> extractServices(String appJson) {
        List<ServiceInfo> services = new ArrayList<>();
        // Find the services array
        Pattern p = Pattern.compile("\"services\"\\s*:\\s*\\[(.*)\\]\\s*\\}\\s*$", Pattern.DOTALL);
        Matcher m = p.matcher(appJson);
        if (!m.find()) return services;

        String svcArray = m.group(1);
        List<String> svcBlocks = splitJsonObjects(svcArray);

        for (String block : svcBlocks) {
            ServiceInfo svc = new ServiceInfo();
            svc.name = extractString(block, "name");
            svc.subdomain = extractString(block, "subdomain");
            svc.container = extractString(block, "container");
            svc.protocol = extractString(block, "protocol");

            // port is a number, not a string
            Pattern pp = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
            Matcher pm = pp.matcher(block);
            svc.port = pm.find() ? pm.group(1) : "0";

            services.add(svc);
        }
        return services;
    }

    private VBox buildAppCard(AppInfo app) {
        VBox card = new VBox(6);
        card.getStyleClass().add("app-card");

        // Header: name + domain + open button
        HBox header = new HBox(10);
        header.getStyleClass().add("app-card-header");

        Label nameLabel = new Label(app.name);
        nameLabel.getStyleClass().add("app-name");

        Hyperlink domainLink = new Hyperlink(app.domain);
        domainLink.getStyleClass().add("app-domain-link");
        String appUrl = "https://" + app.domain;
        domainLink.setOnAction(e -> {
            System.out.println("[AppsView] Domain link clicked: " + appUrl);
            openUrl(appUrl);
        });

        Button openBtn = new Button("Open");
        openBtn.getStyleClass().addAll("btn-small", "btn-primary");
        openBtn.setOnAction(e -> {
            System.out.println("[AppsView] Open button clicked: " + appUrl);
            openUrl(appUrl);
        });

        // De-register: take the app off the mesh (name-addressable, works today).
        // Containers keep running; the app just leaves the isle registry/DNS.
        // NOTE: bring-up / bring-down / uninstall from the GUI await name-addressable
        // CLI verbs (isle app up|down|uninstall <name>) — those operate on a project
        // dir today, so they aren't GUI-drivable yet. Tracked in docs/REVAMP-PLAN.md.
        Button deregBtn = new Button("De-register");
        deregBtn.getStyleClass().addAll("btn-small", "btn-danger");
        deregBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "De-register '" + app.name + "' from the mesh? Containers keep running; "
                    + "the app leaves the isle (registry + .isle/.local).",
                ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText(null);
            confirm.showAndWait().filter(r -> r == ButtonType.OK).ifPresent(r ->
                runIsleAction("deregister", "agent", "unregister", "--name", app.name));
        });

        header.getChildren().addAll(nameLabel, domainLink, openBtn, deregBtn);
        card.getChildren().add(header);

        // Modes
        if (!app.modes.isEmpty()) {
            HBox modesRow = new HBox(6);
            modesRow.getStyleClass().add("app-detail-row");

            Label modesKey = new Label("Modes:");
            modesKey.getStyleClass().add("app-detail-key");
            modesRow.getChildren().add(modesKey);

            for (String mode : app.modes) {
                Label badge = new Label(mode);
                badge.getStyleClass().addAll("mode-badge",
                    "local".equals(mode) ? "mode-local" : "mode-isle");
                modesRow.getChildren().add(badge);
            }
            card.getChildren().add(modesRow);
        }

        // Services
        if (!app.services.isEmpty()) {
            Label servicesHeader = new Label("Services:");
            servicesHeader.getStyleClass().add("app-detail-key");
            card.getChildren().add(servicesHeader);

            for (ServiceInfo svc : app.services) {
                HBox svcRow = new HBox(8);
                svcRow.getStyleClass().add("app-service-row");

                Label svcName = new Label(svc.name);
                svcName.getStyleClass().add("service-name");

                Label svcDetail = new Label(svc.container + " :" + svc.port + " (" + svc.protocol + ")");
                svcDetail.getStyleClass().add("service-detail");

                svcRow.getChildren().addAll(svcName, svcDetail);

                // Subdomain link
                if (svc.subdomain != null && !svc.subdomain.isEmpty()) {
                    String fullSubdomain = svc.subdomain + "." + app.domain;
                    Hyperlink subLink = new Hyperlink(fullSubdomain);
                    subLink.getStyleClass().add("service-link");
                    subLink.setOnAction(e -> openUrl("https://" + fullSubdomain));
                    svcRow.getChildren().add(subLink);
                }

                card.getChildren().add(svcRow);
            }
        }

        // Updated at
        if (app.updatedAt != null && !app.updatedAt.isEmpty()) {
            Label updated = new Label("Updated: " + app.updatedAt);
            updated.getStyleClass().add("app-updated");
            card.getChildren().add(updated);
        }

        return card;
    }

    private void openUrl(String url) {
        System.out.println("[AppsView] Opening URL: " + url);
        try {
            ProcessBuilder pb = new ProcessBuilder("xdg-open", url);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // Read any output for debugging
            Thread reader = new Thread(() -> {
                try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        System.out.println("[xdg-open] " + line);
                    }
                } catch (Exception ignored) {}
            });
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            System.err.println("[AppsView] Failed to open URL: " + e.getMessage());
        }
    }

    @FXML
    private void onBack() {
        if (onBack != null) {
            onBack.run();
        }
    }

    @FXML
    private void onRefresh() {
        loadApps();
    }

    private static class AppInfo {
        String name = "";
        String domain = "";
        String updatedAt = "";
        List<String> modes = List.of();
        List<ServiceInfo> services = List.of();
    }

    private static class ServiceInfo {
        String name = "";
        String subdomain = "";
        String container = "";
        String port = "";
        String protocol = "";
    }
}
