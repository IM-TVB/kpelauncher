# KPE Club Launcher

Premium-only Minecraft launcher (Java/JavaFX) with Microsoft OAuth login,
KPE Club server presets, and custom server support.

## Before it will run

### 1. Register an Azure AD app (required for login)
Microsoft auth needs your own app registration:

1. Go to https://portal.azure.com/ → "App registrations" → "New registration"
2. Name: anything (e.g. "KPE Club Launcher")
3. Supported account types: **"Personal Microsoft accounts only"**
4. Redirect URI: type = **Public client/native (mobile & desktop)**, value =
   `http://127.0.0.1:43110/callback`
5. After creation, copy the **Application (client) ID**
6. Paste it into `src/main/java/site/kpeclub/launcher/auth/MicrosoftAuth.java`
   as `CLIENT_ID`
7. Under "API permissions" add `XboxLive.signin` (delegated) if not already present

No client secret is needed — this uses the public/native app flow (auth code,
no secret, since a secret can't be kept safe in a distributed desktop app).

### 2. Install JDK 17+ and Maven on your dev machine
This project was scaffolded in a sandbox without internet access to Maven
Central, so **it has not been compiled or run yet**. You'll need to build it
yourself locally.

**Important: this project now uses Java Modules (JPMS)**, not a shaded fat jar.
Shading a JavaFX app into one jar doesn't actually work — JavaFX's runtime
checks for proper modules and refuses to start otherwise (`Error: JavaFX
runtime components are missing`), even though the classes are technically
present. Modules is the correct, supported way to package a JavaFX desktop app.

```bash
mvn clean package
```

This produces `target/modules/`, containing your app's jar plus every
dependency jar (JavaFX, Gson) side by side — this whole folder is your
module path.

### 3. Run it (dev mode)
```bash
mvn javafx:run
```

### 4. Build a distributable .exe
This is a **two-step process**: jlink first (bundles a minimal Java
runtime containing exactly the modules you need), then **Inno Setup** (wraps
that into a native Windows installer with a proper uninstaller).

We use Inno Setup instead of jpackage's built-in `--type exe` because
jpackage/WiX has no supported way to run custom cleanup logic on uninstall —
and we want uninstalling to also remove `%APPDATA%\.kpelauncher\` (the
launcher's own settings/servers/wallpaper folder), which jpackage can't do.

```bash
:: Step 1: jlink — build a custom runtime image
jlink --module-path "target/modules;%JAVA_HOME%\jmods" ^
  --add-modules site.kpeclub.launcher,javafx.controls,javafx.fxml,jdk.httpserver,java.desktop ^
  --output target/runtime ^
  --strip-debug --compress=2 --no-header-files --no-man-pages
```

**Step 2:** Install [Inno Setup](https://jrsoftware.org/isinfo.php) (free), then
open `KPEClubLauncher.iss` (in the project root) in the Inno Setup Compiler
and click **Compile** — or run from the command line:
```bash
"C:\Program Files (x86)\Inno Setup 6\ISCC.exe" KPEClubLauncher.iss
```

This produces `Output\KPE-Club-Launcher-Setup-1.0.0.exe` — a single
installer with desktop/Start Menu shortcuts and a real uninstaller.

You should also **code-sign the .exe** with a certificate, or Windows
SmartScreen will warn players it's from an "unknown publisher."

### Uninstalling
The Inno Setup installer registers a normal Windows uninstaller. Players
remove KPE Club Launcher the usual way:
- **Settings → Apps → Installed apps** → "KPE Club Launcher" → Uninstall
- or **Control Panel → Programs and Features** → same thing

Uninstalling removes the app files, Start Menu/desktop shortcuts, **and**
`%APPDATA%\.kpelauncher\` (settings, custom servers, wallpaper, saved
modpacks list) — this cleanup is handled by the `CurUninstallStepChanged`
procedure in `KPEClubLauncher.iss`. It deliberately does **not** touch
`%APPDATA%\.minecraft\` — that folder is shared with the official Minecraft
Launcher and holds the player's actual downloaded versions, mods, and
worlds, which should survive uninstalling just this launcher.


### Why the old shaded-jar approach didn't work
`java -jar` on a shaded fat jar with JavaFX inside it triggers
`Error: JavaFX runtime components are missing, and are required to run this
application` — even with every class physically present — because JavaFX
checks the module system at startup, and a flattened classpath jar isn't a
real module. `mvn javafx:run` masked this because the plugin manually builds
a correct module path for you at dev time; it doesn't reflect what a
double-clicked jar or .exe would actually do.

## Where files are saved
```
%APPDATA%\.kpelauncher\        (launcher-only: settings.json, custom_servers.json)

%APPDATA%\.minecraft\          (shared with the official Minecraft Launcher, if installed)
├── versions\
├── libraries\
├── assets\
└── natives\
```
Using the same `.minecraft` folder as the official launcher means players who
already have Minecraft installed won't need to re-download versions, assets,
or libraries — this launcher reuses whatever's already there. It also means
both launchers read/write the same folder, which is standard practice for
third-party launchers.

## How Minecraft's Java runtime is handled
This launcher does NOT use its own bundled Java (the jlink runtime built for
the launcher UI) to run Minecraft — that runtime is deliberately trimmed down
to only what JavaFX/Gson need, and is missing modules Minecraft/mods require
(e.g. java.logging), causing errors like `NoClassDefFoundError:
java/util/logging/Logger`.

Instead, `JreManager` downloads Mojang's OWN official per-version Java
runtimes — the exact same ones the real Minecraft Launcher bundles — the
first time a version needing them is launched. These are cached at
`%APPDATA%\.kpelauncher\runtimes\<component>\` and reused after that. This is
the same mechanism every legitimate Minecraft launcher uses, so it's robust
across vanilla, Fabric, OptiFine, and Forge without us guessing which JDK
modules any given loader needs.

## What's implemented
- Full Microsoft → Xbox Live → XSTS → Minecraft OAuth chain (`auth/MicrosoftAuth.java`)
- Game ownership check (blocks non-owners even if they somehow get a token)
- Version manifest fetch + per-version download (client jar, libraries, natives, assets)
- Launch command builder with `--quickPlayMultiplayer` auto-connect
- KPE Club preset servers (`nano.kpeclub.site`, `familysmp.kpeclub.site`) + custom server add/remove, persisted to `%APPDATA%\.kpelauncher\custom_servers.json`
- Dark JavaFX UI matching your existing site aesthetic

## What's NOT implemented yet (next steps)
- Progress bar granularity is rough — it jumps at library/asset boundaries. Fine for now, worth smoothing later.
- No auto-updater for the launcher itself.
- No crash log viewer if the game process fails to start.
- No settings screen (RAM allocation slider, Java path override, resolution).
- `quickPlayMultiplayer` only works reliably on newer versions (1.20+ish) — older versions will download and launch fine but won't auto-join, player will need to click Multiplayer once.

## Known limitation of this scaffold
This was built in a sandboxed environment with no access to Maven Central,
Mojang's APIs, or Microsoft's login endpoints, so **none of this has been
compiled or actually run**. Treat it as a strong structural starting point,
not a tested build — expect to fix a few small compile errors (typos, minor
Gson API mismatches) on your first `mvn compile`, especially around JSON
field access. Paste me any errors and I'll fix them directly.
