package com.islemesh.app;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.Parent;
import javafx.stage.Stage;

public class IsleApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        Parent root = ComponentLoader.load(
            "/com/islemesh/app/components/MainLayout/MainLayout.fxml"
        );

        Scene scene = new Scene(root, 400, 320);
        scene.getStylesheets().add(
            getClass().getResource("/com/islemesh/app/styles/global.css").toExternalForm()
        );

        primaryStage.setTitle("Isle Mesh");
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
