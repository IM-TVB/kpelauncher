package site.kpeclub.launcher;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import site.kpeclub.launcher.util.ThemeManager;

public class Main extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        Scene scene = new Scene(loader.load(), 1280, 720);
        ThemeManager.apply(scene);

        try {
            var iconUrl = getClass().getResource("/images/icon.jpg");
            if (iconUrl != null) {
                stage.getIcons().add(new Image(iconUrl.toExternalForm()));
            }
        } catch (Exception e) {
            // A missing/bad icon should never prevent the launcher from starting.
            System.err.println("Could not load window icon: " + e.getMessage());
        }

        stage.setTitle("KPE Club Launcher v1.8.1");
        stage.setScene(scene);
        stage.setMinWidth(860);
        stage.setMinHeight(540);
        stage.show();
    }

    public static void main(String[] args) {
        if (System.getProperty("java.net.preferIPv4Stack") == null) {
            System.setProperty("java.net.preferIPv4Stack", "true");
        }
        launch(args);
    }
}
