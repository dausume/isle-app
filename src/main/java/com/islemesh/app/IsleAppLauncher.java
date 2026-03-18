package com.islemesh.app;

/**
 * Non-JavaFX launcher class needed for fat JAR packaging.
 * JavaFX Application subclasses cannot be the main class in a
 * shaded/fat JAR without the JavaFX runtime on the module path.
 */
public class IsleAppLauncher {
    public static void main(String[] args) {
        IsleApp.main(args);
    }
}
