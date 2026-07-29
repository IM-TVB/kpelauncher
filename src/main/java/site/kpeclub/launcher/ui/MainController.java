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
import site.kpeclub.launcher.model.LauncherSettings;
import site.kpeclub.launcher.model.ServerEntry;
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
    @FXML private javafx.scene.layout.VBox settingsView;
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
    @FXML private CheckBox keepOpenCheckBox;
    @FXML private ComboBox<String> themeComboBox;
    @FXML private Label wallpaperStatusLabel;
    @FXML private Button changeWallpaperButton;
    @FXML private Button resetWallpaperButton;
    @FXML private Slider bannerBrightnessSlider;
    @FXML private ComboBox<String> uninstallDataComboBox;
    @FXML private ComboBox<ServerEntry> serverComboBox;
    @FXML private Button addServerButton;
    @FXML private Button removeServerButton;
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
    private ServerEntry selectedServer;
    private Task<Void> currentDownloadTask; // tracked so Cancel can stop it
    private Process runningGameProcess; // tracked so we can warn about launching a second instance

    @FXML
    public void initialize() {
        playButton.setDisable(true);
        loadServers();
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
        modLoaderComboBox.getItems().addAll("Vanilla", "Fabric");
        modLoaderComboBox.getSelectionModel().selectFirst();
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

        loginButton.setOnAction(e -> {
            if ("Sign out".equals(loginButton.getText())) {
                handleSignOut();
            } else {
                handleLogin();
            }
        });
        addServerButton.setOnAction(e -> handleAddServer());
        refreshHomeButton.setOnAction(e -> {
            loadServers();
            loadVersions();
            statusLabel.setText("Refreshed versions and servers.");
            consoleLabel.setText("Refreshed versions and servers.");
        });
        removeServerButton.setOnAction(e -> handleRemoveServer());
        playButton.setOnAction(e -> handlePlay());
        cancelButton.setOnAction(e -> handleCancelDownload());

        // TLauncher-style: typing a username directly activates an offline session,
        // no separate dialog or button click needed. Commits on Enter or when the
        // field loses focus, so we're not rebuilding the session on every keystroke.
        usernameField.setOnAction(e -> commitUsernameField());
        usernameField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) commitUsernameField();
        });

        widthField.textProperty().addListener((obs, old, val) -> saveResolutionSettings());
        heightField.textProperty().addListener((obs, old, val) -> saveResolutionSettings());

        autoActivateTestModeIfSaved();
        loadHeroBannerImage();

        serverComboBox.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            selectedServer = val;
        });

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
            imageView.setPreserveRatio(false);
            imageView.setSmooth(true);
            // Bind to the pane's actual size instead of a fixed pixel width, so it always
            // fills the banner area correctly regardless of window size.
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
     *  version and populates the loader-version dropdown, newest first. */
    private void loadFabricLoaderVersions() {
        String mcVersion = versionComboBox.getSelectionModel().getSelectedItem();
        if (mcVersion == null) return;

        fabricLoaderVersionComboBox.getItems().clear();
        fabricLoaderVersionComboBox.setPromptText("Loading loader versions...");

        Task<List<site.kpeclub.launcher.download.FabricInstaller.FabricLoaderVersion>> task = new Task<>() {
            @Override
            protected List<site.kpeclub.launcher.download.FabricInstaller.FabricLoaderVersion> call() throws Exception {
                return new site.kpeclub.launcher.download.FabricInstaller().fetchLoaderVersions(mcVersion);
            }
        };
        task.setOnSucceeded(e -> {
            var versions = task.getValue();
            for (var v : versions) {
                fabricLoaderVersionComboBox.getItems().add(v.version() + (v.stable() ? "" : "  (unstable)"));
            }
            if (!fabricLoaderVersionComboBox.getItems().isEmpty()) {
                String savedFabricLoaderVersion = LauncherConfig.loadSettings().getLastSelectedFabricLoaderVersion();
                if (savedFabricLoaderVersion != null
                        && fabricLoaderVersionComboBox.getItems().contains(savedFabricLoaderVersion)) {
                    fabricLoaderVersionComboBox.getSelectionModel().select(savedFabricLoaderVersion);
                } else {
                    fabricLoaderVersionComboBox.getSelectionModel().selectFirst(); // newest first per Fabric's API
                }
            } else {
                fabricLoaderVersionComboBox.setPromptText("No Fabric builds for " + mcVersion);
            }
        });
        task.setOnFailed(e -> {
            fabricLoaderVersionComboBox.setPromptText("Couldn't load (offline?)");
        });
        new Thread(task).start();
    }

    /** Returns just the version number from a dropdown entry like "0.16.9  (unstable)". */
    private String selectedFabricLoaderVersion() {
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
        String modLoader = modLoaderComboBox.getSelectionModel().getSelectedItem();
        browseModsContextLabel.setText(mcVersion != null
                ? "Searching mods for " + mcVersion + ("Fabric".equals(modLoader) ? " (Fabric)" : "")
                : "Searching all mods");
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

    /** Sets this modpack's version + Fabric loader as active on the Play screen and launches it. */
    private void handlePlayModpack(site.kpeclub.launcher.model.ModpackInfo pack) {
        if (!versionComboBox.getItems().contains(pack.getMinecraftVersion())) {
            versionComboBox.getItems().add(0, pack.getMinecraftVersion());
        }
        versionComboBox.getSelectionModel().select(pack.getMinecraftVersion());
        modLoaderComboBox.getSelectionModel().select("Fabric");
        showPlayView();
        navPlayButton.setSelected(true);
        handlePlay();
    }

    private void handleImportModpack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Modrinth Modpack");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Modrinth Modpack (*.mrpack)", "*.mrpack"));

        javafx.stage.Stage stage = (javafx.stage.Stage) importModpackButton.getScene().getWindow();
        java.io.File selected = chooser.showOpenDialog(stage);
        if (selected == null) return;

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
        new Thread(task).start();
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
            java.nio.file.Files.list(LauncherConfig.VERSIONS_DIR)
                    .filter(java.nio.file.Files::isDirectory)
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
    private void setupBrowseModsTab() {
        Runnable doSearch = () -> {
            String query = modSearchField.getText();
            if (query == null || query.isBlank()) return;

            String mcVersion = versionComboBox.getSelectionModel().getSelectedItem();
            String modLoader = modLoaderComboBox.getSelectionModel().getSelectedItem();
            String loaderFilter = "Fabric".equals(modLoader) ? "fabric" : null;

            modSearchResultsBox.getChildren().clear();
            modSearchStatusLabel.setText("Searching...");

            Task<List<site.kpeclub.launcher.model.ModSearchResult>> task = new Task<>() {
                @Override
                protected List<site.kpeclub.launcher.model.ModSearchResult> call() throws Exception {
                    return new site.kpeclub.launcher.download.ModrinthClient().search(query, mcVersion, loaderFilter);
                }
            };
            task.setOnSucceeded(e -> {
                var results = task.getValue();
                if (results.isEmpty()) {
                    modSearchStatusLabel.setText("No mods found for \"" + query + "\".");
                    return;
                }
                modSearchStatusLabel.setText(results.size() + " result(s).");
                for (var result : results) {
                    modSearchResultsBox.getChildren().add(
                            buildModSearchResultRow(result, mcVersion, loaderFilter, modSearchStatusLabel));
                }
            });
            task.setOnFailed(e -> {
                Throwable ex = task.getException();
                modSearchStatusLabel.setText("Search failed: " + (ex != null ? ex.getMessage() : "Unknown error"));
            });
            new Thread(task).start();
        };

        modSearchButton.setOnAction(e -> doSearch.run());
        modSearchField.setOnAction(e -> doSearch.run()); // Enter key triggers search too
    }

    private javafx.scene.layout.HBox buildModSearchResultRow(site.kpeclub.launcher.model.ModSearchResult result,
                                                              String mcVersion, String loaderFilter, Label dialogStatusLabel) {
        javafx.scene.layout.StackPane icon = new javafx.scene.layout.StackPane();
        icon.getStyleClass().addAll("install-icon", "install-icon-fabric");
        Label iconLetter = new Label(result.getTitle().isEmpty() ? "?" : result.getTitle().substring(0, 1).toUpperCase());
        iconLetter.getStyleClass().add("install-icon-letter");
        icon.getChildren().add(iconLetter);

        Label name = new Label(result.getTitle());
        name.getStyleClass().add("install-name");
        Label subtitle = new Label("by " + result.getAuthor() + "  -  " +
                String.format("%,d", result.getDownloads()) + " downloads");
        subtitle.getStyleClass().add("install-subtitle");
        javafx.scene.layout.VBox textStack = new javafx.scene.layout.VBox(2, name, subtitle);

        Button downloadBtn = new Button("Download");
        downloadBtn.getStyleClass().add("btn-play-small");
        downloadBtn.setOnAction(e -> {
            downloadBtn.setDisable(true);
            downloadBtn.setText("Downloading...");
            Task<java.nio.file.Path> task = new Task<>() {
                @Override
                protected java.nio.file.Path call() throws Exception {
                    site.kpeclub.launcher.download.ModrinthClient client = new site.kpeclub.launcher.download.ModrinthClient();
                    var files = client.listVersions(result.getProjectId(), mcVersion, loaderFilter);
                    if (files.isEmpty()) {
                        throw new IllegalStateException("No compatible file found for " +
                                (mcVersion != null ? mcVersion : "any version") +
                                (loaderFilter != null ? " (" + loaderFilter + ")" : ""));
                    }
                    return client.downloadModFile(files.get(0)); // newest compatible file
                }
            };
            task.setOnSucceeded(e2 -> {
                downloadBtn.setText("Downloaded");
                dialogStatusLabel.setText(result.getTitle() + " downloaded to mods folder.");
            });
            task.setOnFailed(e2 -> {
                downloadBtn.setDisable(false);
                downloadBtn.setText("Download");
                Throwable ex = task.getException();
                dialogStatusLabel.setText("Failed: " + (ex != null ? ex.getMessage() : "Unknown error"));
            });
            new Thread(task).start();
        });

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12, icon, textStack, spacer, downloadBtn);
        row.getStyleClass().add("install-row");
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
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
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(dir.toFile());
            }
        } catch (java.io.IOException e) {
            statusLabel.setText("Could not open folder: " + e.getMessage());
        }
    }

    /** Builds one row: icon, name + subtitle, Play / folder / delete buttons — matches the
     *  official Minecraft Launcher's Installations tab layout. */
    private javafx.scene.layout.HBox buildInstallationRow(site.kpeclub.launcher.model.InstalledVersion installation) {
        var loaderType = installation.getLoaderType();

        javafx.scene.layout.StackPane icon = new javafx.scene.layout.StackPane();
        icon.getStyleClass().add("install-icon");
        icon.getStyleClass().add(loaderType == site.kpeclub.launcher.model.InstalledVersion.LoaderType.VANILLA
                ? "install-icon-vanilla" : "install-icon-fabric");
        String iconLetter = switch (loaderType) {
            case FABRIC -> "F";
            case FORGE -> "Fo";
            case OPTIFINE -> "O";
            case VANILLA -> "M";
        };
        Label iconLetterLabel = new Label(iconLetter);
        iconLetterLabel.getStyleClass().add("install-icon-letter");
        icon.getChildren().add(iconLetterLabel);

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

    /** Extracts the Minecraft version portion of a Fabric folder id, e.g.
     *  "fabric-loader-0.16.9-1.21.1" -> "1.21.1", for display and for re-selecting on Play. */
    /** Extracts the underlying vanilla Minecraft version for a modded install's display name.
     *  Prefers reading "inheritsFrom" from the version json (works for Forge, OptiFine, and
     *  Fabric-via-official-installer, whose folder names vary), falling back to Fabric's own
     *  "fabric-loader-<loader>-<mc>" naming convention for Fabric installs via our launcher. */
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
        int lastDash = versionId.lastIndexOf('-');
        return lastDash >= 0 ? versionId.substring(lastDash + 1) : versionId;
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
        } catch (java.io.IOException e) {
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
            if (!versionComboBox.getItems().contains(mcVersion)) {
                versionComboBox.getItems().add(0, mcVersion);
            }
            versionComboBox.getSelectionModel().select(mcVersion);
            modLoaderComboBox.getSelectionModel().select("Fabric");
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
     *  types (Forge, OptiFine) that dropdown doesn't have a picker for. */
    private void launchInstalledVersionDirectly(site.kpeclub.launcher.model.InstalledVersion installation) {
        if (session == null) {
            statusLabel.setText("Enter a username first.");
            return;
        }
        final int[] resolution = getResolution();
        String host = null;
        Integer port = null;
        if (selectedServer != null) {
            String[] parts = selectedServer.getAddress().split(":");
            host = parts[0];
            port = parts.length > 1 ? Integer.parseInt(parts[1]) : 25565;
        }
        final String finalHost = host;
        final Integer finalPort = port;

        statusLabel.setText("Launching " + installation.getVersionId() + "...");
        consoleLabel.setText("Launching " + installation.getVersionId() + " (" +
                installation.getLoaderType().getLabel() + ")...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Process launchedProcess = new GameLauncher().launch(
                        installation.getVersionId(), session, finalHost, finalPort, resolution[0], resolution[1]);
                runningGameProcess = launchedProcess;
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.setText("Minecraft launched.");
            consoleLabel.setText("Minecraft launched.");
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            String msg = ex != null ? ex.getMessage() : "Unknown error";
            statusLabel.setText("Launch failed: " + msg);
            consoleLabel.setText("Launch failed: " + msg);
        });
        new Thread(task).start();
    }

    private void openInstallationFolder(site.kpeclub.launcher.model.InstalledVersion installation) {
        java.nio.file.Path dir = LauncherConfig.VERSIONS_DIR.resolve(installation.getVersionId());
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(dir.toFile());
            }
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
        LauncherSettings settings = LauncherConfig.loadSettings();
        settings.setLastSelectedVersion(versionComboBox.getSelectionModel().getSelectedItem());
        settings.setLastSelectedModLoader(modLoaderComboBox.getSelectionModel().getSelectedItem());
        settings.setLastSelectedFabricLoaderVersion(fabricLoaderVersionComboBox.getSelectionModel().getSelectedItem());
        LauncherConfig.saveSettings(settings);
    }

    /** Re-selects the last-used version + mod loader once the version dropdown has been
     *  populated (called after loadVersions() completes). If the saved version isn't in
     *  the list (e.g. offline and never downloaded), leaves whatever loadVersions() already
     *  auto-selected as a sensible fallback rather than leaving nothing selected. */
    private void restoreSelectedVersionAndLoader() {
        LauncherSettings settings = LauncherConfig.loadSettings();
        String savedVersion = settings.getLastSelectedVersion();
        String savedLoader = settings.getLastSelectedModLoader();

        if (savedVersion != null && versionComboBox.getItems().contains(savedVersion)) {
            versionComboBox.getSelectionModel().select(savedVersion);
        }
        if (savedLoader != null && modLoaderComboBox.getItems().contains(savedLoader)) {
            modLoaderComboBox.getSelectionModel().select(savedLoader);
        }
        // Fabric loader version dropdown populates asynchronously (network call) via the
        // versionComboBox listener above — restoring its selection happens once it loads,
        // see loadFabricLoaderVersions()'s task.setOnSucceeded.
    }

    private void loadResolutionSettings() {
        LauncherSettings settings = LauncherConfig.loadSettings();
        widthField.setText(String.valueOf(settings.getGameWidth()));
        heightField.setText(String.valueOf(settings.getGameHeight()));
    }

    private void saveResolutionSettings() {
        try {
            int width = Integer.parseInt(widthField.getText().trim());
            int height = Integer.parseInt(heightField.getText().trim());
            LauncherSettings settings = LauncherConfig.loadSettings(); // load-modify-save, don't clobber other fields
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

    // ---------------- Wallpaper ----------------

    // ---------------- Theme ----------------

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
        int width = 1280;
        int height = 720;
        try {
            width = Integer.parseInt(widthField.getText().trim());
            height = Integer.parseInt(heightField.getText().trim());
        } catch (NumberFormatException ignored) {
            statusLabel.setText("Invalid resolution, using default 1280x720.");
        }
        if (width <= 0 || height <= 0) {
            width = 1280;
            height = 720;
        }
        return new int[]{width, height};
    }

    // ---------------- Servers ----------------

    private void loadServers() {
        List<ServerEntry> all = new ArrayList<>(LauncherConfig.getPresetServers());
        all.addAll(LauncherConfig.loadCustomServers());
        serverComboBox.getItems().setAll(all);
        if (!all.isEmpty()) serverComboBox.getSelectionModel().selectFirst();
    }

    private void handleAddServer() {
        Dialog<ServerEntry> dialog = new Dialog<>();
        dialog.setTitle("Add Server");
        dialog.setHeaderText("Enter server details");

        TextField nameField = new TextField();
        nameField.setPromptText("Display name");
        TextField addressField = new TextField();
        addressField.setPromptText("play.example.com:25565");

        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8, nameField, addressField);
        box.setPadding(new javafx.geometry.Insets(12));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK && !nameField.getText().isBlank() && !addressField.getText().isBlank()) {
                return new ServerEntry(nameField.getText(), addressField.getText(), false, null);
            }
            return null;
        });

        Optional<ServerEntry> result = dialog.showAndWait();
        result.ifPresent(entry -> {
            serverComboBox.getItems().add(entry);
            serverComboBox.getSelectionModel().select(entry);
            List<ServerEntry> custom = LauncherConfig.loadCustomServers();
            custom.add(entry);
            LauncherConfig.saveCustomServers(custom);
        });
    }

    private void handleRemoveServer() {
        ServerEntry sel = serverComboBox.getSelectionModel().getSelectedItem();
        if (sel == null || sel.isPreset()) {
            statusLabel.setText("Can't remove KPE Club preset servers.");
            return;
        }
        serverComboBox.getItems().remove(sel);
        List<ServerEntry> custom = LauncherConfig.loadCustomServers();
        custom.removeIf(s -> s.getAddress().equals(sel.getAddress()));
        LauncherConfig.saveCustomServers(custom);
    }

    // ---------------- Versions ----------------

    private void loadVersions() {
        versionComboBox.getItems().clear();

        // Always show locally installed versions immediately — don't wait on network.
        List<String> localVersions = scanLocallyCachedVersions();
        localVersions.forEach(id -> versionComboBox.getItems().add(id));
        if (!localVersions.isEmpty()) {
            versionComboBox.getSelectionModel().selectFirst();
            consoleLabel.setText(localVersions.size() + " locally installed version(s) found.");
        }
        restoreSelectedVersionAndLoader();

        Task<List<VersionEntry>> task = new Task<>() {
            @Override
            protected List<VersionEntry> call() throws Exception {
                return new VersionManifest().fetchVersionList();
            }
        };
        task.setOnSucceeded(e -> {
            List<VersionEntry> versions = task.getValue();
            versions.stream()
                    .filter(v -> v.type().equals("release"))
                    .map(VersionEntry::id)
                    .filter(id -> !versionComboBox.getItems().contains(id)) // don't duplicate local ones
                    .forEach(id -> versionComboBox.getItems().add(id));
            if (versionComboBox.getSelectionModel().getSelectedItem() == null
                    && !versionComboBox.getItems().isEmpty()) {
                versionComboBox.getSelectionModel().selectFirst();
            }
            restoreSelectedVersionAndLoader(); // in case the saved version was only in the online list
        });
        task.setOnFailed(e -> {
            if (localVersions.isEmpty()) {
                statusLabel.setText("No internet and no versions installed yet — connect once to download a version.");
            } else {
                statusLabel.setText("No internet — showing locally installed versions only.");
            }
        });
        new Thread(task).start();
    }

    /** Scans .minecraft/versions/ for already-installed versions (from this launcher OR the official one). */
    private List<String> scanLocallyCachedVersions() {
        try {
            if (!java.nio.file.Files.exists(LauncherConfig.VERSIONS_DIR)) return new ArrayList<>();
            return java.nio.file.Files.list(LauncherConfig.VERSIONS_DIR)
                    .filter(java.nio.file.Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(id -> java.nio.file.Files.exists(
                            LauncherConfig.VERSIONS_DIR.resolve(id).resolve(id + ".jar")))
                    .filter(id -> !id.startsWith("fabric-loader-")) // keep Fabric installs out of the vanilla dropdown
                    .filter(id -> isValidCachedVersionJson(
                            LauncherConfig.VERSIONS_DIR.resolve(id).resolve(id + ".json")))
                    .sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList());
        } catch (java.io.IOException e) {
            return new ArrayList<>();
        }
    }

    // ---------------- Login ----------------

    private void handleSignOut() {
        session = null;
        loginButton.setText("Sign in with Microsoft");
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
        statusLabel.setText("Opening browser for Microsoft sign-in...");

        Task<MinecraftSession> task = new Task<>() {
            @Override
            protected MinecraftSession call() throws Exception {
                return new MicrosoftAuth().login();
            }
        };
        task.setOnSucceeded(e -> {
            session = task.getValue();
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
        new Thread(task).start();
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
    private void commitUsernameField() {
        String username = usernameField.getText();
        if (username == null || username.isBlank()) return;
        activateTestModeSession(username.trim(), true);

        // Refresh versions + servers at the same time — same as clicking the Refresh
        // button — so switching to a new/returning player also picks up anything new.
        loadServers();
        loadVersions();
    }

    /** Builds and activates an offline session for the given username.
     *  @param persist whether to save this username as the "last used" one for next launch. */
    private void activateTestModeSession(String username, boolean persist) {
        // Offline-style UUID derivation (same scheme vanilla MC uses for offline-mode)
        String offlineUuid = java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ).toString();

        session = new MinecraftSession(username, offlineUuid, "TEST_MODE_NO_TOKEN");
        accountLabel.setText(username + " (Offline)");
        accountStatusLabel.setText("offline");
        // Deliberately not disabling loginButton — the person can still click "Sign in"
        // to log in with a real Microsoft account, or just edit the username field again,
        // without needing to sign out first.
        loginButton.setText("Sign in with Microsoft");
        loginButton.setDisable(false);
        playButton.setDisable(false);
        statusLabel.setText("Playing offline as " + username + " — works on offline-mode servers only.");
        consoleLabel.setText("Playing offline as " + username);

        if (persist) {
            LauncherSettings settings = LauncherConfig.loadSettings();
            settings.setLastTestModeUsername(username);
            LauncherConfig.saveSettings(settings);
        }
    }



    private void handlePlay() {
        if (session == null) {
            statusLabel.setText("Please sign in first.");
            return;
        }
        String baseVersionId = versionComboBox.getSelectionModel().getSelectedItem();
        if (baseVersionId == null) {
            statusLabel.setText("Please select a version.");
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
                // person picked in the Fabric loader-version dropdown (or the newest available
                // if none was explicitly chosen).
                final String[] resolvedVersionIdHolder = { baseVersionId };
                JsonObject resolvedVersionJson = null;

                if (useFabric) {
                    FabricInstaller fabric = new FabricInstaller();
                    String loaderVersion = selectedFabricLoaderVersion;
                    if (loaderVersion == null) {
                        // No explicit selection (e.g. dropdown hadn't loaded yet) — fall back
                        // to newest available, same as before.
                        var loaderVersions = fabric.fetchLoaderVersions(baseVersionId);
                        if (loaderVersions.isEmpty()) {
                            throw new IllegalStateException("No Fabric loader available for " + baseVersionId);
                        }
                        loaderVersion = loaderVersions.get(0).version();
                    }

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
                String resolvedVersionId = resolvedVersionIdHolder[0];

                java.nio.file.Path cachedVersionJson = LauncherConfig.VERSIONS_DIR
                        .resolve(resolvedVersionId).resolve(resolvedVersionId + ".json");
                java.nio.file.Path cachedClientJar = LauncherConfig.VERSIONS_DIR
                        .resolve(resolvedVersionId).resolve(resolvedVersionId + ".jar");
                boolean alreadyCached = java.nio.file.Files.exists(cachedVersionJson)
                        && java.nio.file.Files.exists(cachedClientJar)
                        && isValidCachedVersionJson(cachedVersionJson);

                if (alreadyCached) {
                    // Fully offline path — no network calls at all, launch straight from disk.
                    Platform.runLater(() -> {
                        statusLabel.setText(resolvedVersionId + " already downloaded — launching offline.");
                        consoleLabel.setText("Launching " + resolvedVersionId + " from local cache (offline).");
                    });
                } else {
                    JsonObject versionJson = resolvedVersionJson;
                    if (versionJson == null) {
                        // Plain vanilla, not yet resolved above
                        VersionManifest manifest = new VersionManifest();
                        VersionEntry entry = manifest.fetchVersionList().stream()
                                .filter(v -> v.id().equals(baseVersionId))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException(
                                        "Version not found and no internet connection to look it up: " + baseVersionId));
                        versionJson = manifest.fetchVersionJson(entry.url());
                    }

                    GameDownloader downloader = new GameDownloader();
                    downloader.downloadVersion(
                            versionJson,
                            progress -> Platform.runLater(() -> {
                                downloadProgress.setProgress(progress.fraction());
                                String detail = String.format("%.0f%%  -  %.1f MB / %.1f MB  (%s)",
                                        progress.percent(), progress.downloadedMB(), progress.totalMB(),
                                        progress.currentFile());
                                progressDetailLabel.setText(detail);
                                consoleLabel.setText(detail);
                            }),
                            this::isCancelled // downloader checks this between files and bails out if true
                    );
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

                site.kpeclub.launcher.download.JreManager jreManager = new site.kpeclub.launcher.download.JreManager();
                String requiredComponent = jreManager.requiredComponent(javaVersionSource);

                if (!jreManager.isComponentInstalled(requiredComponent)) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Downloading Java runtime (" + requiredComponent + ")...");
                        consoleLabel.setText("Downloading Java runtime: " + requiredComponent);
                    });
                    jreManager.downloadComponentIfMissing(requiredComponent, progress ->
                            Platform.runLater(() -> {
                                downloadProgress.setProgress(progress.fraction());
                                String detail = String.format("Java runtime: %.0f%%  -  %.1f MB / %.1f MB",
                                        progress.percent(), progress.downloadedMB(), progress.totalMB());
                                progressDetailLabel.setText(detail);
                                consoleLabel.setText(detail);
                            }));
                }

                if (isCancelled()) return null;

                Platform.runLater(() -> statusLabel.setText("Launching..."));

                String host = null;
                Integer port = null;
                if (selectedServer != null) {
                    String[] parts = selectedServer.getAddress().split(":");
                    host = parts[0];
                    port = parts.length > 1 ? Integer.parseInt(parts[1]) : 25565;
                }

                Process launchedProcess = new GameLauncher().launch(
                        resolvedVersionId, session, host, port, resolution[0], resolution[1]);
                runningGameProcess = launchedProcess;
                return null;
            }
        };

        currentDownloadTask = task;

        task.setOnSucceeded(e -> {
            statusLabel.setText("Minecraft launched.");
            consoleLabel.setText("Minecraft launched.");
            resetDownloadUi();

            if (!keepOpenCheckBox.isSelected() && runningGameProcess != null) {
                javafx.stage.Stage stage = (javafx.stage.Stage) playButton.getScene().getWindow();
                stage.hide();

                Process watchedProcess = runningGameProcess;
                Thread watcher = new Thread(() -> {
                    try {
                        watchedProcess.waitFor(); // blocks until the game process exits
                    } catch (InterruptedException ignored) {
                        // launcher shutting down — nothing more to do
                    }
                    Platform.runLater(() -> {
                        if (watchedProcess == runningGameProcess) { // still the most recent launch
                            stage.show();
                            statusLabel.setText("Minecraft closed.");
                            consoleLabel.setText("Minecraft closed.");
                        }
                    });
                });
                watcher.setDaemon(true);
                watcher.start();
            }
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            String msg = ex != null ? ex.getMessage() : "Unknown error";
            statusLabel.setText("Launch failed: " + msg);
            consoleLabel.setText("Launch failed: " + msg);
            resetDownloadUi();
        });
        task.setOnCancelled(e -> {
            statusLabel.setText("Download cancelled.");
            consoleLabel.setText("Download cancelled.");
            resetDownloadUi();
        });

        new Thread(task).start();
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
            return java.nio.file.Files.list(LauncherConfig.VERSIONS_DIR)
                    .filter(java.nio.file.Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(id -> id.startsWith("fabric-loader-") && id.endsWith("-" + baseVersionId))
                    .filter(id -> java.nio.file.Files.exists(
                            LauncherConfig.VERSIONS_DIR.resolve(id).resolve(id + ".jar")))
                    .filter(id -> isValidCachedVersionJson(
                            LauncherConfig.VERSIONS_DIR.resolve(id).resolve(id + ".json")))
                    .findFirst()
                    .orElse(null);
        } catch (java.io.IOException e) {
            return null;
        }
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
