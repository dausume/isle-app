package com.islemesh.app;

import javafx.fxml.FXMLLoader;
import java.util.function.Consumer;

public class ComponentLoader {

    public static <T> T load(String fxml, Consumer<Object> controllerConsumer) {
        try {
            FXMLLoader loader = new FXMLLoader(
                ComponentLoader.class.getResource(fxml)
            );
            T node = loader.load();
            controllerConsumer.accept(loader.getController());
            return node;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load component: " + fxml, e);
        }
    }

    public static <T> T load(String fxml) {
        return load(fxml, controller -> {});
    }
}
