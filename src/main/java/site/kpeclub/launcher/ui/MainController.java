package site.kpeclub.launcher.ui;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import site.kpeclub.launcher.auth.MicrosoftAuth;
import site.kpeclub.launcher.auth.MicrosoftAuth.MinecraftSession;
import site.kpeclub.launcher.download.FabricInstaller;
import site.kpeclub.launcher.download.GameDownloader;
import site.kpeclub.launcher.download.VersionManifest;
import site.kpeclub.launcher.download.VersionManifest.VersionEntry;
import site.kpeclub.launcher.launch.GameLauncher;
import site.kpeclub.launcher.launch.JavaChoice;
import site.kpeclub.launcher.util.PlatformInfo;
import site.kpeclub.launcher.model.LauncherSettings;
import site.kpeclub.launcher.util.LauncherConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MainController {

    @FXML private Label accountLabel;
    @FXML private Label accountStatusLabel;
    @FXML private Button loginButton;
    @FXML private TextField usernameField;
    @FXML private ToggleButton navHomeButton;
    @FXML private ToggleButton navPlayButton;
    @FXML private ToggleButton navWhatsNewButton;
    @FXML private ToggleButton navSettingsButton;
    @FXML private ToggleButton navInstallationsButton;
    @FXML private ToggleButton navModpacksButton;
    @FXML private ToggleButton navBrowseModsButton;
    @FXML private javafx.scene.layout.BorderPane playView;
    @FXML private javafx.scene.layout.StackPane heroBannerPane;
    @FXML private javafx.scene.layout.StackPane heroBannerOverlay;
    @FXML private ScrollPane settingsView;
    @FXML private Slider ramSlider;
    @FXML private Label ramValueLabel;
    @FXML private Label ramHintLabel;
    @FXML private Button ramAutoButton;
    @FXML private CheckBox showSnapshotsCheckBox;
    @FXML private CheckBox showOldVersionsCheckBox;
    @FXML private Label systemInfoLabel;
    @FXML private TextField javaPathField;
    @FXML private Button browseJavaButton;
    @FXML private Button clearJavaButton;
    @FXML private TextField extraJvmField;
    @FXML private CheckBox fpsBoostCheckBox;
    @FXML private Label performanceHeadingLabel;
    @FXML private CheckBox driverBypassCheckBox;
    @FXML private Button installFpsModsButton;
    @FXML private Button lowGraphicsButton;
    @FXML private Button importMesaButton;
    @FXML private Label fpsHintLabel;
    @FXML private javafx.scene.layout.VBox installationsView;
    @FXML private javafx.scene.layout.VBox installationsListBox;
    @FXML private javafx.scene.layout.VBox modsListBox;
    @FXML private javafx.scene.layout.VBox resourcePacksListBox;
    @FXML private Button refreshInstallationsButton;
    @FXML private Button refreshHomeButton;
    @FXML private Label installationsFolderLabel;
    @FXML private javafx.scene.layout.VBox modpacksView;
    @FXML private javafx.scene.layout.VBox modpacksListBox;
    @FXML private Button importModpackButton;
    @FXML private ProgressBar modpackImportProgress;
    @FXML private Label modpackImportStatusLabel;
    @FXML private javafx.scene.layout.VBox browseModsView;
    @FXML private Label browseModsContextLabel;
    @FXML private TextField modSearchField;
    @FXML private Button modSearchButton;
    @FXML private javafx.scene.layout.VBox modSearchResultsBox;
    @FXML private Label modSearchStatusLabel;
    @FXML private javafx.scene.layout.HBox modCategoryBox;
    @FXML private javafx.scene.layout.HBox modFabricBanner;
    @FXML private Button modFabricButton;
    @FXML private CheckBox keepOpenCheckBox;
    @FXML private ComboBox<String> themeComboBox;
    @FXML private Label wallpaperStatusLabel;
    @FXML private Button changeWallpaperButton;
    @FXML private Button resetWallpaperButton;
    @FXML private Slider bannerBrightnessSlider;
    @FXML private ComboBox<String> uninstallDataComboBox;
    @FXML private Label updateStatusLabel;
    @FXML private Button checkUpdateButton;
    @FXML private Button applyLocalUpdateButton;
    @FXML private ComboBox<String> versionComboBox;
    @FXML private ComboBox<String> modLoaderComboBox;
    @FXML private ComboBox<String> fabricLoaderVersionComboBox;
    @FXML private TextField widthField;
    @FXML private TextField heightField;
    @FXML private Button playButton;
    @FXML private ProgressBar downloadProgress;
    @FXML private Label progressDetailLabel;
    @FXML private Button cancelButton;
    @FXML private Label statusLabel;
    @FXML private Label consoleLabel;

    private MinecraftSession session; // null until logged in
    private boolean isOnlineSession = false; // true when signed in via Microsoft OAuth
    private Task<Void> currentDownloadTask; // tracked so Cancel can stop it
    private Process runningGameProcess; // tracked so we can warn about launching a second instance
    private boolean suppressSelectionSave = false; // prevents programmatic dropdown population from overwriting settings.json
    private String preferredFabricLoaderVersion = null; // used when launching a specific modpack or installation

    @FXML
    public void initialize() {
        playButton.setDisable(true);
        modLoaderComboBox.getItems().addAll("Vanilla", "Fabric");
        modLoaderComboBox.getSelectionModel().selectFirst();
        loadVersions();
        loadResolutionSettings();
        loadKeepOpenSetting();
        keepOpenCheckBox.selectedProperty().addListener((obs, old, val) -> saveKeepOpenSetting());
        updateWallpaperStatusLabel();
        changeWallpaperButton.setOnAction(e -> handleChangeWallpaper());
        resetWallpaperButton.setOnAction(e -> handleResetWallpaper());
        setupThemeComboBox();
        setupBannerBrightnessSlider();
        setupUninstallDataComboBox();
        setupLauncherUpdater();
        setupAdvancedSettings();

        modLoaderComboBox.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            updateFabricLoaderVersionVisibility();
            saveSelectedVersionAndLoader();
        });
        versionComboBox.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            if ("Fabric".equals(modLoaderComboBox.getSelectionModel().getSelectedItem())) {
                loadFabricLoaderVersions();
            }
            saveSelectedVersionAndLoader();
        });
        fabricLoaderVersionComboBox.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> saveSelectedVersionAndLoader());

        // Ensure initial visibility matches restored modLoaderComboBox state
        updateFabricLoaderVersionVisibility();

        loginButton.setOnAction(e -> {
            if ("Sign out".equals(loginButton.getText())) {
                handleSignOut();
            } else {
                handleLogin();
            }
        });
        refreshHomeButton.setOnAction(e -> {
            loadVersions();
            statusLabel.setText("Version list refreshed.");
            consoleLabel.setText("Version list refreshed.");
        });
        playButton.setOnAction(e -> handlePlay());
        cancelButton.setOnAction(e -> handleCancelDownload());

        // TLauncher-style: typing a username directly activates an offline session,
        // no separate dialog or button click needed. Commits on Enter or when the
        // field loses focus, so we're not rebuilding the session on every keystroke.
        usernameField.textProperty().addListener((obs, oldText, newText) -> onUsernameTextChanged(newText));
        usernameField.setOnAction(e -> commitUsernameField());
        usernameField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) commitUsernameField();
        });

        setupResolutionField(widthField, 1280, 320, 7680);
        setupResolutionField(heightField, 720, 240, 4320);

        autoActivateTestModeIfSaved();
        loadHeroBannerImage();

        setupNav();
    }

    /** Loads the hero banner image (src/main/resources/images/banner.png) if present, sized
     *  responsively to fill the banner area. If the file is missing or fails to load, the
     *  CSS gradient fallback on .hero-banner shows through instead — this must never throw,
     *  or it can break the rest of the FXML scene graph from loading. */
    private void loadHeroBannerImage() {
        LauncherSettings settings = LauncherConfig.loadSettings();
        String customPath = settings.getCustomWallpaperPath();

        if (customPath != null) {
            java.nio.file.Path path = java.nio.file.Paths.get(customPath);
            if (java.nio.file.Files.exists(path)) {
                if (applyBannerImage(path.toUri().toString())) return;
            }
            // Saved path is missing/broken — fall through to the bundled default below
            // rather than showing a blank pane.
        }

        var resourceUrl = getClass().getResource("/images/banner.png");
        if (resourceUrl != null) {
            applyBannerImage(resourceUrl.toExternalForm());
        }
        // If neither exists, the CSS gradient fallback on .hero-banner shows through — fine.
    }

    /** Loads an image from the given URL and inserts it as the bottom-most layer of the
     *  hero banner pane, replacing any previous banner image. Returns false (and leaves
     *  the pane untouched) if the image fails to load, so callers can fall back safely. */
    private boolean applyBannerImage(String imageUrl) {
        try {
            javafx.scene.image.Image image = new javafx.scene.image.Image(imageUrl);
            if (image.isError()) return false;

            javafx.scene.image.ImageView imageView = new javafx.scene.image.ImageView(image);
            imageView.setManaged(false);
            imageView.setPreserveRatio(false);
            imageView.setSmooth(true);
            // Unmanaged + bound to the pane's actual size so it always fills the banner area
            // and never prevents the window or input bar from shrinking when resized down.
            imageView.fitWidthProperty().bind(heroBannerPane.widthProperty());
            imageView.fitHeightProperty().bind(heroBannerPane.heightProperty());

            // Remove any previously-applied banner ImageView before adding the new one,
            // so switching wallpapers doesn't stack multiple images on top of each other.
            heroBannerPane.getChildren().removeIf(node -> node instanceof javafx.scene.image.ImageView);
            heroBannerPane.getChildren().add(0, imageView);
            return true;
        } catch (Exception e) {
            // Swallow — a bad/corrupt image file should never crash the launcher's UI.
            System.err.println("Could not load banner image: " + e.getMessage());
            return false;
        }
    }

    // ---------------- Fabric loader version selection ----------------

    private void updateFabricLoaderVersionVisibility() {
        boolean isFabric = "Fabric".equals(modLoaderComboBox.getSelectionModel().getSelectedItem());
        fabricLoaderVersionComboBox.setVisible(isFabric);
        fabricLoaderVersionComboBox.setManaged(isFabric);
        if (isFabric) {
            loadFabricLoaderVersions();
        }
    }

    /** Fetches every available Fabric loader version for the currently selected Minecraft
     *  version and populates the loader-version dropdown, newest first. Also includes locally
     *  cached Fabric loader versions so offline play works seamlessly. */
    private void loadFabricLoaderVersions() {
        String mcVersion = versionComboBox.getSelectionModel().getSelectedItem();
        if (mcVersion == null) return;

        String savedFabricLoaderVersion = LauncherConfig.loadSettings().getLastSelectedFabricLoaderVersion();
        String targetLoaderVersion = preferredFabricLoaderVersion;

        suppressSelectionSave = true;
        try {
            fabricLoaderVersionComboBox.getItems().clear();
            // Seed with any locally installed Fabric loader versions for this MC version first
            for (String localLoader : scanLocalFabricLoaderVersions(mcVersion)) {
                fabricLoaderVersionComboBox.getItems().add(localLoader);
            }
            selectMatchingFabricLoaderItem(targetLoaderVersion, savedFabricLoaderVersion);
        } finally {
            suppressSelectionSave = false;
        }

        fabricLoaderVersionComboBox.setPromptText("Loading loader versions...");

        Task<List<site.kpeclub.launcher.download.FabricInstaller.FabricLoaderVersion>> task = new Task<>() {
            @Override
            protected List<site.kpeclub.launcher.download.FabricInstaller.FabricLoaderVersion> call() throws Exception {
                return new site.kpeclub.launcher.download.FabricInstaller().fetchLoaderVersions(mcVersion);
            }
        };
        task.setOnSucceeded(e -> {
            // Ignore stale response if the user already switched to a different MC version
            if (!mcVersion.equals(versionComboBox.getSelectionModel().getSelectedItem())) return;

            var versions = task.getValue();
            suppressSelectionSave = true;
            try {
                fabricLoaderVersionComboBox.getItems().clear();
                for (var v : versions) {
                    fabricLoaderVersionComboBox.getItems().add(v.version() + (v.stable() ? "" : "  (unstable)"));
                }
                for (String localLoader : scanLocalFabricLoaderVersions(mcVersion)) {
                    boolean alreadyListed = fabricLoaderVersionComboBox.getItems().stream()
                            .anyMatch(item -> item.equals(localLoader) || item.startsWith(localLoader + " "));
                    if (!alreadyListed) {
                        fabricLoaderVersionComboBox.getItems().add(localLoader);
                    }
                }
                if (!fabricLoaderVersionComboBox.getItems().isEmpty()) {
                    selectMatchingFabricLoaderItem(preferredFabricLoaderVersion,
                            LauncherConfig.loadSettings().getLastSelectedFabricLoaderVersion());
                } else {
                    fabricLoaderVersionComboBox.setPromptText("No Fabric builds for " + mcVersion);
                }
            } finally {
                suppressSelectionSave = false;
            }
            saveSelectedVersionAndLoader();
        });
        task.setOnFailed(e -> {
            if (fabricLoaderVersionComboBox.getItems().isEmpty()) {
                fabricLoaderVersionComboBox.setPromptText("Couldn't load (offline?)");
            }
        });
        Thread t = new Thread(task, "kpe-fabric-loaders");
        t.setDaemon(true);
        t.start();
    }

    private void selectMatchingFabricLoaderItem(String preferredRawVersion, String savedDropdownItem) {
        if (fabricLoaderVersionComboBox.getItems().isEmpty()) return;
        if (preferredRawVersion != null && !preferredRawVersion.isBlank()) {
            for (String item : fabricLoaderVersionComboBox.getItems()) {
                if (item.equals(preferredRawVersion) || item.startsWith(preferredRawVersion + " ")) {
                    fabricLoaderVersionComboBox.getSelectionModel().select(item);
                    return;
                }
            }
        }
        if (savedDropdownItem != null && !savedDropdownItem.isBlank()) {
            String savedRaw = savedDropdownItem.contains(" ")
                    ? savedDropdownItem.substring(0, savedDropdownItem.indexOf(' '))
                    : savedDropdownItem;
            for (String item : fabricLoaderVersionComboBox.getItems()) {
                if (item.equals(savedDropdownItem) || item.equals(savedRaw) || item.startsWith(savedRaw + " ")) {
                    fabricLoaderVersionComboBox.getSelectionModel().select(item);
                    return;
                }
            }
        }
        fabricLoaderVersionComboBox.getSelectionModel().selectFirst();
    }

    /** Returns locally cached Fabric loader versions for a given Minecraft version. */
    private List<String> scanLocalFabricLoaderVersions(String mcVersion) {
        List<String> loaders = new ArrayList<>();
        try {
            if (!java.nio.file.Files.exists(LauncherConfig.VERSIONS_DIR)) return loaders;
            String prefix = "fabric-loader-";
            String suffix = "-" + mcVersion;
            try (var stream = java.nio.file.Files.list(LauncherConfig.VERSIONS_DIR)) {
                stream.filter(java.nio.file.Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(id -> id.startsWith(prefix) && id.endsWith(suffix)
                                && id.length() > prefix.length() + suffix.length())
                        .filter(id -> isValidCachedVersionJson(
                                LauncherConfig.VERSIONS_DIR.resolve(id).resolve(id + ".json")))
                        .forEach(id -> {
                            String loaderVer = id.substring(prefix.length(), id.length() - suffix.length());
                            if (!loaders.contains(loaderVer)) loaders.add(loaderVer);
                        });
            }
        } catch (java.io.IOException ignored) {}
        return loaders;
    }

    /** Returns just the version number from a dropdown entry like "0.16.9  (unstable)". */
    private String selectedFabricLoaderVersion() {
        if (preferredFabricLoaderVersion != null && !preferredFabricLoaderVersion.isBlank()) {
            return preferredFabricLoaderVersion;
        }
        String raw = fabricLoaderVersionComboBox.getSelectionModel().getSelectedItem();
        if (raw == null) return null;
        int spaceIdx = raw.indexOf(' ');
        return spaceIdx > 0 ? raw.substring(0, spaceIdx) : raw;
    }



    // ---------------- Sidebar nav ----------------

    private void setupNav() {
        ToggleGroup navGroup = new ToggleGroup();
        navHomeButton.setToggleGroup(navGroup);
        navPlayButton.setToggleGroup(navGroup);
        navWhatsNewButton.setToggleGroup(navGroup);
        navSettingsButton.setToggleGroup(navGroup);
        navInstallationsButton.setToggleGroup(navGroup);
        navModpacksButton.setToggleGroup(navGroup);
        navBrowseModsButton.setToggleGroup(navGroup);

        navHomeButton.setOnAction(e -> showPlayView());
        navPlayButton.setOnAction(e -> showPlayView());
        navSettingsButton.setOnAction(e -> showSettingsView());
        navInstallationsButton.setOnAction(e -> showInstallationsView());
        navModpacksButton.setOnAction(e -> showModpacksView());
        navBrowseModsButton.setOnAction(e -> showBrowseModsView());
        navWhatsNewButton.setOnAction(e -> {
            // Placeholder — no dedicated "what's new" content yet, just keep Play view visible
            showPlayView();
            consoleLabel.setText("What's new: nothing posted yet.");
        });

        // Prevent a toggle group from allowing zero selection (user clicking the already-active tab)
        navGroup.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val == null && old != null) old.setSelected(true);
        });

        setupInstallationsTab();
        setupModpacksTab();
        setupBrowseModsTab();
    }

    private void hideAllViews() {
        playView.setVisible(false);
        playView.setManaged(false);
        settingsView.setVisible(false);
        settingsView.setManaged(false);
        installationsView.setVisible(false);
        installationsView.setManaged(false);
        modpacksView.setVisible(false);
        modpacksView.setManaged(false);
        browseModsView.setVisible(false);
        browseModsView.setManaged(false);
    }

    private void showPlayView() {
        hideAllViews();
        playView.setVisible(true);
        playView.setManaged(true);
    }

    private void showSettingsView() {
        hideAllViews();
        settingsView.setVisible(true);
        settingsView.setManaged(true);
    }

    private void showInstallationsView() {
        hideAllViews();
        installationsView.setVisible(true);
        installationsView.setManaged(true);
        loadInstallationsList();
        loadModsList();
        loadResourcePacksList();
    }

    private void showModpacksView() {
        hideAllViews();
        modpacksView.setVisible(true);
        modpacksView.setManaged(true);
        loadModpacksList();
    }

    private void showBrowseModsView() {
        hideAllViews();
        browseModsView.setVisible(true);
        browseModsView.setManaged(true);
        String mcVersion = versionComboBox.getSelectionModel().getSelectedItem();
        browseModsContextLabel.setText(mcVersion != null
                ? "Mods that work with Minecraft " + mcVersion + ". One click installs them."
                : "Choose a Minecraft version on the Play screen first.");
        updateModsBanner();
        if (!(mcVersion + "|").equals(modBrowseLoadedFor)) {
            modBrowseLoadedFor = mcVersion + "|";
            runModSearch();
        }
    }

    // ---------------- Installations tab ----------------

    private void setupInstallationsTab() {
        installationsFolderLabel.setText(LauncherConfig.VERSIONS_DIR.toString());
        refreshInstallationsButton.setOnAction(e -> {
            loadInstallationsList();
            loadModsList();
            loadResourcePacksList();
        });
    }

    // ---------------- Modpacks tab ----------------

    private void setupModpacksTab() {
        importModpackButton.setOnAction(e -> handleImportModpack());
    }

    private void loadModpacksList() {
        modpacksListBox.getChildren().clear();
        List<site.kpeclub.launcher.model.ModpackInfo> packs = LauncherConfig.loadModpacks();
        if (packs.isEmpty()) {
            Label empty = new Label("No modpacks imported yet — click \"Import .mrpack\" to add one.");
            empty.getStyleClass().add("status-label");
            modpacksListBox.getChildren().add(empty);
            return;
        }
        for (var pack : packs) {
            modpacksListBox.getChildren().add(buildModpackRow(pack));
        }
    }

    private javafx.scene.layout.HBox buildModpackRow(site.kpeclub.launcher.model.ModpackInfo pack) {
        javafx.scene.layout.StackPane icon = new javafx.scene.layout.StackPane();
        icon.getStyleClass().add("install-icon");
        icon.getStyleClass().add("install-icon-fabric");
        Label iconLetter = new Label("P");
        iconLetter.getStyleClass().add("install-icon-letter");
        icon.getChildren().add(iconLetter);

        Label name = new Label(pack.getName());
        name.getStyleClass().add("install-name");
        String loaderLabel = "fabric-loader".equals(pack.getLoader()) ? "Fabric" : pack.getLoader();
        Label subtitle = new Label(pack.getMinecraftVersion() + "  -  " + loaderLabel +
                (pack.getLoaderVersion() != null ? " " + pack.getLoaderVersion() : "") +
                "  -  " + pack.getFileCount() + " mods");
        subtitle.getStyleClass().add("install-subtitle");
        javafx.scene.layout.VBox textStack = new javafx.scene.layout.VBox(2, name, subtitle);

        Button playBtn = new Button("Play");
        playBtn.getStyleClass().add("btn-play-small");
        playBtn.setOnAction(e -> handlePlayModpack(pack));

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12, icon, textStack, spacer, playBtn);
        row.getStyleClass().add("install-row");
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    /** Sets this modpack's version + loader as active on the Play screen and launches it. */
    private void handlePlayModpack(site.kpeclub.launcher.model.ModpackInfo pack) {
        if (!versionComboBox.getItems().contains(pack.getMinecraftVersion())) {
            versionComboBox.getItems().add(0, pack.getMinecraftVersion());
        }
        boolean isFabric = "fabric-loader".equals(pack.getLoader());
        preferredFabricLoaderVersion = isFabric ? pack.getLoaderVersion() : null;
        try {
            versionComboBox.getSelectionModel().select(pack.getMinecraftVersion());
            modLoaderComboBox.getSelectionModel().select(isFabric ? "Fabric" : "Vanilla");
            showPlayView();
            navPlayButton.setSelected(true);
            handlePlay();
        } finally {
            preferredFabricLoaderVersion = null;
        }
    }

    private void handleImportModpack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Modrinth Modpack");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Modrinth Modpack (*.mrpack)", "*.mrpack"));

        javafx.stage.Stage stage = (javafx.stage.Stage) importModpackButton.getScene().getWindow();
        java.io.File selected = chooser.showOpenDialog(stage);
        if (selected == null) return;

        // A modpack contains programs (mods) that run on your PC with your permissions — be clear about it.
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "A modpack installs mods, and mods are programs that can do anything on your PC.\n\n"
                + "Only import modpacks from sources you trust (for example Modrinth).\n\nFile: " + selected.getName(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Import modpack");
        confirm.setHeaderText("Do you trust this modpack?");
        confirm.getDialogPane().getStylesheets().addAll(importModpackButton.getScene().getStylesheets());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        importModpackButton.setDisable(true);
        modpackImportProgress.setVisible(true);
        modpackImportProgress.setManaged(true);
        modpackImportProgress.setProgress(0);
        modpackImportStatusLabel.setText("Importing " + selected.getName() + "...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                site.kpeclub.launcher.download.ModpackImporter importer =
                        new site.kpeclub.launcher.download.ModpackImporter();

                var result = importer.importPack(selected.toPath(), progress ->
                        Platform.runLater(() -> {
                            modpackImportProgress.setProgress(progress.fraction());
                            modpackImportStatusLabel.setText(String.format("%.0f%%  -  %s",
                                    progress.percent(), progress.currentFile()));
                        }));

                Platform.runLater(() -> {
                    List<site.kpeclub.launcher.model.ModpackInfo> packs = LauncherConfig.loadModpacks();
                    packs.removeIf(p -> p.getName().equals(result.info().getName())
                            && p.getMinecraftVersion().equals(result.info().getMinecraftVersion()));
                    packs.add(result.info());
                    LauncherConfig.saveModpacks(packs);
                    loadModpacksList();
                    modpackImportStatusLabel.setText("Imported " + result.info().getName() + " successfully.");
                });
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            importModpackButton.setDisable(false);
            modpackImportProgress.setVisible(false);
            modpackImportProgress.setManaged(false);
        });
        task.setOnFailed(e -> {
            importModpackButton.setDisable(false);
            modpackImportProgress.setVisible(false);
            modpackImportProgress.setManaged(false);
            Throwable ex = task.getException();
            modpackImportStatusLabel.setText("Import failed: " + (ex != null ? ex.getMessage() : "Unknown error"));
        });
        startDaemon(task, "kpe-modpack-import");
    }

    /**
     * Identifies which loader (if any) a version folder represents, checking both the
     * naming convention our own downloads use and the actual json content for installs
     * made by Fabric Installer / Forge Installer / OptiFine via the official launcher,
     * where naming isn't guaranteed and we need to inspect mainClass/libraries instead.
     */
    private site.kpeclub.launcher.model.InstalledVersion.LoaderType detectLoaderType(
            String id, java.nio.file.Path jsonPath) {
        // Our own Fabric downloads (via Fabric's meta API) always use this naming convention.
        if (id.startsWith("fabric-loader-")) {
            return site.kpeclub.launcher.model.InstalledVersion.LoaderType.FABRIC;
        }

        try {
            var json = com.google.gson.JsonParser
                    .parseString(java.nio.file.Files.readString(jsonPath)).getAsJsonObject();

            String mainClass = json.has("mainClass") ? json.get("mainClass").getAsString() : "";
            if (mainClass.contains("fabricmc") || mainClass.contains("knot")) {
                return site.kpeclub.launcher.model.InstalledVersion.LoaderType.FABRIC;
            }
            if (mainClass.contains("optifine") || id.toLowerCase().contains("optifine")) {
                return site.kpeclub.launcher.model.InstalledVersion.LoaderType.OPTIFINE;
            }
            if (mainClass.contains("forge") || id.toLowerCase().contains("forge")
                    || mainClass.contains("fml") || mainClass.contains("bootstraplauncher")) {
                return site.kpeclub.launcher.model.InstalledVersion.LoaderType.FORGE;
            }

            // mainClass alone isn't always conclusive (some Forge builds share Mojang's
            // launchwrapper) — also check library coordinates as a fallback signal.
            if (json.has("libraries")) {
                for (var libEl : json.getAsJsonArray("libraries")) {
                    if (!libEl.isJsonObject() || !libEl.getAsJsonObject().has("name")) continue;
                    String name = libEl.getAsJsonObject().get("name").getAsString().toLowerCase();
                    if (name.contains("optifine")) return site.kpeclub.launcher.model.InstalledVersion.LoaderType.OPTIFINE;
                    if (name.contains("fabricmc")) return site.kpeclub.launcher.model.InstalledVersion.LoaderType.FABRIC;
                    if (name.contains("minecraftforge") || name.contains("neoforge")) {
                        return site.kpeclub.launcher.model.InstalledVersion.LoaderType.FORGE;
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to VANILLA — can't read the file, treat it as unremarkable
            // rather than failing the whole scan over one bad entry.
        }

        return site.kpeclub.launcher.model.InstalledVersion.LoaderType.VANILLA;
    }

    private void loadInstallationsList() {
        installationsListBox.getChildren().clear();
        try {
            if (!java.nio.file.Files.exists(LauncherConfig.VERSIONS_DIR)) return;

            List<site.kpeclub.launcher.model.InstalledVersion> found = new ArrayList<>();
            try (var stream = java.nio.file.Files.list(LauncherConfig.VERSIONS_DIR)) {
                stream.filter(java.nio.file.Files::isDirectory)
                        .forEach(dir -> {
                            String id = dir.getFileName().toString();
                            java.nio.file.Path json = dir.resolve(id + ".json");
                            if (!isValidCachedVersionJson(json)) return;

                            // A valid installation either has its own client jar (vanilla, or
                            // Fabric installed via our launcher's meta-API path) or inherits one
                            // from a parent version (Fabric/OptiFine installed via the official
                            // Minecraft Launcher's installer, which never writes their own jar).
                            java.nio.file.Path ownJar = dir.resolve(id + ".jar");
                            boolean hasOwnJar = java.nio.file.Files.exists(ownJar);
                            if (!hasOwnJar) {
                                try {
                                    var jsonObj = com.google.gson.JsonParser
                                            .parseString(java.nio.file.Files.readString(json)).getAsJsonObject();
                                    if (!jsonObj.has("inheritsFrom")) return; // no jar and nothing to inherit from — broken
                                } catch (Exception e) {
                                    return;
                                }
                            }

                            var loaderType = detectLoaderType(id, json);
                            long size = folderSizeBytes(dir);
                            found.add(new site.kpeclub.launcher.model.InstalledVersion(id, loaderType, size));
                        });
            }

            if (found.isEmpty()) {
                Label empty = new Label("No installations found yet — download a version from the Play tab first.");
                empty.getStyleClass().add("status-label");
                installationsListBox.getChildren().add(empty);
                return;
            }

            for (var installation : found) {
                installationsListBox.getChildren().add(buildInstallationRow(installation));
            }
        } catch (java.io.IOException e) {
            statusLabel.setText("Could not list installations: " + e.getMessage());
        }
    }

    // ---------------- Mods ----------------

    private void loadModsList() {
        modsListBox.getChildren().clear();
        List<site.kpeclub.launcher.model.ModInfo> mods = site.kpeclub.launcher.util.ContentScanner.scanMods();
        if (mods.isEmpty()) {
            Label empty = new Label("No mods found in " + site.kpeclub.launcher.util.ContentScanner.modsDir());
            empty.getStyleClass().add("status-label");
            modsListBox.getChildren().add(empty);
            return;
        }
        for (var mod : mods) {
            modsListBox.getChildren().add(buildModRow(mod));
        }
    }

    // ---------------- Browse Mods tab (Modrinth search + download) ----------------

    /** Wires up the Browse Mods tab's search field/button. Filters by whatever Minecraft
     *  version + loader is currently selected on the Play screen (re-read fresh on every
     *  search, so switching version/loader on the Play tab affects the next search here). */
    private String modCategory = null; // null = most popular
    private String modBrowseLoadedFor = "";

    private void setupBrowseModsTab() {
        // Category "chips" so nobody has to know what to type.
        ToggleGroup group = new ToggleGroup();
        String[][] cats = {{"Popular", null}, {"More FPS", "optimization"}, {"Useful", "utility"},
                {"Adventure", "adventure"}, {"Looks nice", "decoration"}, {"Technology", "technology"}};
        for (String[] c : cats) {
            ToggleButton chip = new ToggleButton(c[0]);
            chip.getStyleClass().add("chip");
            chip.setToggleGroup(group);
            chip.setUserData(c[1]);
            modCategoryBox.getChildren().add(chip);
        }
        ((ToggleButton) modCategoryBox.getChildren().get(0)).setSelected(true);
        group.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) { if (old != null) old.setSelected(true); return; }
            modCategory = (String) now.getUserData();
            runModSearch();
        });

        modSearchButton.setOnAction(e -> runModSearch());
        modSearchField.setOnAction(e -> runModSearch());
        modFabricButton.setOnAction(e -> {
            modLoaderComboBox.getSelectionModel().select("Fabric");
            updateModsBanner();
        });
    }

    private void updateModsBanner() {
        boolean fabric = "Fabric".equals(modLoaderComboBox.getSelectionModel().getSelectedItem());
        modFabricBanner.setVisible(!fabric);
        modFabricBanner.setManaged(!fabric);
    }

    private int modSearchGeneration = 0;

    private void runModSearch() {
        final int generation = ++modSearchGeneration;
        String mc = versionComboBox.getSelectionModel().getSelectedItem();
        modSearchResultsBox.getChildren().clear();
        if (mc == null) {
            modSearchStatusLabel.setText("Go to Play and choose a Minecraft version first.");
            return;
        }
        String query = modSearchField.getText() == null ? "" : modSearchField.getText().trim();
        String category = modCategory;
        modSearchStatusLabel.setText(query.isEmpty() ? "Loading popular mods..." : "Searching...");

        Task<List<site.kpeclub.launcher.model.ModSearchResult>> task = new Task<>() {
            @Override
            protected List<site.kpeclub.launcher.model.ModSearchResult> call() throws Exception {
                return new site.kpeclub.launcher.download.ModrinthClient().search(query, mc, "fabric", category);
            }
        };
        task.setOnSucceeded(e -> {
            if (generation != modSearchGeneration) return; // an older, slower search finished late — ignore it
            var results = task.getValue();
            modSearchResultsBox.getChildren().clear();
            if (results.isEmpty()) {
                modSearchStatusLabel.setText("No mods found for Minecraft " + mc + ". Try another word or category.");
                return;
            }
            modSearchStatusLabel.setText(results.size() + " mods for Minecraft " + mc + ".");
            for (var r : results) modSearchResultsBox.getChildren().add(buildModCard(r, mc));
        });
        task.setOnFailed(e -> {
            if (generation == modSearchGeneration) {
                modSearchStatusLabel.setText("Could not load mods. Check your internet and try again.");
            }
        });
        Thread t = new Thread(task, "kpe-mod-search");
        t.setDaemon(true);
        t.start();
    }

    private static String compactCount(long n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(java.util.Locale.ROOT, "%.0fk", n / 1_000.0);
        return String.valueOf(n);
    }

    private javafx.scene.layout.HBox buildModCard(site.kpeclub.launcher.model.ModSearchResult result, String mcVersion) {
        // icon: letter first, real picture on top once it has loaded
        javafx.scene.layout.StackPane icon = new javafx.scene.layout.StackPane();
        icon.getStyleClass().addAll("install-icon", "install-icon-fabric");
        icon.setMinSize(52, 52); icon.setMaxSize(52, 52);
        Label letter = new Label(result.getTitle().isEmpty() ? "?" : result.getTitle().substring(0, 1).toUpperCase());
        letter.getStyleClass().add("install-icon-letter");
        icon.getChildren().add(letter);
        if (result.getIconUrl() != null && result.getIconUrl().startsWith("https://cdn.modrinth.com/")) {
            javafx.scene.image.Image img = new javafx.scene.image.Image(result.getIconUrl(), 52, 52, true, true, true);
            javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(img);
            view.setFitWidth(52); view.setFitHeight(52);
            javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(52, 52);
            clip.setArcWidth(14); clip.setArcHeight(14);
            view.setClip(clip);
            img.progressProperty().addListener((o, a, p) -> {
                if (p.doubleValue() >= 1.0 && !img.isError()) icon.getChildren().setAll(view);
            });
        }

        Label name = new Label(result.getTitle());
        name.getStyleClass().add("install-name");
        Label meta = new Label("by " + result.getAuthor() + "   ·   " + compactCount(result.getDownloads()) + " downloads");
        meta.getStyleClass().add("install-subtitle");
        Label desc = new Label(result.getDescription());
        desc.getStyleClass().add("mod-desc");
        desc.setWrapText(true);
        desc.setMaxHeight(34);
        javafx.scene.layout.VBox text = new javafx.scene.layout.VBox(3, name, meta, desc);
        javafx.scene.layout.HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS);

        Button action = new Button();
        Button remove = new Button("Remove");
        remove.getStyleClass().add("btn-outline-small");
        Runnable showState = () -> {
            boolean installed = site.kpeclub.launcher.download.ModInstaller.isInstalled(result.getProjectId())
                    || site.kpeclub.launcher.download.ModInstaller.isInstalled(result.getSlug());
            action.getStyleClass().removeAll("btn-play-small", "btn-installed");
            action.getStyleClass().add(installed ? "btn-installed" : "btn-play-small");
            action.setText(installed ? "Installed ✓" : "Install");
            action.setDisable(installed);
            remove.setVisible(installed);
            remove.setManaged(installed);
        };
        showState.run();

        action.setOnAction(e -> {
            action.setDisable(true);
            action.setText("Installing...");
            Task<List<String>> task = new Task<>() {
                @Override
                protected List<String> call() throws Exception {
                    return new site.kpeclub.launcher.download.ModInstaller().install(result.getProjectId(), mcVersion);
                }
            };
            task.setOnSucceeded(ev -> {
                showState.run();
                int extra = Math.max(0, task.getValue().size() - 1);
                modLoaderComboBox.getSelectionModel().select("Fabric"); // mods need Fabric — switch it on for them
                updateModsBanner();
                modSearchStatusLabel.setText(result.getTitle() + " is installed"
                        + (extra > 0 ? " (and " + extra + " needed extra mod" + (extra > 1 ? "s" : "") + ")" : "")
                        + ". Go to Play and press PLAY.");
            });
            task.setOnFailed(ev -> {
                showState.run();
                Throwable ex = task.getException();
                modSearchStatusLabel.setText(ex instanceof java.io.IOException && ex.getMessage() != null
                        && ex.getMessage().startsWith("This mod has no")
                        ? ex.getMessage() : "Could not install " + result.getTitle() + ". Check your internet.");
            });
            Thread t = new Thread(task, "kpe-mod-install");
            t.setDaemon(true);
            t.start();
        });
        remove.setOnAction(e -> {
            try {
                site.kpeclub.launcher.download.ModInstaller.remove(result.getProjectId());
                site.kpeclub.launcher.download.ModInstaller.remove(result.getSlug());
                modSearchStatusLabel.setText(result.getTitle() + " removed.");
            } catch (java.io.IOException ex) {
                modSearchStatusLabel.setText("Could not remove: " + ex.getMessage());
            }
            showState.run();
        });

        javafx.scene.layout.VBox buttons = new javafx.scene.layout.VBox(6, action, remove);
        buttons.setAlignment(javafx.geometry.Pos.CENTER);
        buttons.setMinWidth(110);

        javafx.scene.layout.HBox card = new javafx.scene.layout.HBox(14, icon, text, buttons);
        card.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        card.getStyleClass().add("mod-card");
        return card;
    }

    private void loadResourcePacksList() {
        resourcePacksListBox.getChildren().clear();
        List<site.kpeclub.launcher.model.ResourcePackInfo> packs =
                site.kpeclub.launcher.util.ContentScanner.scanResourcePacks();
        if (packs.isEmpty()) {
            Label empty = new Label("No resource packs found in " +
                    site.kpeclub.launcher.util.ContentScanner.resourcePacksDir());
            empty.getStyleClass().add("status-label");
            resourcePacksListBox.getChildren().add(empty);
            return;
        }
        for (var pack : packs) {
            resourcePacksListBox.getChildren().add(buildResourcePackRow(pack));
        }
    }

    private javafx.scene.layout.HBox buildModRow(site.kpeclub.launcher.model.ModInfo mod) {
        javafx.scene.layout.StackPane icon = new javafx.scene.layout.StackPane();
        icon.getStyleClass().add("install-icon");
        icon.getStyleClass().add("install-icon-fabric"); // reuse the brown square for mods generically
        Label iconLetter = new Label("M");
        iconLetter.getStyleClass().add("install-icon-letter");
        icon.getChildren().add(iconLetter);

        Label name = new Label(mod.getDisplayName());
        name.getStyleClass().add("install-name");

        StringBuilder subtitleText = new StringBuilder();
        subtitleText.append(mod.getLoader());
        if (mod.getVersion() != null) subtitleText.append("  -  v").append(mod.getVersion());
        subtitleText.append("  -  ").append(String.format("%.1f MB", mod.getSizeMB()));
        if (mod.getModId() == null) subtitleText.append("  -  (no metadata found)");

        Label subtitle = new Label(subtitleText.toString());
        subtitle.getStyleClass().add("install-subtitle");
        javafx.scene.layout.VBox textStack = new javafx.scene.layout.VBox(2, name, subtitle);

        Button folderBtn = new Button("Folder");
        folderBtn.getStyleClass().add("btn-icon");
        folderBtn.setOnAction(e -> openFolderInExplorer(site.kpeclub.launcher.util.ContentScanner.modsDir()));

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12, icon, textStack, spacer, folderBtn);
        row.getStyleClass().add("install-row");
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    private javafx.scene.layout.HBox buildResourcePackRow(site.kpeclub.launcher.model.ResourcePackInfo pack) {
        javafx.scene.layout.StackPane icon = new javafx.scene.layout.StackPane();
        icon.getStyleClass().add("install-icon");
        icon.getStyleClass().add("install-icon-vanilla"); // reuse the green square for resource packs
        Label iconLetter = new Label("R");
        iconLetter.getStyleClass().add("install-icon-letter");
        icon.getChildren().add(iconLetter);

        Label name = new Label(pack.getName());
        name.getStyleClass().add("install-name");

        StringBuilder subtitleText = new StringBuilder();
        subtitleText.append(pack.isZip() ? "Zip" : "Folder");
        subtitleText.append("  -  ").append(String.format("%.1f MB", pack.getSizeMB()));
        if (pack.getDescription() != null && !pack.getDescription().isBlank()) {
            subtitleText.append("  -  ").append(pack.getDescription());
        }

        Label subtitle = new Label(subtitleText.toString());
        subtitle.getStyleClass().add("install-subtitle");
        javafx.scene.layout.VBox textStack = new javafx.scene.layout.VBox(2, name, subtitle);

        Button folderBtn = new Button("Folder");
        folderBtn.getStyleClass().add("btn-icon");
        folderBtn.setOnAction(e -> openFolderInExplorer(site.kpeclub.launcher.util.ContentScanner.resourcePacksDir()));

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12, icon, textStack, spacer, folderBtn);
        row.getStyleClass().add("install-row");
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    private void openFolderInExplorer(java.nio.file.Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir); // so "Folder" works even before anything's installed
            new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start();
        } catch (java.io.IOException e) {
            statusLabel.setText("Could not open folder: " + e.getMessage());
        }
    }

    /** Builds one row: icon, name + subtitle, Play / folder / delete buttons — matches the
     *  official Minecraft Launcher's Installations tab layout. */
    private javafx.scene.layout.HBox buildInstallationRow(site.kpeclub.launcher.model.InstalledVersion installation) {
        var loaderType = installation.getLoaderType();

        javafx.scene.layout.StackPane icon = createLoaderIcon(loaderType);

        String displayName = installation.isModded()
                ? loaderType.getLabel() + " " + fabricMcVersion(installation.getVersionId())
                : installation.getVersionId();
        Label name = new Label(displayName);
        name.getStyleClass().add("install-name");
        Label subtitle = new Label(installation.getVersionId() + "  -  " +
                String.format("%.1f MB", installation.getSizeMB()));
        subtitle.getStyleClass().add("install-subtitle");
        javafx.scene.layout.VBox textStack = new javafx.scene.layout.VBox(2, name, subtitle);

        Button playBtn = new Button("Play");
        playBtn.getStyleClass().add("btn-play-small");
        playBtn.setOnAction(e -> handlePlayInstallation(installation));

        Button folderBtn = new Button("Folder");
        folderBtn.getStyleClass().add("btn-icon");
        folderBtn.setOnAction(e -> openInstallationFolder(installation));

        Button deleteBtn = new Button("Delete");
        deleteBtn.getStyleClass().add("btn-icon");
        deleteBtn.setOnAction(e -> handleDeleteInstallation(installation));

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12, icon, textStack, spacer, playBtn, folderBtn, deleteBtn);
        row.getStyleClass().add("install-row");
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    /** Extracts the underlying vanilla Minecraft version for a modded install's display name.
     *  Prefers reading "inheritsFrom" from the version json (works for Forge, OptiFine, and
     *  Fabric-via-official-installer, whose folder names vary), falling back to Fabric's own
     *  "fabric-loader-<loader>-<mc>" naming convention for Fabric installs via our launcher. */
    private javafx.scene.layout.StackPane createLoaderIcon(
            site.kpeclub.launcher.model.InstalledVersion.LoaderType loaderType) {
        javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(36, 36);
        var g = canvas.getGraphicsContext2D();
        switch (loaderType) {
            case VANILLA -> {
                // Classic Minecraft Grass Block pixel-art icon
                g.setFill(javafx.scene.paint.Color.web("#7B5230"));
                g.fillRect(0, 0, 36, 36);
                g.setFill(javafx.scene.paint.Color.web("#5D3C21"));
                g.fillRect(4, 18, 5, 5);
                g.fillRect(22, 24, 6, 5);
                g.fillRect(12, 28, 5, 4);
                g.setFill(javafx.scene.paint.Color.web("#93643D"));
                g.fillRect(15, 17, 5, 4);
                g.fillRect(27, 16, 5, 5);
                // Green grass top + jagged overhang
                g.setFill(javafx.scene.paint.Color.web("#58A83E"));
                g.fillRect(0, 0, 36, 11);
                g.fillRect(0, 11, 7, 4);
                g.fillRect(12, 11, 8, 5);
                g.fillRect(25, 11, 11, 3);
                g.setFill(javafx.scene.paint.Color.web("#72C850"));
                g.fillRect(3, 2, 10, 4);
                g.fillRect(19, 3, 9, 4);
                g.setFill(javafx.scene.paint.Color.web("#3E7B2B"));
                g.fillRect(7, 11, 5, 2);
                g.fillRect(20, 11, 5, 2);
            }
            case FABRIC -> {
                // Fabric woven parchment / scroll icon
                g.setFill(javafx.scene.paint.Color.web("#E8DEC8"));
                g.fillRect(0, 0, 36, 36);
                g.setFill(javafx.scene.paint.Color.web("#CFC1A5"));
                g.fillRect(0, 27, 36, 9);
                g.setFill(javafx.scene.paint.Color.web("#3B312B"));
                g.fillRoundRect(7, 7, 22, 16, 4, 4);
                g.setFill(javafx.scene.paint.Color.web("#F4EDE0"));
                g.fillRect(10, 10, 16, 3);
                g.fillRect(10, 16, 11, 3);
                g.setFill(javafx.scene.paint.Color.web("#8C735B"));
                g.fillRect(11, 25, 14, 4);
            }
            case FORGE -> {
                // Forge metallic anvil icon on dark slate
                g.setFill(javafx.scene.paint.Color.web("#1F2633"));
                g.fillRect(0, 0, 36, 36);
                g.setFill(javafx.scene.paint.Color.web("#F59E0B"));
                g.fillRect(0, 31, 36, 5);
                g.setFill(javafx.scene.paint.Color.web("#CBD5E1"));
                g.fillRect(6, 10, 24, 5);
                g.fillRect(10, 15, 16, 4);
                g.setFill(javafx.scene.paint.Color.web("#94A3B8"));
                g.fillRect(13, 19, 10, 6);
                g.fillRect(8, 25, 20, 4);
            }
            case OPTIFINE -> {
                // OptiFine crimson & gold optic lens icon
                g.setFill(javafx.scene.paint.Color.web("#7F1D1D"));
                g.fillRect(0, 0, 36, 36);
                g.setFill(javafx.scene.paint.Color.web("#DC2626"));
                g.fillOval(5, 5, 26, 26);
                g.setStroke(javafx.scene.paint.Color.web("#FBBF24"));
                g.setLineWidth(2.5);
                g.strokeOval(6.5, 6.5, 23, 23);
                g.setFill(javafx.scene.paint.Color.web("#FEF3C7"));
                g.fillOval(12, 11, 5, 5);
                g.setFill(javafx.scene.paint.Color.web("#991B1B"));
                g.fillOval(14, 14, 8, 8);
            }
        }
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(36, 36);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        canvas.setClip(clip);

        javafx.scene.layout.StackPane box = new javafx.scene.layout.StackPane(canvas);
        box.setMinSize(36, 36);
        box.setPrefSize(36, 36);
        box.setMaxSize(36, 36);
        return box;
    }

    private String fabricMcVersion(String versionId) {
        try {
            java.nio.file.Path json = LauncherConfig.VERSIONS_DIR.resolve(versionId).resolve(versionId + ".json");
            if (java.nio.file.Files.exists(json)) {
                var jsonObj = com.google.gson.JsonParser
                        .parseString(java.nio.file.Files.readString(json)).getAsJsonObject();
                if (jsonObj.has("inheritsFrom")) {
                    return jsonObj.get("inheritsFrom").getAsString();
                }
            }
        } catch (Exception ignored) {
            // fall through to the naming-convention guess below
        }
        if (versionId.startsWith("fabric-loader-")) {
            String rest = versionId.substring("fabric-loader-".length());
            int nextDash = rest.indexOf('-');
            if (nextDash >= 0 && nextDash + 1 < rest.length()) {
                return rest.substring(nextDash + 1);
            }
        }
        int lastDash = versionId.lastIndexOf('-');
        return lastDash >= 0 ? versionId.substring(lastDash + 1) : versionId;
    }

    /** Extracts the Fabric loader version from a "fabric-loader-<loader>-<mc>" folder id, if possible. */
    private String extractFabricLoaderVersion(String versionId, String mcVersion) {
        String prefix = "fabric-loader-";
        String suffix = "-" + mcVersion;
        if (versionId.startsWith(prefix) && versionId.endsWith(suffix)
                && versionId.length() > prefix.length() + suffix.length()) {
            return versionId.substring(prefix.length(), versionId.length() - suffix.length());
        }
        return null;
    }

    private long folderSizeBytes(java.nio.file.Path dir) {
        try (var stream = java.nio.file.Files.walk(dir)) {
            return stream.filter(java.nio.file.Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return java.nio.file.Files.size(p);
                        } catch (java.io.IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Sets this installation as the active Play-screen selection, then immediately triggers
     *  the same launch flow as the main Play button (download-if-needed + launch). */
    private void handlePlayInstallation(site.kpeclub.launcher.model.InstalledVersion installation) {
        var loaderType = installation.getLoaderType();

        if (loaderType == site.kpeclub.launcher.model.InstalledVersion.LoaderType.FORGE
                || loaderType == site.kpeclub.launcher.model.InstalledVersion.LoaderType.OPTIFINE) {
            // Forge and OptiFine aren't supported through the Play screen's mod-loader
            // dropdown (only vanilla/Fabric are) — launch this exact cached installation
            // directly instead of trying to route it through that resolution logic.
            launchInstalledVersionDirectly(installation);
            return;
        }

        if (installation.isFabric()) {
            String mcVersion = fabricMcVersion(installation.getVersionId());
            String loaderVer = extractFabricLoaderVersion(installation.getVersionId(), mcVersion);
            if (loaderVer == null) {
                // Custom-named Fabric installation (e.g. from official installer) — launch directly
                launchInstalledVersionDirectly(installation);
                return;
            }
            if (!versionComboBox.getItems().contains(mcVersion)) {
                versionComboBox.getItems().add(0, mcVersion);
            }
            preferredFabricLoaderVersion = loaderVer;
            try {
                versionComboBox.getSelectionModel().select(mcVersion);
                modLoaderComboBox.getSelectionModel().select("Fabric");
                showPlayView();
                navPlayButton.setSelected(true);
                handlePlay();
            } finally {
                preferredFabricLoaderVersion = null;
            }
            return;
        } else {
            if (!versionComboBox.getItems().contains(installation.getVersionId())) {
                versionComboBox.getItems().add(0, installation.getVersionId());
            }
            versionComboBox.getSelectionModel().select(installation.getVersionId());
            modLoaderComboBox.getSelectionModel().select("Vanilla");
        }
        showPlayView();
        navPlayButton.setSelected(true);
        handlePlay(); // reuse the exact same download-if-needed + launch flow as the main Play button
    }

    /** Launches an already-cached installation directly by its exact version ID, skipping
     *  the Play screen's version/loader dropdown resolution entirely — used for loader
     *  types (Forge, OptiFine, or custom-named Fabric profiles) that dropdown doesn't have a picker for. */
    private void launchInstalledVersionDirectly(site.kpeclub.launcher.model.InstalledVersion installation) {
        if (!isOnlineSession) commitUsernameField(); // pick up a name typed but not confirmed with Enter
        if (session == null) {
            statusLabel.setText("Type your name first, then press PLAY.");
            return;
        }
        if (runningGameProcess != null && runningGameProcess.isAlive()) {
            Alert warn = new Alert(Alert.AlertType.CONFIRMATION);
            warn.setTitle("Minecraft is already running");
            warn.setHeaderText("A Minecraft instance launched from this launcher is still running.");
            warn.setContentText("Starting another one at the same time can cause lag, world " +
                    "save conflicts, or other issues. Launch anyway?");
            Optional<ButtonType> result = warn.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) {
                statusLabel.setText("Launch cancelled — another instance is already running.");
                return;
            }
        }

        showPlayView();
        navPlayButton.setSelected(true);

        playButton.setDisable(true);
        downloadProgress.setVisible(true);
        downloadProgress.setManaged(true);
        downloadProgress.setProgress(0);
        progressDetailLabel.setVisible(true);
        progressDetailLabel.setManaged(true);
        progressDetailLabel.setText("");
        cancelButton.setVisible(true);
        cancelButton.setManaged(true);
        cancelButton.setDisable(false);

        final int[] resolution = getResolution();
        final String versionId = installation.getVersionId();

        statusLabel.setText("Launching " + versionId + "...");
        consoleLabel.setText("Launching " + versionId + " (" +
                installation.getLoaderType().getLabel() + ")...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                JavaChoice choice = JavaChoice.from(LauncherConfig.loadSettings());
                PlatformInfo.Arch arch = choice.arch();

                java.nio.file.Path versionJsonPath = LauncherConfig.VERSIONS_DIR
                        .resolve(versionId).resolve(versionId + ".json");
                JsonObject versionJson = com.google.gson.JsonParser
                        .parseString(java.nio.file.Files.readString(versionJsonPath)).getAsJsonObject();
                if (versionJson.has("inheritsFrom")) {
                    JsonObject resolved = site.kpeclub.launcher.util.VersionInheritanceResolver
                            .resolve(versionJson, LauncherConfig.VERSIONS_DIR);
                    if (resolved != null) versionJson = resolved;
                }

                if (!GameDownloader.isFullyDownloaded(versionId, arch) && versionJson.has("downloads")) {
                    try {
                        new GameDownloader().downloadVersion(
                                versionJson, arch,
                                progress -> Platform.runLater(() -> {
                                    downloadProgress.setProgress(progress.fraction());
                                    String detail = String.format(java.util.Locale.ROOT,
                                            "%.0f%%  -  %.1f / %.1f MB  (%s)",
                                            progress.percent(), progress.downloadedMB(), progress.totalMB(),
                                            progress.currentFile());
                                    progressDetailLabel.setText(detail);
                                    consoleLabel.setText(detail);
                                }),
                                this::isCancelled);
                    } catch (java.io.IOException ignored) {
                        Platform.runLater(() -> consoleLabel.setText(
                                "Could not verify files (offline?) — trying to launch anyway."));
                    }
                }

                if (isCancelled()) return null;

                ensureJavaFor(versionId, choice, versionJson, this::isCancelled);

                if (isCancelled()) return null;

                ensureMesaIfBypassEnabled(arch, this::isCancelled);

                if (isCancelled()) return null;

                Process launchedProcess = new GameLauncher().launch(
                        versionId, session, null, null,
                        resolution[0], resolution[1], choice);
                runningGameProcess = launchedProcess;
                watchForCrash(launchedProcess);
                return null;
            }
        };

        currentDownloadTask = task;

        task.setOnSucceeded(e -> {
            statusLabel.setText("Minecraft is starting... this can take a minute.");
            consoleLabel.setText("Minecraft launched (" + GameLauncher.lastJavaUsed() + ").");
            resetDownloadUi();
            hideLauncherWhileGameRunningIfConfigured();
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            String msg = ex != null ? ex.getMessage() : "Unknown error";
            statusLabel.setText("Could not start Minecraft: " + msg);
            consoleLabel.setText("Launch failed: " + msg);
            resetDownloadUi();
        });
        task.setOnCancelled(e -> {
            statusLabel.setText("Launch cancelled.");
            consoleLabel.setText("Launch cancelled.");
            resetDownloadUi();
        });
        startDaemon(task, "kpe-direct-launch");
    }

    private void openInstallationFolder(site.kpeclub.launcher.model.InstalledVersion installation) {
        java.nio.file.Path dir = LauncherConfig.VERSIONS_DIR.resolve(installation.getVersionId());
        try {
            new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start();
        } catch (java.io.IOException e) {
            statusLabel.setText("Could not open folder: " + e.getMessage());
        }
    }

    private void handleDeleteInstallation(site.kpeclub.launcher.model.InstalledVersion installation) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete installation");
        confirm.setHeaderText("Delete " + installation.getVersionId() + "?");
        confirm.setContentText("This removes the version files from disk (" +
                String.format("%.1f MB", installation.getSizeMB()) + "). This can't be undone.");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        java.nio.file.Path dir = LauncherConfig.VERSIONS_DIR.resolve(installation.getVersionId());
        try {
            deleteRecursively(dir);
            statusLabel.setText("Deleted " + installation.getVersionId());
            loadInstallationsList();
        } catch (java.io.IOException e) {
            statusLabel.setText("Could not delete: " + e.getMessage());
        }
    }

    private void deleteRecursively(java.nio.file.Path dir) throws java.io.IOException {
        if (!java.nio.file.Files.exists(dir)) return;
        try (var stream = java.nio.file.Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            java.nio.file.Files.delete(p);
                        } catch (java.io.IOException ignored) {
                            // best-effort; leftover locked files (e.g. natives in use) are rare
                        }
                    });
        }
    }

    // ---------------- Resolution settings ----------------

    private void saveSelectedVersionAndLoader() {
        if (suppressSelectionSave) return;
        LauncherSettings settings = LauncherConfig.loadSettings();
        String ver = versionComboBox.getSelectionModel().getSelectedItem();
        if (ver != null) settings.setLastSelectedVersion(ver);
        String loader = modLoaderComboBox.getSelectionModel().getSelectedItem();
        if (loader != null) settings.setLastSelectedModLoader(loader);
        String fabricVer = fabricLoaderVersionComboBox.getSelectionModel().getSelectedItem();
        if (fabricVer != null) settings.setLastSelectedFabricLoaderVersion(fabricVer);
        LauncherConfig.saveSettings(settings);
    }

    /** Re-selects the last-used version + mod loader once the version dropdown has been
     *  populated (called after loadVersions() completes). If the saved version isn't in
     *  the list (e.g. offline and never downloaded), leaves whatever loadVersions() already
     *  auto-selected as a sensible fallback rather than leaving nothing selected. */
    private void restoreSelectedVersionAndLoader(String savedVersion, String savedLoader) {
        suppressSelectionSave = true;
        try {
            if (savedVersion != null && versionComboBox.getItems().contains(savedVersion)) {
                versionComboBox.getSelectionModel().select(savedVersion);
            }
            if (savedLoader != null && modLoaderComboBox.getItems().contains(savedLoader)) {
                modLoaderComboBox.getSelectionModel().select(savedLoader);
            }
        } finally {
            suppressSelectionSave = false;
        }
        // Fabric loader version dropdown populates asynchronously (network call) via the
        // versionComboBox listener above — restoring its selection happens once it loads,
        // see loadFabricLoaderVersions()'s task.setOnSucceeded.
    }

    private void setupResolutionField(TextField field, int defaultVal, int minVal, int maxVal) {
        field.textProperty().addListener((obs, old, val) -> {
            if (val == null) return;
            String digits = val.replaceAll("[^0-9]", "");
            if (digits.length() > 5) digits = digits.substring(0, 5);
            if (!digits.equals(val)) {
                field.setText(digits);
                return;
            }
            saveResolutionSettings();
        });
        field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                normalizeResolutionField(field, defaultVal, minVal, maxVal);
                saveResolutionSettings();
            }
        });
        field.setOnAction(e -> {
            normalizeResolutionField(field, defaultVal, minVal, maxVal);
            saveResolutionSettings();
        });
    }

    private void normalizeResolutionField(TextField field, int defaultVal, int minVal, int maxVal) {
        try {
            String raw = field.getText() == null ? "" : field.getText().trim();
            if (raw.isEmpty()) {
                field.setText(String.valueOf(defaultVal));
                return;
            }
            int parsed = Integer.parseInt(raw);
            int clamped = Math.max(minVal, Math.min(maxVal, parsed));
            if (clamped != parsed) {
                field.setText(String.valueOf(clamped));
            }
        } catch (NumberFormatException e) {
            field.setText(String.valueOf(defaultVal));
        }
    }

    private void loadResolutionSettings() {
        LauncherSettings settings = LauncherConfig.loadSettings();
        int w = Math.max(320, Math.min(7680, settings.getGameWidth() > 0 ? settings.getGameWidth() : 1280));
        int h = Math.max(240, Math.min(4320, settings.getGameHeight() > 0 ? settings.getGameHeight() : 720));
        widthField.setText(String.valueOf(w));
        heightField.setText(String.valueOf(h));
    }

    private void saveResolutionSettings() {
        try {
            int width = Integer.parseInt(widthField.getText().trim());
            int height = Integer.parseInt(heightField.getText().trim());
            if (width < 320 || width > 7680 || height < 240 || height > 4320) {
                return; // wait until focus loss or valid range before saving
            }
            LauncherSettings settings = LauncherConfig.loadSettings();
            settings.setGameWidth(width);
            settings.setGameHeight(height);
            LauncherConfig.saveSettings(settings);
        } catch (NumberFormatException ignored) {
            // user is mid-typing (e.g. field temporarily empty) — don't save garbage
        }
    }

    private void loadKeepOpenSetting() {
        LauncherSettings settings = LauncherConfig.loadSettings();
        keepOpenCheckBox.setSelected(settings.isKeepLauncherOpenWhileGameRunning());
    }

    private void saveKeepOpenSetting() {
        LauncherSettings settings = LauncherConfig.loadSettings();
        settings.setKeepLauncherOpenWhileGameRunning(keepOpenCheckBox.isSelected());
        LauncherConfig.saveSettings(settings);
    }

    // ---------------- Wallpaper & Theme ----------------

    /** RAM slider, Java path, version-type toggles and the 32/64-bit info line. */
    private void setupAdvancedSettings() {
        LauncherSettings s = LauncherConfig.loadSettings();

        // --- system info ---
        boolean launcher64 = PlatformInfo.isLauncherJvm64Bit();
        long totalMb = PlatformInfo.totalPhysicalMemoryMb();
        systemInfoLabel.setText("Your PC: " + PlatformInfo.describe()
                + (totalMb > 0 ? ", " + Math.round(totalMb / 1024.0) + " GB RAM" : "")
                + ". Launcher is " + (launcher64 ? "64" : "32") + "-bit. "
                + "Minecraft uses " + (PlatformInfo.isOs64Bit() ? "64-bit" : "32-bit") + " Java automatically.");

        // --- RAM ---
        int max = PlatformInfo.maxSafeHeapMb(PlatformInfo.isOs64Bit());
        ramSlider.setMax(Math.max(1024, Math.min(max, 16384)));
        ramSlider.setMin(512);
        ramHintLabel.setText(PlatformInfo.isOs64Bit()
                ? "Auto picks a good amount for your PC. More RAM helps with mods and big worlds."
                : "32-bit Windows can only give Minecraft about 1 GB of RAM.");
        int saved = s.getRamMb();
        ramSlider.setValue(saved > 0 ? saved : Math.min(2048, ramSlider.getMax()));
        ramValueLabel.setText(saved > 0 ? formatRam(saved) : "Auto");
        ramSlider.valueProperty().addListener((obs, o, v) -> {
            int mb = (int) (Math.round(v.doubleValue() / 256.0) * 256);
            ramValueLabel.setText(formatRam(mb));
            LauncherSettings cur = LauncherConfig.loadSettings();
            cur.setRamMb(mb);
            LauncherConfig.saveSettings(cur);
        });
        ramAutoButton.setOnAction(e -> {
            LauncherSettings cur = LauncherConfig.loadSettings();
            cur.setRamMb(0);
            LauncherConfig.saveSettings(cur);
            ramValueLabel.setText("Auto");
        });

        // --- version types ---
        showSnapshotsCheckBox.setSelected(s.isShowSnapshots());
        showOldVersionsCheckBox.setSelected(s.isShowOldVersions());
        showSnapshotsCheckBox.selectedProperty().addListener((obs, o, v) -> {
            LauncherSettings cur = LauncherConfig.loadSettings();
            cur.setShowSnapshots(v);
            LauncherConfig.saveSettings(cur);
            loadVersions();
        });
        showOldVersionsCheckBox.selectedProperty().addListener((obs, o, v) -> {
            LauncherSettings cur = LauncherConfig.loadSettings();
            cur.setShowOldVersions(v);
            LauncherConfig.saveSettings(cur);
            loadVersions();
        });

        // --- FPS boost & Old PC GPU driver bypass ---
        fpsBoostCheckBox.setSelected(s.isFpsBoost());
        fpsBoostCheckBox.selectedProperty().addListener((obs, o, v) -> {
            LauncherSettings cur = LauncherConfig.loadSettings();
            cur.setFpsBoost(v);
            LauncherConfig.saveSettings(cur);
        });
        driverBypassCheckBox.setSelected(s.isBypassDriverCheck());
        driverBypassCheckBox.selectedProperty().addListener((obs, o, v) -> {
            LauncherSettings cur = LauncherConfig.loadSettings();
            cur.setBypassDriverCheck(v);
            LauncherConfig.saveSettings(cur);
            if (v) {
                PlatformInfo.Arch arch = PlatformInfo.minecraftArch();
                if (site.kpeclub.launcher.launch.FpsBoost.isMesaInstalled(arch)) {
                    updateMesaButtonLabel();
                    fpsHintLabel.setText("Driver bypass ON (Mesa3D ready): runs Minecraft 1.16.5 and 1.17+ on old Intel Q45 / GMA / HD GPUs.");
                } else {
                    autoDownloadMesaFromSettings();
                }
            } else {
                startDaemon(site.kpeclub.launcher.launch.FpsBoost::cleanupStagedMesaDlls, "kpe-mesa-clean");
                fpsHintLabel.setText("Driver bypass OFF: uses standard Windows OpenGL driver checks.");
            }
        });
        if (!s.isBypassDriverCheck()) {
            startDaemon(() -> {
                boolean modernPc = PlatformInfo.supportsAllMinecraftVersionsNatively();
                if (modernPc) {
                    Platform.runLater(() -> {
                        if (!LauncherConfig.loadSettings().isBypassDriverCheck()) {
                            if (driverBypassCheckBox != null) {
                                driverBypassCheckBox.setVisible(false);
                                driverBypassCheckBox.setManaged(false);
                            }
                            if (importMesaButton != null) {
                                importMesaButton.setVisible(false);
                                importMesaButton.setManaged(false);
                            }
                            if (performanceHeadingLabel != null) {
                                performanceHeadingLabel.setText("Performance");
                            }
                            if (fpsHintLabel != null) {
                                fpsHintLabel.setText("Tip: Boost FPS applies smoother Java settings and high process priority.");
                            }
                        }
                    });
                }
            }, "kpe-gpu-capability-check");
        }
        if (importMesaButton != null) {
            updateMesaButtonLabel();
            importMesaButton.setOnAction(e -> {
                PlatformInfo.Arch arch = PlatformInfo.minecraftArch();
                if (!site.kpeclub.launcher.launch.FpsBoost.isMesaInstalled(arch)) {
                    driverBypassCheckBox.setSelected(true);
                    autoDownloadMesaFromSettings();
                    return;
                }
                FileChooser fc = new FileChooser();
                fc.setTitle("Select Mesa3D opengl32.dll or Mesa3D .zip / .7z archive");
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                        "Mesa3D OpenGL DLL or Archive", "opengl32.dll", "*.dll", "*.zip", "*.7z"));
                java.io.File chosen = fc.showOpenDialog(playButton.getScene().getWindow());
                if (chosen == null) return;
                try {
                    int count = site.kpeclub.launcher.launch.FpsBoost.importMesaFile(
                            chosen.toPath(), arch);
                    driverBypassCheckBox.setSelected(true);
                    updateMesaButtonLabel();
                    fpsHintLabel.setText("Mesa3D OpenGL wrapper installed (" + count
                            + " DLLs). Minecraft 1.16.5 and 1.17+ can now run on old GPUs!");
                } catch (java.io.IOException ex) {
                    fpsHintLabel.setText("Could not load Mesa3D: " + ex.getMessage());
                }
            });
        }
        installFpsModsButton.setOnAction(e -> {
            String mc = versionComboBox.getSelectionModel().getSelectedItem();
            if (mc == null) {
                fpsHintLabel.setText("Choose a Minecraft version on the Play screen first.");
                return;
            }
            installFpsModsButton.setDisable(true);
            fpsHintLabel.setText("Downloading FPS mods for " + mc + "...");
            Task<java.util.List<String>> task = new Task<>() {
                @Override
                protected java.util.List<String> call() throws Exception {
                    return site.kpeclub.launcher.launch.FpsBoost.installFpsMods(mc);
                }
            };
            task.setOnSucceeded(ev -> {
                installFpsModsButton.setDisable(false);
                var done = task.getValue();
                fpsHintLabel.setText(done.isEmpty()
                        ? "No FPS mods are available for " + mc + " (they need Minecraft 1.16 or newer)."
                        : "Installed: " + String.join(", ", done)
                          + ". On the Play screen open \"More options\" and choose Fabric to use them.");
            });
            task.setOnFailed(ev -> {
                installFpsModsButton.setDisable(false);
                fpsHintLabel.setText("Could not download (check your internet).");
            });
            startDaemon(task, "kpe-fps-mods");
        });
        lowGraphicsButton.setOnAction(e -> {
            try {
                site.kpeclub.launcher.launch.FpsBoost.applyLowGraphics();
                fpsHintLabel.setText("Done. Graphics are now set to Fast with a lower render distance. Start the game to see it.");
            } catch (java.io.IOException ex) {
                fpsHintLabel.setText("Could not change options: " + ex.getMessage());
            }
        });

        // --- Java ---
        javaPathField.setText(s.getJavaPath() == null ? "" : s.getJavaPath());
        javaPathField.setOnAction(e -> saveJavaSettings());
        javaPathField.focusedProperty().addListener((obs, o, f) -> { if (!f) saveJavaSettings(); });
        extraJvmField.setText(s.getExtraJvmArgs());
        extraJvmField.setOnAction(e -> saveJavaSettings());
        extraJvmField.focusedProperty().addListener((obs, o, f) -> { if (!f) saveJavaSettings(); });
        browseJavaButton.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Choose java.exe or javaw.exe");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java", "java.exe", "javaw.exe"));
            java.io.File f = fc.showOpenDialog(playButton.getScene().getWindow());
            if (f != null) {
                javaPathField.setText(f.getAbsolutePath());
                saveJavaSettings();
            }
        });
        clearJavaButton.setOnAction(e -> {
            javaPathField.setText("");
            saveJavaSettings();
            refreshAutoDetectedJavaPrompt();
        });
        refreshAutoDetectedJavaPrompt();
    }

    private void refreshAutoDetectedJavaPrompt() {
        Task<String> detectTask = new Task<>() {
            @Override
            protected String call() {
                java.nio.file.Path best = site.kpeclub.launcher.download.JreManager.detectBestJavaOnSystem(
                        PlatformInfo.minecraftArch());
                if (best == null) return null;
                site.kpeclub.launcher.util.JavaProbe.Info info = site.kpeclub.launcher.util.JavaProbe.probe(best);
                if (info == null) return best.toString();
                return "Auto-detected Java " + info.majorVersion() + " (" + (info.is64Bit() ? "64-bit" : "32-bit")
                        + "): " + best;
            }
        };
        detectTask.setOnSucceeded(ev -> {
            String found = detectTask.getValue();
            if (found != null && !found.isBlank()) {
                javaPathField.setPromptText(found);
            } else {
                javaPathField.setPromptText("Automatic (Mojang's Java for your PC)");
            }
        });
        startDaemon(detectTask, "kpe-java-autodetect");
    }

    private void saveJavaSettings() {
        LauncherSettings cur = LauncherConfig.loadSettings();
        cur.setJavaPath(javaPathField.getText());
        cur.setExtraJvmArgs(extraJvmField.getText() == null ? "" : extraJvmField.getText());
        LauncherConfig.saveSettings(cur);
    }

    private static String formatRam(int mb) {
        return mb % 1024 == 0 ? (mb / 1024) + " GB" : String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    private void setupThemeComboBox() {
        themeComboBox.getItems().addAll("Dark", "Light", "System");
        LauncherSettings settings = LauncherConfig.loadSettings();
        String current = settings.getTheme();
        themeComboBox.getSelectionModel().select(
                themeComboBox.getItems().contains(current) ? current : "System");

        themeComboBox.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            if (val == null) return;
            javafx.scene.Scene scene = themeComboBox.getScene();
            if (scene != null) {
                site.kpeclub.launcher.util.ThemeManager.reapply(scene, val);
            }
        });
    }

    private void setupBannerBrightnessSlider() {
        LauncherSettings settings = LauncherConfig.loadSettings();
        bannerBrightnessSlider.setValue(settings.getBannerBrightness());
        applyBannerBrightness(settings.getBannerBrightness());

        bannerBrightnessSlider.valueProperty().addListener((obs, old, val) -> {
            double brightness = val.doubleValue();
            applyBannerBrightness(brightness);

            LauncherSettings current = LauncherConfig.loadSettings();
            current.setBannerBrightness(brightness);
            LauncherConfig.saveSettings(current);
        });
    }

    /** Adjusts the dark/light overlay's opacity on top of the banner image — lower slider
     *  value = darker overlay (dimmer banner), higher = lighter overlay (brighter banner).
     *  1.0 removes the overlay entirely so the raw image shows at full brightness. */
    private void applyBannerBrightness(double brightness) {
        heroBannerOverlay.setOpacity(1.0 - brightness);
    }

    // ---------------- Uninstall data preference ----------------

    /** The installer (KPEClubLauncher.iss) reads this same setting from settings.json
     *  before showing its own Keep/Delete prompt on uninstall — if the player already
     *  chose here, the uninstaller skips asking and just does what was chosen. */
    private void setupLauncherUpdater() {
        if (checkUpdateButton != null) {
            checkUpdateButton.setOnAction(e -> handleCheckForUpdates(false));
        }
        if (applyLocalUpdateButton != null) {
            applyLocalUpdateButton.setOnAction(e -> {
                FileChooser fc = new FileChooser();
                fc.setTitle("Select KPE Club Launcher Setup .exe to Update");
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                        "KPE Club Launcher Installer (*.exe)", "KPE-Club-Launcher-Setup-*.exe", "*.exe"));
                java.io.File chosen = fc.showOpenDialog(playButton.getScene().getWindow());
                if (chosen == null) return;
                try {
                    updateStatusLabel.setText("Starting installer to update KPE Club Launcher...");
                    site.kpeclub.launcher.download.LauncherUpdater.runInstallerAndExit(chosen.toPath(), false);
                } catch (java.io.IOException ex) {
                    updateStatusLabel.setText("Could not launch installer: " + ex.getMessage());
                }
            });
        }
        handleCheckForUpdates(true);
    }

    private void handleCheckForUpdates(boolean silentStartupCheck) {
        if (!silentStartupCheck && checkUpdateButton != null) {
            checkUpdateButton.setDisable(true);
            updateStatusLabel.setText("Checking for updates...");
        }
        Task<site.kpeclub.launcher.download.LauncherUpdater.UpdateInfo> task = new Task<>() {
            @Override
            protected site.kpeclub.launcher.download.LauncherUpdater.UpdateInfo call() throws Exception {
                return site.kpeclub.launcher.download.LauncherUpdater.checkForUpdate();
            }
        };
        task.setOnSucceeded(e -> {
            if (checkUpdateButton != null) checkUpdateButton.setDisable(false);
            var info = task.getValue();
            if (info == null) {
                if (!silentStartupCheck && updateStatusLabel != null) {
                    updateStatusLabel.setText("You are running the latest version (v"
                            + site.kpeclub.launcher.download.LauncherUpdater.CURRENT_VERSION + ").");
                }
                return;
            }
            String msg = "Update available: v" + info.version()
                    + (info.notes().isBlank() ? "" : " — " + info.notes());
            if (updateStatusLabel != null) updateStatusLabel.setText(msg);
            consoleLabel.setText(msg + " (Go to Settings > Launcher Updates to install)");
            if (checkUpdateButton != null) {
                checkUpdateButton.setText("Download & Install v" + info.version());
                checkUpdateButton.setOnAction(ev -> downloadAndInstallLauncherUpdate(info));
            }
        });
        task.setOnFailed(e -> {
            if (checkUpdateButton != null) checkUpdateButton.setDisable(false);
            if (!silentStartupCheck && updateStatusLabel != null) {
                updateStatusLabel.setText("You are on v"
                        + site.kpeclub.launcher.download.LauncherUpdater.CURRENT_VERSION
                        + " (no online update manifest found; use 'Update from Setup .exe...' to install a newer build manually).");
            }
        });
        startDaemon(task, "kpe-update-check");
    }

    private void downloadAndInstallLauncherUpdate(site.kpeclub.launcher.download.LauncherUpdater.UpdateInfo info) {
        if (checkUpdateButton != null) checkUpdateButton.setDisable(true);
        if (updateStatusLabel != null) {
            updateStatusLabel.setText("Downloading KPE Club Launcher v" + info.version() + "...");
        }
        Task<java.nio.file.Path> dlTask = new Task<>() {
            @Override
            protected java.nio.file.Path call() throws Exception {
                return site.kpeclub.launcher.download.LauncherUpdater.downloadUpdateInstaller(
                        info,
                        p -> Platform.runLater(() -> {
                            if (updateStatusLabel != null) {
                                updateStatusLabel.setText(String.format(java.util.Locale.ROOT,
                                        "Downloading update v%s: %.0f%% (%.1f / %.1f MB)",
                                        info.version(), p.percent(), p.downloadedMB(), p.totalMB()));
                            }
                        }),
                        this::isCancelled);
            }
        };
        dlTask.setOnSucceeded(e -> {
            try {
                site.kpeclub.launcher.download.LauncherUpdater.runInstallerAndExit(dlTask.getValue(), true);
            } catch (java.io.IOException ex) {
                if (checkUpdateButton != null) checkUpdateButton.setDisable(false);
                if (updateStatusLabel != null) {
                    updateStatusLabel.setText("Downloaded update, but could not start installer: " + ex.getMessage());
                }
            }
        });
        dlTask.setOnFailed(e -> {
            if (checkUpdateButton != null) checkUpdateButton.setDisable(false);
            Throwable ex = dlTask.getException();
            if (updateStatusLabel != null) {
                updateStatusLabel.setText("Update download failed: " + (ex != null ? ex.getMessage() : "unknown error"));
            }
        });
        startDaemon(dlTask, "kpe-update-download");
    }

    private void setupUninstallDataComboBox() {
        uninstallDataComboBox.getItems().addAll("Ask me when uninstalling", "Keep my settings", "Delete my settings");

        LauncherSettings settings = LauncherConfig.loadSettings();
        String saved = settings.getUninstallDataPreference();
        if ("Keep".equals(saved)) {
            uninstallDataComboBox.getSelectionModel().select("Keep my settings");
        } else if ("Delete".equals(saved)) {
            uninstallDataComboBox.getSelectionModel().select("Delete my settings");
        } else {
            uninstallDataComboBox.getSelectionModel().select("Ask me when uninstalling");
        }

        uninstallDataComboBox.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            if (val == null) return;
            LauncherSettings current = LauncherConfig.loadSettings();
            if ("Keep my settings".equals(val)) {
                current.setUninstallDataPreference("Keep");
            } else if ("Delete my settings".equals(val)) {
                current.setUninstallDataPreference("Delete");
            } else {
                current.setUninstallDataPreference(null); // back to "ask every time"
            }
            LauncherConfig.saveSettings(current);
        });
    }

    private void updateWallpaperStatusLabel() {
        LauncherSettings settings = LauncherConfig.loadSettings();
        String customPath = settings.getCustomWallpaperPath();
        wallpaperStatusLabel.setText(customPath != null
                ? "Using custom wallpaper: " + java.nio.file.Paths.get(customPath).getFileName()
                : "Using default background.");
    }

    private void handleChangeWallpaper() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose Wallpaper Image");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Image files", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));

        javafx.stage.Stage stage = (javafx.stage.Stage) changeWallpaperButton.getScene().getWindow();
        java.io.File selected = chooser.showOpenDialog(stage);
        if (selected == null) return;

        try {
            java.nio.file.Files.createDirectories(LauncherConfig.WALLPAPER_DIR);
            // Copy into our own storage rather than referencing the original file's path
            // directly — the person could move/delete/rename the source file later, and
            // this way the wallpaper keeps working regardless of what they do with it.
            String extension = selected.getName().contains(".")
                    ? selected.getName().substring(selected.getName().lastIndexOf('.'))
                    : "";
            java.nio.file.Path dest = LauncherConfig.WALLPAPER_DIR.resolve("wallpaper" + extension);

            // Remove any previous custom wallpaper file (possibly a different extension)
            // before copying the new one, so old files don't pile up.
            if (java.nio.file.Files.exists(LauncherConfig.WALLPAPER_DIR)) {
                try (var stream = java.nio.file.Files.list(LauncherConfig.WALLPAPER_DIR)) {
                    stream.forEach(p -> {
                        try { java.nio.file.Files.deleteIfExists(p); } catch (java.io.IOException ignored) {}
                    });
                }
            }

            java.nio.file.Files.copy(selected.toPath(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            LauncherSettings settings = LauncherConfig.loadSettings();
            settings.setCustomWallpaperPath(dest.toAbsolutePath().toString());
            LauncherConfig.saveSettings(settings);

            loadHeroBannerImage();
            updateWallpaperStatusLabel();
            statusLabel.setText("Wallpaper updated.");
        } catch (java.io.IOException e) {
            statusLabel.setText("Could not set wallpaper: " + e.getMessage());
        }
    }

    private void handleResetWallpaper() {
        LauncherSettings settings = LauncherConfig.loadSettings();
        settings.setCustomWallpaperPath(null);
        LauncherConfig.saveSettings(settings);

        try {
            if (java.nio.file.Files.exists(LauncherConfig.WALLPAPER_DIR)) {
                try (var stream = java.nio.file.Files.list(LauncherConfig.WALLPAPER_DIR)) {
                    stream.forEach(p -> {
                        try { java.nio.file.Files.deleteIfExists(p); } catch (java.io.IOException ignored) {}
                    });
                }
            }
        } catch (java.io.IOException ignored) {
            // Not critical if cleanup fails — the setting is already cleared, which is what matters.
        }

        // Remove the current banner ImageView so the default (bundled banner.png, or the
        // CSS gradient if that's also missing) shows through again.
        heroBannerPane.getChildren().removeIf(node -> node instanceof javafx.scene.image.ImageView);
        loadHeroBannerImage();
        updateWallpaperStatusLabel();
        statusLabel.setText("Wallpaper reset to default.");
    }

    /** Reads current width/height fields, falling back to 1280x720 if invalid. */
    private int[] getResolution() {
        normalizeResolutionField(widthField, 1280, 320, 7680);
        normalizeResolutionField(heightField, 720, 240, 4320);
        saveResolutionSettings();
        int width = 1280;
        int height = 720;
        try {
            width = Integer.parseInt(widthField.getText().trim());
            height = Integer.parseInt(heightField.getText().trim());
        } catch (NumberFormatException ignored) {
        }
        width = Math.max(320, Math.min(7680, width));
        height = Math.max(240, Math.min(4320, height));
        return new int[]{width, height};
    }

    // ---------------- Versions ----------------

    private void loadVersions() {
        LauncherSettings savedSettings = LauncherConfig.loadSettings();
        final String targetSavedVersion = savedSettings.getLastSelectedVersion();
        final String targetSavedLoader = savedSettings.getLastSelectedModLoader();

        suppressSelectionSave = true;
        List<String> localVersions;
        try {
            versionComboBox.getItems().clear();

            // Always show locally installed versions immediately — don't wait on network.
            localVersions = scanLocallyCachedVersions();
            localVersions.forEach(id -> versionComboBox.getItems().add(id));
            if (!localVersions.isEmpty()) {
                versionComboBox.getSelectionModel().selectFirst();
                consoleLabel.setText(localVersions.size() + " locally installed version(s) found.");
            }
        } finally {
            suppressSelectionSave = false;
        }
        restoreSelectedVersionAndLoader(targetSavedVersion, targetSavedLoader);

        Task<List<VersionEntry>> task = new Task<>() {
            @Override
            protected List<VersionEntry> call() throws Exception {
                return new VersionManifest().fetchVersionList();
            }
        };
        task.setOnSucceeded(e -> {
            List<VersionEntry> versions = task.getValue();
            LauncherSettings vs = LauncherConfig.loadSettings();
            String currentSelected = versionComboBox.getSelectionModel().getSelectedItem();
            String currentOrSavedVersion = currentSelected != null
                    ? currentSelected
                    : (targetSavedVersion != null ? targetSavedVersion : vs.getLastSelectedVersion());
            String currentOrSavedLoader = vs.getLastSelectedModLoader() != null
                    ? vs.getLastSelectedModLoader() : targetSavedLoader;

            suppressSelectionSave = true;
            try {
                // Mojang's own order first (newest first); versions that exist only on this PC go last.
                String keep = versionComboBox.getSelectionModel().getSelectedItem();
                List<String> ordered = new ArrayList<>();
                versions.stream()
                        .filter(v -> v.type().equals("release")
                                || (vs.isShowSnapshots() && v.type().equals("snapshot"))
                                || (vs.isShowOldVersions() && v.type().startsWith("old_")))
                        .map(VersionEntry::id)
                        .forEach(ordered::add);
                for (String local : new ArrayList<>(versionComboBox.getItems())) {
                    if (!ordered.contains(local)) ordered.add(local);
                }
                versionComboBox.getItems().setAll(ordered);
                if (keep != null && ordered.contains(keep)) versionComboBox.getSelectionModel().select(keep);
                if (versionComboBox.getSelectionModel().getSelectedItem() == null
                        && !versionComboBox.getItems().isEmpty()) {
                    versionComboBox.getSelectionModel().selectFirst();
                }
            } finally {
                suppressSelectionSave = false;
            }
            restoreSelectedVersionAndLoader(currentOrSavedVersion, currentOrSavedLoader); // in case the saved version was only in the online list
            if ("Fabric".equals(modLoaderComboBox.getSelectionModel().getSelectedItem())) {
                loadFabricLoaderVersions();
            }
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            String reason = ex != null && ex.getMessage() != null ? " (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")" : "";
            if (localVersions.isEmpty()) {
                statusLabel.setText("No internet. Connect to the internet once to download Minecraft.");
            } else {
                statusLabel.setText("No internet. Showing only the versions already on this PC.");
            }
            if (!reason.isEmpty()) {
                consoleLabel.setText("Network check failed" + reason);
            }
        });
        Thread t = new Thread(task, "kpe-version-manifest");
        t.setDaemon(true);
        t.start();
    }

    /** Newest first by NUMBERS, so 1.21.1 comes before 1.9.4 (plain text order gets this wrong). */
    static int compareVersionIdsDesc(String a, String b) {
        return -compareVersionIds(a, b);
    }

    static int compareVersionIds(String a, String b) {
        String[] pa = a.split("[.\\-]"), pb = b.split("[.\\-]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            boolean missX = i >= pa.length, missY = i >= pb.length;
            String x = missX ? "" : pa[i], y = missY ? "" : pb[i];
            boolean nx = x.matches("\\d+"), ny = y.matches("\\d+");
            int c;
            if (missX || missY) {
                // Shorter id vs longer id: 1.20.1 is newer than 1.20, but 1.20 is newer than 1.20-pre1.
                boolean extraIsNumber = missX ? ny : nx;
                c = extraIsNumber ? (missX ? -1 : 1) : (missX ? 1 : -1);
            } else if (nx && ny) c = Long.compare(Long.parseLong(x), Long.parseLong(y));
            else if (nx != ny) c = nx ? 1 : -1;       // 1.20 is newer than 1.20-pre1
            else c = x.compareTo(y);
            if (c != 0) return c;
        }
        return 0;
    }

    /** Scans .minecraft/versions/ for already-installed versions (from this launcher OR the official one). */
    private List<String> scanLocallyCachedVersions() {
        List<String> cached = new ArrayList<>();
        try {
            if (!java.nio.file.Files.exists(LauncherConfig.VERSIONS_DIR)) return cached;
            try (var stream = java.nio.file.Files.list(LauncherConfig.VERSIONS_DIR)) {
                stream.filter(java.nio.file.Files::isDirectory)
                        .forEach(dir -> {
                            String id = dir.getFileName().toString();
                            java.nio.file.Path json = dir.resolve(id + ".json");
                            if (!isValidCachedVersionJson(json)) return;
                            if (id.startsWith("fabric-loader-")) {
                                String mcVer = fabricMcVersion(id);
                                if (mcVer != null && !mcVer.isBlank() && !cached.contains(mcVer)) {
                                    cached.add(mcVer);
                                }
                            } else if (java.nio.file.Files.exists(dir.resolve(id + ".jar"))) {
                                if (!cached.contains(id)) cached.add(id);
                            }
                        });
            }
            cached.sort(MainController::compareVersionIdsDesc);
        } catch (java.io.IOException ignored) {}
        return cached;
    }

    // ---------------- Login ----------------

    private void handleSignOut() {
        session = null;
        isOnlineSession = false;
        usernameField.setDisable(false);
        loginButton.setText("Microsoft login (optional)");
        statusLabel.setText("Signed out.");
        consoleLabel.setText("Signed out.");

        // Fall back to the offline username field if it has something in it, matching
        // TLauncher's behavior of always having a usable session by default.
        if (usernameField.getText() != null && !usernameField.getText().isBlank()) {
            commitUsernameField();
        } else {
            accountLabel.setText("Not signed in");
            accountStatusLabel.setText("offline");
            playButton.setDisable(true);
        }
    }

    private void handleLogin() {
        loginButton.setDisable(true);
        statusLabel.setText("Opening your browser to sign in...");

        Task<MinecraftSession> task = new Task<>() {
            @Override
            protected MinecraftSession call() throws Exception {
                return new MicrosoftAuth().login();
            }
        };
        task.setOnSucceeded(e -> {
            session = task.getValue();
            isOnlineSession = true;
            usernameField.setDisable(true);
            accountLabel.setText(session.username());
            accountStatusLabel.setText("online");
            loginButton.setText("Sign out");
            loginButton.setDisable(false);
            playButton.setDisable(false);
            statusLabel.setText("Signed in as " + session.username());
            consoleLabel.setText("Signed in as " + session.username());

            // A real login takes priority — clear the saved test-mode username so it
            // doesn't silently auto-activate over this real session on next restart.
            LauncherSettings settings = LauncherConfig.loadSettings();
            settings.setLastTestModeUsername(null);
            LauncherConfig.saveSettings(settings);
        });
        task.setOnFailed(e -> {
            loginButton.setDisable(false);
            String msg = task.getException() != null ? task.getException().getMessage() : "Unknown error";
            statusLabel.setText("Login failed: " + msg);
        });
        startDaemon(task, "kpe-ms-login");
    }

    // ---------------- Test Mode (no auth) ----------------
    /** Restores the last-used offline username on launcher startup, pre-filling the field
     *  and activating the session — TLauncher-style, no dialog required. */
    private void autoActivateTestModeIfSaved() {
        LauncherSettings settings = LauncherConfig.loadSettings();
        String lastUsername = settings.getLastTestModeUsername();
        if (lastUsername != null && !lastUsername.isBlank()) {
            usernameField.setText(lastUsername);
            activateTestModeSession(lastUsername, false); // false: don't re-save, it's already saved
        }
    }

    /** Commits whatever's currently typed in the username field as the active offline
     *  session — TLauncher-style, no dialog. Called on Enter or when the field loses focus. */
    /** Background work must never keep the program alive after the window is closed. */
    private static void startDaemon(Runnable work, String name) {
        Thread t = new Thread(work, name);
        t.setDaemon(true);
        t.start();
    }

    private void onUsernameTextChanged(String newText) {
        if (isOnlineSession) return;
        if (newText == null || newText.isBlank()) {
            session = null;
            accountLabel.setText("Not signed in");
            accountStatusLabel.setText("offline");
            playButton.setDisable(true);
            statusLabel.setText("Type your name to start.");
            return;
        }
        String trimmed = newText.trim();
        if (site.kpeclub.launcher.util.Safe.isValidUsername(trimmed)) {
            activateTestModeSession(trimmed, false);
        } else {
            session = null;
            playButton.setDisable(true);
            statusLabel.setText("Your name must be 3 to 16 letters, numbers or _ (no spaces).");
        }
    }

    private void commitUsernameField() {
        if (isOnlineSession) return; // Don't overwrite an active Microsoft session when usernameField loses focus
        String username = usernameField.getText();
        if (username == null || username.isBlank()) {
            // Name box was emptied: do not keep playing as the old name.
            session = null;
            accountLabel.setText("Not signed in");
            accountStatusLabel.setText("offline");
            playButton.setDisable(true);
            statusLabel.setText("Type your name to start.");
            return;
        }
        String trimmed = username.trim();
        if (session != null && !isOnlineSession && trimmed.equals(session.username())) {
            LauncherSettings cur = LauncherConfig.loadSettings();
            if (!trimmed.equals(cur.getLastTestModeUsername())) {
                cur.setLastTestModeUsername(trimmed);
                LauncherConfig.saveSettings(cur);
            }
            return;
        }
        activateTestModeSession(trimmed, true);
    }

    /** Builds and activates an offline session for the given username.
     *  @param persist whether to save this username as the "last used" one for next launch. */
    private void activateTestModeSession(String username, boolean persist) {
        if (!site.kpeclub.launcher.util.Safe.isValidUsername(username)) {
            session = null;
            isOnlineSession = false;
            playButton.setDisable(true);
            statusLabel.setText("Your name must be 3 to 16 letters, numbers or _ (no spaces).");
            return;
        }
        // Offline-style UUID derivation (same scheme vanilla MC uses for offline-mode)
        String offlineUuid = java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ).toString();

        session = new MinecraftSession(username, offlineUuid, "TEST_MODE_NO_TOKEN");
        isOnlineSession = false;
        accountLabel.setText(username + " (Offline)");
        accountStatusLabel.setText("offline");
        // Deliberately not disabling loginButton — the person can still click "Sign in"
        // to log in with a real Microsoft account, or just edit the username field again,
        // without needing to sign out first.
        loginButton.setText("Microsoft login (optional)");
        loginButton.setDisable(false);
        playButton.setDisable(false);
        statusLabel.setText("Ready! You will play as " + username + ". Press PLAY.");
        consoleLabel.setText("Playing offline as " + username);

        if (persist) {
            LauncherSettings settings = LauncherConfig.loadSettings();
            settings.setLastTestModeUsername(username);
            LauncherConfig.saveSettings(settings);
        }
    }



    private void handlePlay() {
        if (!isOnlineSession) {
            commitUsernameField();
        }
        if (session == null) {
            statusLabel.setText("Type your name first, then press PLAY.");
            return;
        }
        String baseVersionId = versionComboBox.getSelectionModel().getSelectedItem();
        if (baseVersionId == null) {
            statusLabel.setText("Choose a Minecraft version first.");
            return;
        }

        if (runningGameProcess != null && runningGameProcess.isAlive()) {
            Alert warn = new Alert(Alert.AlertType.CONFIRMATION);
            warn.setTitle("Minecraft is already running");
            warn.setHeaderText("A Minecraft instance launched from this launcher is still running.");
            warn.setContentText("Starting another one at the same time can cause lag, world " +
                    "save conflicts, or other issues. Launch anyway?");
            Optional<ButtonType> result = warn.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) {
                statusLabel.setText("Launch cancelled — another instance is already running.");
                return;
            }
        }

        String modLoader = modLoaderComboBox.getSelectionModel().getSelectedItem();
        boolean useFabric = "Fabric".equals(modLoader);

        playButton.setDisable(true);
        downloadProgress.setVisible(true);
        downloadProgress.setManaged(true);
        downloadProgress.setProgress(0);
        progressDetailLabel.setVisible(true);
        progressDetailLabel.setManaged(true);
        progressDetailLabel.setText("");
        cancelButton.setVisible(true);
        cancelButton.setManaged(true);
        cancelButton.setDisable(false);
        statusLabel.setText("Preparing " + baseVersionId + (useFabric ? " (Fabric)" : "") + "...");

        // Read resolution now, on the FX thread — the Task below runs on a background
        // thread and must not touch JavaFX controls directly.
        final int[] resolution = getResolution();
        final String selectedFabricLoaderVersion = useFabric ? selectedFabricLoaderVersion() : null;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                // Resolve the ACTUAL version id we'll download/launch. For vanilla this is
                // just baseVersionId. For Fabric it's something like
                // "fabric-loader-0.16.9-1.21.1", decided by the specific loader version the
                // person picked in the Fabric loader-version dropdown (or the newest available /
                // locally cached build if none was explicitly chosen).
                final String[] resolvedVersionIdHolder = { baseVersionId };
                JsonObject resolvedVersionJson = null;

                if (useFabric) {
                    FabricInstaller fabric = new FabricInstaller();
                    String loaderVersion = selectedFabricLoaderVersion;
                    if (loaderVersion == null) {
                        try {
                            var loaderVersions = fabric.fetchLoaderVersions(baseVersionId);
                            if (!loaderVersions.isEmpty()) {
                                loaderVersion = loaderVersions.get(0).version();
                            }
                        } catch (Exception offlineOrError) {
                            String cachedFabricId = findCachedFabricVersion(baseVersionId);
                            if (cachedFabricId != null) {
                                resolvedVersionIdHolder[0] = cachedFabricId;
                            } else {
                                throw offlineOrError;
                            }
                        }
                        if (loaderVersion == null && resolvedVersionIdHolder[0].equals(baseVersionId)) {
                            String cachedFabricId = findCachedFabricVersion(baseVersionId);
                            if (cachedFabricId != null) {
                                resolvedVersionIdHolder[0] = cachedFabricId;
                            } else {
                                throw new IllegalStateException("No Fabric loader available for " + baseVersionId);
                            }
                        }
                    }

                    if (loaderVersion != null) {
                        String expectedFabricId = "fabric-loader-" + loaderVersion + "-" + baseVersionId;
                        boolean thisExactBuildCached = isValidCachedVersionJson(
                                LauncherConfig.VERSIONS_DIR.resolve(expectedFabricId).resolve(expectedFabricId + ".json"));

                        if (thisExactBuildCached) {
                            resolvedVersionIdHolder[0] = expectedFabricId;
                        } else {
                            Platform.runLater(() -> consoleLabel.setText("Fetching Fabric loader info..."));

                            VersionManifest manifest = new VersionManifest();
                            VersionEntry vanillaEntry = manifest.fetchVersionList().stream()
                                    .filter(v -> v.id().equals(baseVersionId))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalStateException("Version not found: " + baseVersionId));
                            JsonObject vanillaJson = manifest.fetchVersionJson(vanillaEntry.url());
                            JsonObject fabricProfile = fabric.fetchFabricProfile(baseVersionId, loaderVersion);

                            resolvedVersionJson = fabric.buildMergedVersionJson(vanillaJson, fabricProfile);
                            resolvedVersionIdHolder[0] = resolvedVersionJson.get("id").getAsString();
                        }
                    }
                }
                String resolvedVersionId = resolvedVersionIdHolder[0];

                java.nio.file.Path cachedVersionJson = LauncherConfig.VERSIONS_DIR
                        .resolve(resolvedVersionId).resolve(resolvedVersionId + ".json");
                java.nio.file.Path cachedClientJar = LauncherConfig.VERSIONS_DIR
                        .resolve(resolvedVersionId).resolve(resolvedVersionId + ".jar");
                boolean hasValidJson = java.nio.file.Files.exists(cachedVersionJson)
                        && isValidCachedVersionJson(cachedVersionJson);
                boolean hasJarOrInheritedJar = java.nio.file.Files.exists(cachedClientJar);
                if (!hasJarOrInheritedJar && hasValidJson) {
                    try {
                        JsonObject onDisk = com.google.gson.JsonParser
                                .parseString(java.nio.file.Files.readString(cachedVersionJson)).getAsJsonObject();
                        if (onDisk.has("inheritsFrom")) {
                            JsonObject resolved = site.kpeclub.launcher.util.VersionInheritanceResolver
                                    .resolve(onDisk, LauncherConfig.VERSIONS_DIR);
                            if (resolved != null && resolved.has("inheritsFromResolvedParentId")) {
                                String parentId = resolved.get("inheritsFromResolvedParentId").getAsString();
                                hasJarOrInheritedJar = java.nio.file.Files.exists(
                                        LauncherConfig.VERSIONS_DIR.resolve(parentId).resolve(parentId + ".jar"));
                            }
                        }
                    } catch (Exception ignored) {}
                }
                boolean alreadyCached = hasValidJson && hasJarOrInheritedJar;

                final JavaChoice javaChoice = JavaChoice.from(LauncherConfig.loadSettings());
                final PlatformInfo.Arch arch = javaChoice.arch();
                boolean complete = alreadyCached && GameDownloader.isFullyDownloaded(resolvedVersionId, arch);

                if (complete) {
                    // Fully downloaded for this CPU — launch straight from disk, no network needed.
                    Platform.runLater(() -> {
                        statusLabel.setText(resolvedVersionId + " is ready.");
                        consoleLabel.setText("Launching " + resolvedVersionId + " from local files.");
                    });
                } else {
                    JsonObject versionJson = resolvedVersionJson;
                    boolean repairOnly = false;
                    if (versionJson == null && alreadyCached) {
                        // Installed before (or by another launcher) but never verified: check the files
                        // using the version file already on disk. Works offline if nothing is missing.
                        JsonObject onDisk = com.google.gson.JsonParser
                                .parseString(java.nio.file.Files.readString(cachedVersionJson)).getAsJsonObject();
                        if (onDisk.has("inheritsFrom")) {
                            JsonObject resolved = site.kpeclub.launcher.util.VersionInheritanceResolver
                                    .resolve(onDisk, LauncherConfig.VERSIONS_DIR);
                            if (resolved != null) {
                                versionJson = resolved;
                                repairOnly = true;
                            }
                        } else {
                            versionJson = onDisk;
                            repairOnly = true;
                        }
                    }
                    if (versionJson == null && !alreadyCached) {
                        VersionManifest manifest = new VersionManifest();
                        VersionEntry entry = manifest.fetchVersionList().stream()
                                .filter(v -> v.id().equals(baseVersionId))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException(
                                        "Version not found and no internet connection to look it up: " + baseVersionId));
                        versionJson = manifest.fetchVersionJson(entry.url());
                    }

                    if (versionJson != null) {
                        try {
                            new GameDownloader().downloadVersion(
                                    versionJson, arch,
                                    progress -> Platform.runLater(() -> {
                                        downloadProgress.setProgress(progress.fraction());
                                        String detail = String.format(java.util.Locale.ROOT,
                                                "%.0f%%  -  %.1f / %.1f MB  (%s)",
                                                progress.percent(), progress.downloadedMB(), progress.totalMB(),
                                                progress.currentFile());
                                        progressDetailLabel.setText(detail);
                                        consoleLabel.setText(detail);
                                    }),
                                    this::isCancelled);
                        } catch (java.io.IOException netError) {
                            if (!repairOnly) throw netError;
                            // Could not verify (no internet) — try launching with what is on disk.
                            Platform.runLater(() -> consoleLabel.setText(
                                    "Could not verify files (offline?) — trying to launch anyway."));
                        }
                    }
                }

                if (isCancelled()) return null;

                // Read the version json fresh from disk (whether just downloaded or already
                // cached) so we know which Java runtime component this version needs.
                java.nio.file.Path finalVersionJsonPath = LauncherConfig.VERSIONS_DIR
                        .resolve(resolvedVersionId).resolve(resolvedVersionId + ".json");
                JsonObject finalVersionJson = com.google.gson.JsonParser
                        .parseString(java.nio.file.Files.readString(finalVersionJsonPath)).getAsJsonObject();

                // Resolve inheritsFrom (Fabric-via-installer, OptiFine, Forge) so javaVersion
                // is read from the right place — modern MC's javaVersion.component can live
                // on either the child or parent depending on the loader.
                JsonObject javaVersionSource = finalVersionJson;
                if (finalVersionJson.has("inheritsFrom")) {
                    JsonObject resolvedForJava = site.kpeclub.launcher.util.VersionInheritanceResolver
                            .resolve(finalVersionJson, LauncherConfig.VERSIONS_DIR);
                    if (resolvedForJava != null) javaVersionSource = resolvedForJava;
                }

                ensureJavaFor(resolvedVersionId, javaChoice, javaVersionSource, this::isCancelled);

                if (isCancelled()) return null;

                if (!GameLauncher.isLegacy1165OrOlder(resolvedVersionId, javaVersionSource)) {
                    ensureMesaIfBypassEnabled(arch, this::isCancelled);
                }

                if (isCancelled()) return null;

                Platform.runLater(() -> statusLabel.setText("Launching..."));

                String host = null;
                Integer port = null;

                Process launchedProcess = new GameLauncher().launch(
                        resolvedVersionId, session, host, port, resolution[0], resolution[1], javaChoice);
                runningGameProcess = launchedProcess;
                watchForCrash(launchedProcess);
                return null;
            }
        };

        currentDownloadTask = task;

        task.setOnSucceeded(e -> {
            statusLabel.setText("Minecraft is starting... this can take a minute.");
            consoleLabel.setText("Minecraft launched (" + GameLauncher.lastJavaUsed() + ").");
            resetDownloadUi();
            hideLauncherWhileGameRunningIfConfigured();
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            String msg = ex != null ? ex.getMessage() : "Unknown error";
            statusLabel.setText("Could not start Minecraft: " + msg);
            consoleLabel.setText("Launch failed: " + msg);
            resetDownloadUi();
        });
        task.setOnCancelled(e -> {
            statusLabel.setText("Download cancelled.");
            consoleLabel.setText("Download cancelled.");
            resetDownloadUi();
        });

        startDaemon(task, "kpe-play");
    }

    private void hideLauncherWhileGameRunningIfConfigured() {
        if (!keepOpenCheckBox.isSelected() && runningGameProcess != null) {
            javafx.stage.Stage stage = (javafx.stage.Stage) playButton.getScene().getWindow();
            stage.hide();

            Process watchedProcess = runningGameProcess;
            Thread watcher = new Thread(() -> {
                int exitCode = 0;
                try {
                    exitCode = watchedProcess.waitFor(); // blocks until the game process exits
                } catch (InterruptedException ignored) {
                    // launcher shutting down — nothing more to do
                }
                final int code = exitCode;
                Platform.runLater(() -> {
                    if (watchedProcess == runningGameProcess) { // still the most recent launch
                        stage.show();
                        if (code == 0) { // on a crash, the crash dialog sets its own message
                            statusLabel.setText("Minecraft closed.");
                            consoleLabel.setText("Minecraft closed.");
                        }
                    }
                });
            }, "kpe-hide-watch");
            watcher.setDaemon(true);
            watcher.start();
        }
    }

    /** Checks a cached version json actually has what GameLauncher needs, so a stale or
     *  partially-written file from a previous failed run isn't mistaken for a good cache.
     *  Also accepts "inheritsFrom"-style files (Fabric Installer, Forge, etc via the official
     *  launcher) as long as their parent version is present and itself resolvable. */
    private boolean isValidCachedVersionJson(java.nio.file.Path path) {
        try {
            String content = java.nio.file.Files.readString(path);
            JsonObject json = com.google.gson.JsonParser.parseString(content).getAsJsonObject();

            if (json.has("downloads") && json.getAsJsonObject("downloads").has("client")
                    && json.has("mainClass") && json.has("assetIndex")) {
                return true; // self-contained version json (our own downloads, or Fabric via meta API)
            }

            if (json.has("inheritsFrom")) {
                JsonObject resolved = site.kpeclub.launcher.util.VersionInheritanceResolver
                        .resolve(json, LauncherConfig.VERSIONS_DIR);
                return resolved != null
                        && resolved.has("downloads") && resolved.getAsJsonObject("downloads").has("client")
                        && resolved.has("mainClass") && resolved.has("assetIndex");
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void handleCancelDownload() {
        if (currentDownloadTask != null && currentDownloadTask.isRunning()) {
            cancelButton.setDisable(true); // avoid double-clicks while it winds down
            statusLabel.setText("Cancelling...");
            currentDownloadTask.cancel();
        }
    }

    /**
     * Looks for an already-downloaded Fabric version folder for the given base Minecraft
     * version (e.g. "1.21.1" -> "fabric-loader-0.16.9-1.21.1"), so a repeat launch while
     * offline doesn't need to hit Fabric's meta API at all.
     */
    private String findCachedFabricVersion(String baseVersionId) {
        try {
            if (!java.nio.file.Files.exists(LauncherConfig.VERSIONS_DIR)) return null;
            try (var stream = java.nio.file.Files.list(LauncherConfig.VERSIONS_DIR)) {
                return stream.filter(java.nio.file.Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(id -> id.startsWith("fabric-loader-") && id.endsWith("-" + baseVersionId))
                        .filter(id -> isValidCachedVersionJson(
                                LauncherConfig.VERSIONS_DIR.resolve(id).resolve(id + ".json")))
                        .findFirst()
                        .orElse(null);
            }
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Makes sure Mojang's Java for this version (matching 32/64-bit) is downloaded.
     *  Does nothing if the player picked their own Java in Settings. */
    private void ensureJavaFor(String versionId, JavaChoice choice, JsonObject versionJsonOrNull,
                               java.util.function.BooleanSupplier cancelled) throws Exception {
        JsonObject src = versionJsonOrNull;
        if (src == null) {
            src = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                    LauncherConfig.VERSIONS_DIR.resolve(versionId).resolve(versionId + ".json"))).getAsJsonObject();
            if (src.has("inheritsFrom")) {
                JsonObject r = site.kpeclub.launcher.util.VersionInheritanceResolver
                        .resolve(src, LauncherConfig.VERSIONS_DIR);
                if (r != null) src = r;
            }
        }
        boolean legacy1165 = GameLauncher.isLegacy1165OrOlder(versionId, src);
        if (choice.customJava() != null) {
            String customStr = choice.customJava().toString().toLowerCase(java.util.Locale.ROOT);
            if (!customStr.contains("jre-shim-")) {
                if (!legacy1165) return;
                site.kpeclub.launcher.util.JavaProbe.Info info =
                        site.kpeclub.launcher.util.JavaProbe.probe(choice.customJava());
                if (info != null && info.majorVersion() <= 8) return;
            }
        }
        var jre = new site.kpeclub.launcher.download.JreManager();
        String component = jre.requiredComponent(src);
        // Java already on this PC (ours, the official launcher's, or a system Java)? Then nothing to download.
        if (jre.findExistingJava(src, choice.arch()) != null) return;
        Platform.runLater(() -> {
            statusLabel.setText("Downloading Java (" + component + ", " + choice.arch().bits + "-bit)...");
            consoleLabel.setText("Downloading Java runtime: " + component);
        });
        jre.downloadComponentIfMissing(component, choice.arch(), progress -> Platform.runLater(() -> {
            downloadProgress.setProgress(progress.fraction());
            String detail = String.format(java.util.Locale.ROOT, "Java: %.0f%%  -  %.1f / %.1f MB",
                    progress.percent(), progress.downloadedMB(), progress.totalMB());
            progressDetailLabel.setText(detail);
            consoleLabel.setText(detail);
        }), cancelled);
    }

    /** Watches the game; if it exits with an error, shows the last lines so the player (and you)
     *  can see why instead of the game silently vanishing. */
    private void watchForCrash(Process process) {
        Thread t = new Thread(() -> {
            try {
                int code = process.waitFor();
                if (code == 0) return;
                java.util.List<String> lines = GameLauncher.lastOutput();
                Platform.runLater(() -> {
                    if (!playButton.getScene().getWindow().isShowing()) {
                        ((javafx.stage.Stage) playButton.getScene().getWindow()).show();
                    }
                    showCrashDialog(code, lines);
                });
            } catch (InterruptedException ignored) {
                // launcher closing
            }
        }, "kpe-crash-watch");
        t.setDaemon(true);
        t.start();
    }

    private void ensureMesaIfBypassEnabled(PlatformInfo.Arch arch,
                                           java.util.function.BooleanSupplier cancelled) throws Exception {
        if (!LauncherConfig.loadSettings().isBypassDriverCheck()) return;
        if (site.kpeclub.launcher.launch.FpsBoost.isMesaInstalled(arch)) {
            Platform.runLater(this::updateMesaButtonLabel);
            return;
        }
        Platform.runLater(() -> {
            statusLabel.setText("Downloading Mesa3D OpenGL bypass for your GPU...");
            consoleLabel.setText("Downloading Mesa3D OpenGL software driver...");
        });
        site.kpeclub.launcher.launch.FpsBoost.ensureMesaInstalled(arch, progress -> Platform.runLater(() -> {
            downloadProgress.setProgress(progress.fraction());
            String detail = String.format(java.util.Locale.ROOT,
                    "Mesa3D OpenGL: %.0f%%  -  %.1f / %.1f MB",
                    progress.percent(), progress.downloadedMB(), progress.totalMB());
            progressDetailLabel.setText(detail);
            consoleLabel.setText(detail);
        }), cancelled);
        Platform.runLater(this::updateMesaButtonLabel);
    }

    private void autoDownloadMesaFromSettings() {
        PlatformInfo.Arch arch = PlatformInfo.minecraftArch();
        if (importMesaButton != null) importMesaButton.setDisable(true);
        fpsHintLabel.setText("Downloading Mesa3D OpenGL software driver for " + arch.bits + "-bit Java...");
        Task<Void> mesaTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                site.kpeclub.launcher.launch.FpsBoost.ensureMesaInstalled(arch, p -> Platform.runLater(() ->
                        fpsHintLabel.setText(String.format(java.util.Locale.ROOT,
                                "Downloading Mesa3D OpenGL driver: %.0f%% (%.1f / %.1f MB)...",
                                p.percent(), p.downloadedMB(), p.totalMB()))
                ), this::isCancelled);
                return null;
            }
        };
        mesaTask.setOnSucceeded(ev -> {
            if (importMesaButton != null) importMesaButton.setDisable(false);
            updateMesaButtonLabel();
            fpsHintLabel.setText("Mesa3D OpenGL driver installed ✓ — ready to launch Minecraft on old GPUs!");
        });
        mesaTask.setOnFailed(ev -> {
            if (importMesaButton != null) importMesaButton.setDisable(false);
            updateMesaButtonLabel();
            Throwable ex = mesaTask.getException();
            fpsHintLabel.setText("Could not auto-download Mesa3D ("
                    + (ex != null ? ex.getMessage() : "check internet")
                    + "). Click 'Download / Load Mesa3D' to retry or select a local opengl32.dll.");
        });
        startDaemon(mesaTask, "kpe-mesa-download");
    }

    private void updateMesaButtonLabel() {
        if (importMesaButton == null) return;
        boolean hasMesa = site.kpeclub.launcher.launch.FpsBoost.isMesaInstalled(PlatformInfo.minecraftArch());
        importMesaButton.setText(hasMesa ? "Mesa3D Installed ✓" : "Download / Load Mesa3D");
    }

    private void showCrashDialog(int code, java.util.List<String> lines) {
        String joined = String.join("\n", lines);
        boolean glDriverCrash = joined.contains("65542") || joined.contains("65543")
                || joined.contains("The driver does not appear to support OpenGL")
                || joined.contains("Pixel format not accelerated")
                || joined.contains("WGL_ARB_create_context");

        if (glDriverCrash) {
            LauncherSettings cur = LauncherConfig.loadSettings();
            if (!cur.isBypassDriverCheck()) {
                cur.setBypassDriverCheck(true);
                LauncherConfig.saveSettings(cur);
            }
            if (driverBypassCheckBox != null) {
                driverBypassCheckBox.setVisible(true);
                driverBypassCheckBox.setManaged(true);
                driverBypassCheckBox.setSelected(true);
            }
            if (importMesaButton != null) {
                importMesaButton.setVisible(true);
                importMesaButton.setManaged(true);
            }
        }

        statusLabel.setText("Minecraft closed with an error (code " + code + ").");
        consoleLabel.setText("Minecraft closed with an error (code " + code + ").");
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Minecraft stopped");
        alert.setHeaderText(glDriverCrash
                ? "GPU / OpenGL Driver Unsupported (Code " + code + ")"
                : "Minecraft closed with an error (code " + code + ")");
        alert.setContentText(glDriverCrash
                ? "Your graphics driver does not natively support the OpenGL version requested by Minecraft.\n"
                  + "• 'Bypass unsupported GPU driver' is now enabled in Settings > Performance.\n"
                  + "• Click PLAY again and the launcher will automatically download and attach the Mesa3D OpenGL software driver for your PC.\n\n"
                  + "Full log: " + GameLauncher.logFile()
                : "Full log: " + GameLauncher.logFile());
        TextArea area = new TextArea(String.join("\n", lines));
        area.setEditable(false);
        area.setPrefRowCount(14);
        alert.getDialogPane().setExpandableContent(area);
        alert.getDialogPane().getStylesheets().addAll(playButton.getScene().getStylesheets());
        alert.show();
    }

    private void resetDownloadUi() {
        downloadProgress.setVisible(false);
        downloadProgress.setManaged(false);
        progressDetailLabel.setVisible(false);
        progressDetailLabel.setManaged(false);
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        playButton.setDisable(false);
    }
}
