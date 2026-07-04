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
import java.util.LinkedHashMap;
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

    /**
     * Two data sources, merged by app name:
     *   - `isle agent list-apps`  -> apps currently ON the mesh (running/registered).
     *   - `isle app installed`    -> apps whose .deb is installed on this node (may be down).
     * An app installed via a .deb carries a `pkg` (its isle-app-<pkg> lifecycle wrapper);
     * that's what lets the GUI bring it up/down. Running-but-not-.deb apps have no pkg and
     * only offer Open + De-register.
     */
    private void loadApps() {
        appsList.getChildren().clear();

        List<AppInfo> running = parseAppsJson(runListApps());
        for (AppInfo a : running) a.running = true;

        List<AppInfo> installed = parseInstalledJson(runInstalledApps());

        LinkedHashMap<String, AppInfo> byName = new LinkedHashMap<>();
        for (AppInfo a : running) byName.put(a.name, a);
        for (AppInfo ins : installed) {
            AppInfo r = byName.get(ins.name);
            if (r != null) {
                // running AND installed via .deb -> expose its wrapper so we can Bring Down
                if (r.pkg.isEmpty()) r.pkg = ins.pkg;
            } else {
                byName.put(ins.name, ins); // installed but down
            }
        }
        List<AppInfo> apps = new ArrayList<>(byName.values());
        System.out.println("[AppsView] " + running.size() + " running, " + installed.size()
            + " installed, " + apps.size() + " total");

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
        return runCliCapture("[]", "agent", "list-apps");
    }

    private String runInstalledApps() {
        String out = runCliCapture("[]", "app", "installed", "--json");
        // `installed` prints a human line when there are none; keep only the JSON array.
        int a = out.indexOf('['), b = out.lastIndexOf(']');
        return (a >= 0 && b >= a) ? out.substring(a, b + 1) : "[]";
    }

    /** Run an isle CLI command and return its stdout, or `fallback` on any failure. */
    private String runCliCapture(String fallback, String... args) {
        try {
            String islePath = IsleConfig.findIsleCli();
            if (islePath == null) return fallback;

            List<String> cmd = new ArrayList<>();
            cmd.add(islePath);
            for (String a : args) cmd.add(a);

            ProcessBuilder pb = new ProcessBuilder(cmd);
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
            System.err.println("[AppsView] cli capture failed: " + e.getMessage());
            return fallback;
        }
    }

    /**
     * Run an isle CLI action (e.g. agent unregister) and refresh the app list.
     * Mirrors runListApps(); blocking on the FX thread like loadApps() does. Fine for
     * fast actions (unregister); slow lifecycle actions use runRaw() off-thread instead.
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
     * Run an arbitrary command (a per-app lifecycle wrapper `isle-app-<pkg> up|down`, or
     * `pkexec dpkg -r <pkg>`) OFF the FX thread — these can take minutes (image build /
     * polkit prompt) and must not freeze the UI. Refresh the list on the FX thread when done.
     */
    private void runRaw(String label, String... command) {
        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[AppsView][" + label + "] " + line);
                    }
                }
                process.waitFor();
            } catch (Exception e) {
                System.err.println("[AppsView] " + label + " failed: " + e.getMessage());
            }
            javafx.application.Platform.runLater(this::loadApps);
        });
        t.setDaemon(true);
        t.start();
    }

    private void confirmThen(String message, Runnable action) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.showAndWait().filter(r -> r == ButtonType.OK).ifPresent(r -> action.run());
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

    /**
     * Parse `isle app installed --json` — a flat array of
     * {name,domain,port,protocol,pkg}. These are installed-but-(maybe)-down apps.
     */
    private List<AppInfo> parseInstalledJson(String json) {
        List<AppInfo> apps = new ArrayList<>();
        if (json == null) return apps;
        json = json.trim();
        if (json.equals("[]") || json.isEmpty()) return apps;
        if (json.startsWith("[")) json = json.substring(1);
        if (json.endsWith("]")) json = json.substring(0, json.length() - 1);

        for (String block : splitJsonObjects(json)) {
            AppInfo app = new AppInfo();
            app.name = extractString(block, "name");
            app.domain = extractString(block, "domain");
            app.protocol = extractString(block, "protocol");
            app.pkg = extractString(block, "pkg");
            // port may be quoted or bare
            Pattern pp = Pattern.compile("\"port\"\\s*:\\s*\"?(\\d+)\"?");
            Matcher pm = pp.matcher(block);
            app.port = pm.find() ? pm.group(1) : "";
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

        // Header: name + status + state-aware actions
        HBox header = new HBox(10);
        header.getStyleClass().add("app-card-header");

        Label nameLabel = new Label(app.name);
        nameLabel.getStyleClass().add("app-name");

        Label statusBadge = new Label(app.running ? "running" : "installed");
        statusBadge.getStyleClass().addAll("mode-badge", app.running ? "mode-isle" : "mode-local");

        header.getChildren().addAll(nameLabel, statusBadge);

        if (app.running) {
            String appUrl = "https://" + app.domain;

            Hyperlink domainLink = new Hyperlink(app.domain);
            domainLink.getStyleClass().add("app-domain-link");
            domainLink.setOnAction(e -> openUrl(appUrl));

            Button openBtn = new Button("Open");
            openBtn.getStyleClass().addAll("btn-small", "btn-primary");
            openBtn.setOnAction(e -> openUrl(appUrl));

            header.getChildren().addAll(domainLink, openBtn);

            // Bring Down: only when we know the app's lifecycle wrapper (installed via .deb).
            // Stops containers + de-registers, leaving the app installed (down).
            if (!app.pkg.isEmpty()) {
                Button downBtn = new Button("Bring Down");
                downBtn.getStyleClass().addAll("btn-small", "btn-secondary");
                downBtn.setOnAction(e -> confirmThen(
                    "Bring '" + app.name + "' down? Containers stop and it leaves the mesh "
                        + "(stays installed).",
                    () -> runRaw("down", app.pkg, "down")));
                header.getChildren().add(downBtn);
            }

            // De-register: take the app off the mesh without stopping containers.
            Button deregBtn = new Button("De-register");
            deregBtn.getStyleClass().addAll("btn-small", "btn-danger");
            deregBtn.setOnAction(e -> confirmThen(
                "De-register '" + app.name + "' from the mesh? Containers keep running; "
                    + "the app leaves the isle (registry + .isle/.local).",
                () -> runIsleAction("deregister", "agent", "unregister", "--name", app.name)));
            header.getChildren().add(deregBtn);

        } else {
            // Installed but down: offer Bring Up + Uninstall.
            Label domainLabel = new Label(app.domain);
            domainLabel.getStyleClass().add("app-domain-link");
            header.getChildren().add(domainLabel);

            if (!app.pkg.isEmpty()) {
                Button upBtn = new Button("Bring Up");
                upBtn.getStyleClass().addAll("btn-small", "btn-primary");
                upBtn.setOnAction(e -> runRaw("up", app.pkg, "up"));

                Button uninstallBtn = new Button("Uninstall");
                uninstallBtn.getStyleClass().addAll("btn-small", "btn-danger");
                uninstallBtn.setOnAction(e -> confirmThen(
                    "Uninstall '" + app.name + "' entirely? Removes the app package from "
                        + "this node (brings it down + de-registers first).",
                    () -> runRaw("uninstall", "pkexec", "dpkg", "-r", app.pkg)));

                header.getChildren().addAll(upBtn, uninstallBtn);
            }
        }

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

        // Services (running apps expose these via list-apps)
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
        } else if (!app.running && !app.port.isEmpty()) {
            // Installed-down apps have no live services; show the recorded endpoint.
            Label detail = new Label("Serves :" + app.port + " (" + app.protocol + ") when up");
            detail.getStyleClass().add("service-detail");
            card.getChildren().add(detail);
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
        // lifecycle state (installed-app layer)
        boolean running = false;
        String pkg = "";        // isle-app-<pkg> wrapper command, if installed via .deb
        String port = "";       // recorded endpoint for installed-down apps
        String protocol = "";
    }

    private static class ServiceInfo {
        String name = "";
        String subdomain = "";
        String container = "";
        String port = "";
        String protocol = "";
    }
}
