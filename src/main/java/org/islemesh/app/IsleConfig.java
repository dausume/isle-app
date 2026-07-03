package org.islemesh.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared utility for reading isle-mesh configuration state.
 * Reads from /etc/isle-mesh and isle CLI — no writes.
 */
public class IsleConfig {

    private static final Path MODE_FILE = Path.of("/etc/isle-mesh/agent/agent.mode");

    /**
     * Read the current role from agent.mode.
     * @return "core", "remote", or null if not set
     */
    public static String readRole() {
        try {
            if (Files.exists(MODE_FILE)) {
                String mode = Files.readString(MODE_FILE).trim();
                if (!mode.isEmpty()) {
                    return mode;
                }
            }
        } catch (Exception e) {
            // Not readable yet
        }
        return null;
    }

    /**
     * Write role to agent.mode.
     */
    public static void writeRole(String role) {
        try {
            Files.createDirectories(MODE_FILE.getParent());
            Files.writeString(MODE_FILE, role + "\n");
        } catch (Exception e) {
            System.out.println("[IsleConfig] Could not write agent.mode (permissions not yet granted)");
        }
    }

    /**
     * Map a stored role value to the check command role.
     * agent.mode stores "core" or "remote", but permissions check uses "core" or "connect".
     */
    public static String roleToCheckRole(String role) {
        return "remote".equals(role) ? "connect" : "core";
    }

    /**
     * Get the display name for a role.
     */
    public static String roleDisplayName(String role) {
        if ("core".equals(role)) return "Isle Core";
        if ("remote".equals(role)) return "Isle Connect";
        return "Unknown";
    }

    /**
     * Check permission status for the given role.
     * Returns map of group -> "yes"|"no"|"missing".
     * Tries isle CLI first, falls back to /etc/group.
     */
    public static Map<String, String> checkPermissions(String role) {
        Map<String, String> result = new LinkedHashMap<>();

        String checkRole = roleToCheckRole(role);

        // Try isle permissions check first
        if (runIsleCheck(checkRole, result)) {
            return result;
        }

        // Fallback: read /etc/group directly
        List<String> groups = "core".equals(role)
            ? List.of("isle-mesh", "docker", "libvirt", "kvm")
            : List.of("isle-mesh", "docker");

        for (String group : groups) {
            result.put(group, checkGroupInEtcGroup(group));
        }
        return result;
    }

    /**
     * Check if all required permissions are granted for the given role.
     */
    public static boolean allPermissionsGranted(String role) {
        Map<String, String> status = checkPermissions(role);
        return !status.isEmpty() && status.values().stream().allMatch("yes"::equals);
    }

    private static boolean runIsleCheck(String checkRole, Map<String, String> result) {
        try {
            String islePath = findIsleCli();
            if (islePath == null) return false;

            String username = System.getProperty("user.name");
            ProcessBuilder pb = new ProcessBuilder(islePath, "permissions", "check", checkRole, username);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        result.put(line.substring(0, eq), line.substring(eq + 1));
                    }
                }
            }

            process.waitFor();
            return !result.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static String checkGroupInEtcGroup(String group) {
        try {
            String username = System.getProperty("user.name");
            List<String> lines = Files.readAllLines(Path.of("/etc/group"));
            for (String line : lines) {
                String[] parts = line.split(":");
                if (parts.length >= 1 && parts[0].equals(group)) {
                    if (parts.length >= 4) {
                        for (String member : parts[3].split(",")) {
                            if (member.trim().equals(username)) {
                                return "yes";
                            }
                        }
                    }
                    return "no";
                }
            }
            return "missing";
        } catch (Exception e) {
            return "missing";
        }
    }

    /**
     * Run `isle status check` and return component statuses.
     * Returns map like: router=running, agent=stopped, mdns=running, etc.
     */
    public static Map<String, String> checkComponentStatus() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            String islePath = findIsleCli();
            if (islePath == null) return result;

            ProcessBuilder pb = new ProcessBuilder(islePath, "status", "check");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        result.put(line.substring(0, eq), line.substring(eq + 1));
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            System.err.println("[IsleConfig] status check failed: " + e.getMessage());
        }
        return result;
    }

    /**
     * Run an arbitrary `isle ...` command and return its stdout lines (stderr merged).
     * Returns an empty list if the CLI cannot be found or the call fails.
     */
    public static List<String> runIsle(String... args) {
        List<String> out = new java.util.ArrayList<>();
        try {
            String islePath = findIsleCli();
            if (islePath == null) return out;
            List<String> cmd = new java.util.ArrayList<>();
            cmd.add(islePath);
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.add(line);
            }
            p.waitFor();
        } catch (Exception e) {
            System.err.println("[IsleConfig] runIsle failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Non-destructive probe for an existing isle on this network segment
     * (`isle join --detect`, mDNS-based). Returns found=true/false, router, name.
     * The device never reaches into anything — it only learns whether to SUGGEST joining.
     */
    public static Map<String, String> detectIsle() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : runIsle("join", "--detect")) {
            line = line.trim();
            if (line.startsWith("isle ")) m.putAll(parseKvTokens(line.substring(5)));
        }
        return m;
    }

    /** List mounted removable/USB drives (`isle usb list`) → name/size/mount/label per drive. */
    public static List<Map<String, String>> listUsbDrives() {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (String line : runIsle("usb", "list")) {
            line = line.trim();
            if (line.startsWith("usb ")) out.add(parseKvTokens(line.substring(4)));
        }
        return out;
    }

    /** Parse "key=value key2=value2" tokens from a line into a map (whitespace-split). */
    public static Map<String, String> parseKvTokens(String line) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String tok : line.trim().split("\\s+")) {
            int eq = tok.indexOf('=');
            if (eq > 0) m.put(tok.substring(0, eq), tok.substring(eq + 1));
        }
        return m;
    }

    public static String findIsleCli() {
        try {
            Process p = new ProcessBuilder("which", "isle")
                .redirectErrorStream(true)
                .start();
            String path;
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                path = reader.readLine();
            }
            if (p.waitFor() == 0 && path != null && !path.isEmpty()) {
                return path.trim();
            }
        } catch (Exception e) {
            // Fall through
        }

        String[] candidates = { "/usr/local/bin/isle", "/usr/bin/isle" };
        for (String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }
}
