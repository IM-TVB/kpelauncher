; KPE Club Launcher — Inno Setup installer script
;
; Replaces jpackage --type exe, since jpackage/WiX has no supported way to run custom
; cleanup logic during uninstall. This script explicitly deletes the launcher's own
; settings folder (%APPDATA%\.kpelauncher — servers, resolution, wallpaper, etc) when
; the app is uninstalled. It deliberately does NOT touch %APPDATA%\.minecraft — that's
; shared with the official Minecraft Launcher and contains the player's actual game
; files, worlds, and mods, which should survive uninstalling just this launcher.
;
; PREREQUISITES before compiling this script:
;   1. Build the jlink runtime image first (see README.md's jlink command) —
;      this script expects it at target\runtime\
;   2. Download and install Inno Setup: https://jrsoftware.org/isinfo.php
;   3. Confirm these files exist (already included in this project):
;        - installer\LICENSE.txt        (shown as the license/ToS page during setup —
;                                         keep in sync with TermsOfService.java's TEXT)
;        - installer\wizard-image.bmp   (164x314 sidebar image, left side of most pages)
;        - installer\wizard-small.bmp   (55x58 icon, top-right of the welcome/finish pages)
;   4. Open this file in Inno Setup's compiler (or run ISCC.exe KPEClubLauncher.iss)
;
; Output: a single KPE-Club-Launcher-Setup-1.0.0.exe in the Output\ folder.

#define MyAppName "KPE Club Launcher"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "KPE Club"
#define MyAppURL "https://kpeclub.site"

[Setup]
AppId={{8F2C9B3A-4D1E-4A7B-9C5F-1A2B3C4D5E6F}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
AppCopyright=Copyright (C) 2026 {#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
DisableWelcomePage=no
LicenseFile=installer\LICENSE.txt
OutputDir=Output
OutputBaseFilename=KPE-Club-Launcher-Setup-{#MyAppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
WizardImageFile=installer\wizard-image.bmp
WizardSmallImageFile=installer\wizard-small.bmp
SetupIconFile=src\main\resources\images\icon.ico
UninstallDisplayIcon={app}\icon.ico
UninstallDisplayName={#MyAppName}
; Windows 8.1 is the practical floor here — the bundled Java runtime (17+) doesn't
; support Windows 7 at all, so block install attempts on anything older with a clear
; message instead of letting them hit a cryptic failure after installing.
MinVersion=6.3
; ArchitecturesInstallIn64BitMode intentionally omitted — this build targets whatever
; architecture the bundled jlink runtime was built for. Build a 32-bit runtime image
; and 32-bit JDK if you need a 32-bit installer; this script itself is architecture-agnostic.

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"

[Files]
; Bundles the entire jlink runtime image (Java runtime + your app's module) built by:
;   jlink --module-path "target/modules;%JAVA_HOME%\jmods" ...
; See README.md for the exact command. Run that BEFORE compiling this script.
Source: "target\runtime\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

; The actual launcher is javaw.exe (there is no separate "KPEClubLauncher.exe" —
; the jlink runtime only produces java.exe/javaw.exe). We bundle the .ico separately
; and point every shortcut's IconFilename at THIS file, so shortcuts show your logo
; instead of Java's generic default icon.
Source: "src\main\resources\images\icon.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\bin\javaw.exe"; \
    Parameters: "-m site.kpeclub.launcher/site.kpeclub.launcher.Main"; \
    WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\bin\javaw.exe"; \
    Parameters: "-m site.kpeclub.launcher/site.kpeclub.launcher.Main"; \
    WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\bin\javaw.exe"; \
    Parameters: "-m site.kpeclub.launcher/site.kpeclub.launcher.Main"; \
    Description: "Launch {#MyAppName} now"; Flags: nowait postinstall skipifsilent

[Messages]
; Friendlier finish-page wording than Inno's generic default.
FinishedHeadingLabel=Setup Complete
FinishedLabel=%1 has been installed on your computer.%n%nYou're all set — click Finish to close this window, or leave "Launch KPE Club Launcher now" checked above to jump straight in.
; Custom confirmation shown before uninstalling.
ConfirmUninstall=Are you sure you want to remove %1?%n%nYou'll be asked separately whether to also delete your saved settings (servers, wallpaper, preferences).

[Code]
// Reads the "uninstallDataPreference" field from settings.json (set via the in-app
// Settings > "On Uninstall" dropdown) using a plain substring search rather than a
// full JSON parser — good enough for one simple string field. Returns 'Keep',
// 'Delete', or '' if the setting is missing/null (meaning: ask instead).
function ReadUninstallPreference(SettingsFilePath: String): String;
var
  FileContent: AnsiString;
begin
  Result := '';
  if not FileExists(SettingsFilePath) then Exit;
  if not LoadStringFromFile(SettingsFilePath, FileContent) then Exit;

  if Pos('"uninstallDataPreference": "Keep"', FileContent) > 0 then
    Result := 'Keep'
  else if Pos('"uninstallDataPreference": "Delete"', FileContent) > 0 then
    Result := 'Delete';
end;

// Removes %APPDATA%\.kpelauncher (settings, custom servers, wallpaper, modpacks list)
// after the app itself has been fully uninstalled — honoring the in-app "On Uninstall"
// preference if the player already set one, and only asking here if they didn't.
// Deliberately never touches %APPDATA%\.minecraft — that folder is shared with the
// official Minecraft Launcher and holds the player's actual game files, which should
// never be removed by this.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  KpeLauncherDataDir: String;
  SettingsFilePath: String;
  Preference: String;
  DeleteChoice: Integer;
  ShouldDelete: Boolean;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    KpeLauncherDataDir := ExpandConstant('{userappdata}\.kpelauncher');
    if not DirExists(KpeLauncherDataDir) then Exit;

    SettingsFilePath := KpeLauncherDataDir + '\settings.json';
    Preference := ReadUninstallPreference(SettingsFilePath);

    if Preference = 'Keep' then
    begin
      ShouldDelete := False;
    end
    else if Preference = 'Delete' then
    begin
      ShouldDelete := True;
    end
    else
    begin
      // No preference set in-app — ask, same as before.
      DeleteChoice := MsgBox(
        'Do you also want to remove your KPE Club Launcher settings ' +
        '(servers, wallpaper, preferences)?' + #13#10 + #13#10 +
        'Your downloaded Minecraft versions, mods, and worlds in %APPDATA%\.minecraft ' +
        'will NOT be touched either way.' + #13#10 + #13#10 +
        '(Tip: you can pre-decide this next time under Settings > On Uninstall.)',
        mbConfirmation, MB_YESNO);
      ShouldDelete := (DeleteChoice = IDYES);
    end;

    if ShouldDelete then
    begin
      DelTree(KpeLauncherDataDir, True, True, True);
      MsgBox('KPE Club Launcher and its settings have been removed.', mbInformation, MB_OK);
    end
    else
    begin
      MsgBox('KPE Club Launcher has been removed. Your settings were kept at:' + #13#10 + KpeLauncherDataDir, mbInformation, MB_OK);
    end;
  end;
end;
