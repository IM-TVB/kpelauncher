; KPE Club Launcher — Inno Setup installer script (supports both fresh install and in-place update!)
;
; Replaces jpackage --type exe, since jpackage/WiX has no supported way to run custom
; cleanup logic during uninstall.
;
; UPGRADE / UPDATE SUPPORT:
;   - Uses a fixed AppId so running a newer setup .exe over an existing installation
;     automatically detects the installed version, reuses its folder (UsePreviousAppDir=yes),
;     closes the running launcher if open (CloseApplications=yes), cleans old runtime
;     modules in [InstallDelete] so jlink images never collide, and preserves all player
;     settings (%APPDATA%\.kpelauncher) and game files (%APPDATA%\.minecraft).

#define MyAppName "KPE Club Launcher"
#define MyAppVersion "1.8.1"
; Compile with  ISCC /DLegacy32 KPEClubLauncher.iss  for the 32-bit installer (works on 32-bit AND
; 64-bit Windows, and on older PCs). Without it you get the 64-bit installer.
#ifdef Legacy32
  #define MyArchLabel "x86"
#else
  #define MyArchLabel "x64"
#endif
#define MyAppPublisher "KPE Club"
#define MyAppURL "https://kpeclub.site"

#if !FileExists(AddBackslash(SourcePath) + "target\runtime\bin\javaw.exe")
  #error "target\runtime\ is missing! Before compiling KPEClubLauncher.iss in Inno Setup, run build-win64.bat (or run 'mvn clean package' followed by 'jlink' as shown in README.md) to generate target\runtime\."
#endif

[Setup]
AppId={{8F2C9B3A-4D1E-4A7B-9C5F-1A2B3C4D5E6F}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
AppCopyright=Copyright (C) 2026 {#MyAppPublisher}
VersionInfoVersion={#MyAppVersion}.0
VersionInfoCompany={#MyAppPublisher}
VersionInfoDescription={#MyAppName} Installer
VersionInfoTextVersion={#MyAppVersion}
VersionInfoCopyright=Copyright (C) 2026 {#MyAppPublisher}
VersionInfoProductName={#MyAppName}
VersionInfoProductVersion={#MyAppVersion}
VersionInfoOriginalFileName=KPE-Club-Launcher-Setup-{#MyAppVersion}-{#MyArchLabel}.exe
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
UsePreviousAppDir=yes
UsePreviousTasks=yes
DirExistsWarning=no
CloseApplications=yes
CloseApplicationsFilter=*.exe,*.dll
RestartApplications=no
DisableProgramGroupPage=yes
DisableWelcomePage=no
LicenseFile=installer\LICENSE.txt
OutputDir=Output
OutputBaseFilename=KPE-Club-Launcher-Setup-{#MyAppVersion}-{#MyArchLabel}
Compression=lzma2/ultra64
SolidCompression=yes
LZMADictionarySize=131072
LZMANumFastBytes=273
WizardStyle=modern
WizardImageFile=installer\wizard-image.bmp
WizardSmallImageFile=installer\wizard-small.bmp
SetupIconFile=src\main\resources\images\icon.ico
UninstallDisplayIcon={app}\icon.ico
UninstallDisplayName={#MyAppName}
#ifdef Legacy32
MinVersion=6.1.7601
#else
MinVersion=6.3
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"

[InstallDelete]
; When updating an existing installation, remove the old jlink runtime folders inside {app}
; first so stale modules/DLLs from a previous version never mix with the new runtime.
; Player data in %APPDATA%\.kpelauncher and %APPDATA%\.minecraft is NEVER touched during an update.
Type: filesandordirs; Name: "{app}\bin"
Type: filesandordirs; Name: "{app}\conf"
Type: filesandordirs; Name: "{app}\legal"
Type: filesandordirs; Name: "{app}\lib"

[Files]
; Bundles the entire jlink runtime image (Java runtime + your app's module)
Source: "target\runtime\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

; Custom icon for shortcuts and Add/Remove Programs
Source: "src\main\resources\images\icon.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "installer\KPEClub-Publisher.cer"; DestDir: "{app}"; Flags: ignoreversion skipifsourcedoesntexist

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\bin\javaw.exe"; \
    Parameters: "-Xms32m -Xmx192m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -m site.kpeclub.launcher/site.kpeclub.launcher.Main"; \
    WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\bin\javaw.exe"; \
    Parameters: "-Xms32m -Xmx192m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -m site.kpeclub.launcher/site.kpeclub.launcher.Main"; \
    WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\bin\javaw.exe"; \
    Parameters: "-Xms32m -Xmx192m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -m site.kpeclub.launcher/site.kpeclub.launcher.Main"; \
    Description: "Launch {#MyAppName} now"; Flags: nowait postinstall

[Messages]
FinishedHeadingLabel=Setup Complete
FinishedLabel={#MyAppName} {#MyAppVersion} has been installed on your computer.%n%nYou're all set — leave "Launch {#MyAppName} now" checked below to jump straight in.
ConfirmUninstall=Are you sure you want to remove {#MyAppName}?%n%nYou'll be asked separately whether to also delete your saved settings (wallpaper, preferences).

[Code]
const
  UninstallRegKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{8F2C9B3A-4D1E-4A7B-9C5F-1A2B3C4D5E6F}_is1';

var
  ExistingVersion: String;
  IsUpgradeInstall: Boolean;
  HasExistingSettings: Boolean;

function GetInstalledVersion(): String;
begin
  Result := '';
  if not RegQueryStringValue(HKLM, UninstallRegKey, 'DisplayVersion', Result) then
    RegQueryStringValue(HKCU, UninstallRegKey, 'DisplayVersion', Result);
end;

procedure InitializeWizard();
begin
  ExistingVersion := GetInstalledVersion();
  HasExistingSettings := FileExists(ExpandConstant('{userappdata}\.kpelauncher\settings.json'));
  IsUpgradeInstall := (ExistingVersion <> '') or FileExists(ExpandConstant('{autopf}\{#MyAppName}\bin\javaw.exe')) or HasExistingSettings;
  if IsUpgradeInstall then
  begin
    if ExistingVersion <> '' then
      WizardForm.WelcomeLabel1.Caption := 'Update {#MyAppName} (' + ExistingVersion + ' -> {#MyAppVersion})'
    else
      WizardForm.WelcomeLabel1.Caption := 'Update {#MyAppName} to {#MyAppVersion}';

    WizardForm.WelcomeLabel2.Caption :=
      'An existing installation of {#MyAppName} was detected on your computer.' + #13#10 + #13#10 +
      'Setup will update {#MyAppName} to version {#MyAppVersion} in place while keeping all of your ' +
      'saved settings, accounts, mods, modpacks, and Minecraft worlds.' + #13#10 + #13#10 +
      'Click Next to continue with the update.';

    WizardForm.FinishedLabel.Caption :=
      '{#MyAppName} has been updated to version {#MyAppVersion} on your computer.' + #13#10 + #13#10 +
      'You''re all set — leave "Launch {#MyAppName} now" checked below to jump straight in.';
  end;
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  // Skip the License agreement and directory selection pages when updating or when settings already exist
  if IsUpgradeInstall and ((PageID = wpLicense) or (PageID = wpSelectDir)) then
    Result := True;
end;

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
      DeleteChoice := MsgBox(
        'Do you also want to remove your KPE Club Launcher settings ' +
        '(wallpaper, preferences)?' + #13#10 + #13#10 +
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
