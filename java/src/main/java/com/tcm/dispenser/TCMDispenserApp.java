package com.tcm.dispenser;

import com.tcm.dispenser.view.MainPanel;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class TCMDispenserApp extends Application {

    private MainPanel mainPanel;

    @Override
    public void start(Stage primaryStage) {
        mainPanel = new MainPanel(primaryStage);

        Scene scene = new Scene(mainPanel, 1280, 800);
        primaryStage.setTitle("智能中药配方颗粒调剂机控制系统");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1024);
        primaryStage.setMinHeight(680);
        primaryStage.setOnCloseRequest(event -> {
            mainPanel.shutdown();
        });
        primaryStage.show();
    }

    @Override
    public void stop() throws Exception {
        if (mainPanel != null) {
            mainPanel.shutdown();
        }
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
