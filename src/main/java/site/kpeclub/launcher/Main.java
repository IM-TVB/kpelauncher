package site.kpeclub.launcher;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import site.kpeclub.launcher.model.LauncherSettings;
import site.kpeclub.launcher.util.LauncherConfig;
import site.kpeclub.launcher.util.TermsOfService;
import site.kpeclub.launcher.util.ThemeManager;

public class Main extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        if (!ensureTermsAccepted()) {
            return; // person declined — app exits, main window never shows
        }

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

        stage.setTitle("KPE Club Launcher");
        stage.setScene(scene);
        stage.setMinWidth(860);
        stage.setMinHeight(540);
        stage.show();
    }

    /**
     * Blocks until the person accepts the Terms of Service, only prompting if they haven't
     * already accepted the current version (LauncherSettings.acceptedTermsVersion tracks this,
     * so bumping TermsOfService.VERSION re-prompts everyone even if they accepted before).
     *
     * @return true if terms are accepted (already, or just now) and startup should continue;
     *         false if the person declined and the app should exit.
     */
    private boolean ensureTermsAccepted() {
        LauncherSettings settings = LauncherConfig.loadSettings();
        if (settings.getAcceptedTermsVersion() >= TermsOfService.VERSION) {
            return true; // already accepted the current version, nothing to show
        }

        TextArea termsText = new TextArea(TermsOfService.TEXT);
        termsText.setEditable(false);
        termsText.setWrapText(true);
        termsText.setPrefSize(560, 360);
        termsText.setStyle(
                "-fx-control-inner-background: #1a1a1a; -fx-text-fill: #d0d0d0; " +
                "-fx-font-family: 'Consolas'; -fx-font-size: 12px;");

        CheckBox agreeCheckBox = new CheckBox("I have read and agree to the Terms of Service");
        agreeCheckBox.setStyle("-fx-text-fill: #d0d0d0;");

        VBox root = new VBox(12, termsText, agreeCheckBox);
        root.setStyle("-fx-background-color: #161616; -fx-padding: 16;");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("KPE Club Launcher — Terms of Service");
        dialog.setHeaderText("Please read and accept the terms before continuing.");
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().setStyle("-fx-background-color: #161616;");

        ButtonType acceptButton = new ButtonType("I Accept", ButtonBar.ButtonData.OK_DONE);
        ButtonType declineButton = new ButtonType("Decline (Exit)", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(acceptButton, declineButton);

        // Accept stays disabled until the checkbox is checked — the person must actively
        // confirm, clicking through without reading isn't enough.
        Node acceptButtonNode = dialog.getDialogPane().lookupButton(acceptButton);
        acceptButtonNode.setDisable(true);
        agreeCheckBox.selectedProperty().addListener((obs, wasSelected, isSelected) ->
                acceptButtonNode.setDisable(!isSelected));

        var result = dialog.showAndWait();
        boolean accepted = result.isPresent() && result.get() == acceptButton && agreeCheckBox.isSelected();

        if (accepted) {
            settings.setAcceptedTermsVersion(TermsOfService.VERSION);
            LauncherConfig.saveSettings(settings);
            return true;
        }
        return false;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
