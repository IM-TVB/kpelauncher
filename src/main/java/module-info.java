module site.kpeclub.launcher {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http;
    requires java.desktop;
    requires jdk.httpserver;
    requires com.google.gson;

    opens site.kpeclub.launcher to javafx.fxml;
    opens site.kpeclub.launcher.ui to javafx.fxml;
    opens site.kpeclub.launcher.model to com.google.gson;

    exports site.kpeclub.launcher;
}
