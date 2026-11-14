module site.kpeclub.launcher {
    requires javafx.controls;
    requires transitive javafx.graphics;
    requires javafx.fxml;
    requires java.net.http;
    requires jdk.crypto.ec;
    requires com.google.gson;

    opens site.kpeclub.launcher to javafx.fxml;
    opens site.kpeclub.launcher.ui to javafx.fxml;
    opens site.kpeclub.launcher.model to com.google.gson;

    exports site.kpeclub.launcher;
}
