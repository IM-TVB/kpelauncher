@echo off
REM KPE Club Launcher 1.8.1 — 64-bit build (size-optimized runtime & installer).
REM Automatically detects 64-bit JDK 17+, Maven (or downloads portable Maven), and Inno Setup 6 or 7.
setlocal
cd /d "%~dp0"

REM ============================================================================
REM 1. Auto-detect 64-bit JDK 17+ (JAVA_HOME)
REM ============================================================================
if "%JAVA_HOME%"=="" goto :detect_java
if not exist "%JAVA_HOME%\jmods\java.base.jmod" goto :detect_java
goto :java_ready

:detect_java
set "JAVA_HOME="

REM Step A: Resolve java.home from java.exe on PATH (handles Oracle/Adoptium javapath shims)
for /f "tokens=2 delims==" %%A in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home ="') do (
    if "%JAVA_HOME%"=="" (
        for /f "tokens=* delims= " %%B in ("%%A") do (
            if exist "%%~fB\jmods\java.base.jmod" set "JAVA_HOME=%%~fB"
        )
    )
)

REM Step B: Derive JAVA_HOME from javac.exe or java.exe on PATH
if "%JAVA_HOME%"=="" (
    for /f "delims=" %%J in ('where javac.exe 2^>nul') do (
        if "%JAVA_HOME%"=="" (
            for %%P in ("%%~dpJ..") do (
                if exist "%%~fP\jmods\java.base.jmod" set "JAVA_HOME=%%~fP"
            )
        )
    )
)
if "%JAVA_HOME%"=="" (
    for /f "delims=" %%J in ('where java.exe 2^>nul') do (
        if "%JAVA_HOME%"=="" (
            for %%P in ("%%~dpJ..") do (
                if exist "%%~fP\jmods\java.base.jmod" set "JAVA_HOME=%%~fP"
            )
        )
    )
)

REM Step C: Query Windows Registry for installed 64-bit JDKs
if "%JAVA_HOME%"=="" (
    for %%K in (
        "HKLM\SOFTWARE\Eclipse Adoptium\JDK"
        "HKLM\SOFTWARE\JavaSoft\JDK"
        "HKLM\SOFTWARE\Microsoft\JDK"
        "HKLM\SOFTWARE\AdoptOpenJDK\JDK"
        "HKLM\SOFTWARE\Azul Systems\Zulu"
        "HKLM\SOFTWARE\BellSoft\Liberica"
    ) do (
        if "%JAVA_HOME%"=="" (
            for /f "tokens=2*" %%A in ('reg query "%%~K" /s /v JavaHome 2^>nul ^| findstr /i "JavaHome"') do (
                if "%JAVA_HOME%"=="" (
                    if exist "%%~fB\jmods\java.base.jmod" set "JAVA_HOME=%%~fB"
                )
            )
        )
    )
)

REM Step D: Scan common 64-bit JDK directories in Program Files
if "%JAVA_HOME%"=="" (
    for %%V in ("Eclipse Adoptium" "Java" "Microsoft" "Zulu" "Amazon Corretto" "BellSoft" "Liberica" "Semeru" "AdoptOpenJDK" "RedHat" "SapMachine" "GraalVM") do (
        if exist "%ProgramFiles%\%%~V" (
            for /f "delims=" %%D in ('dir /b /ad /o-n "%ProgramFiles%\%%~V" 2^>nul') do (
                if "%JAVA_HOME%"=="" (
                    if exist "%ProgramFiles%\%%~V\%%D\jmods\java.base.jmod" set "JAVA_HOME=%ProgramFiles%\%%~V\%%D"
                )
            )
        )
    )
)

REM Step E: Scan user profile JDK locations (.jdks, AppData\Local\Programs, Scoop)
if "%JAVA_HOME%"=="" (
    for %%B in (
        "%USERPROFILE%\.jdks"
        "%LOCALAPPDATA%\Programs\Eclipse Adoptium"
        "%LOCALAPPDATA%\Programs\Java"
        "%LOCALAPPDATA%\Programs\Microsoft"
        "%LOCALAPPDATA%\Programs\BellSoft"
        "%LOCALAPPDATA%\Programs\Amazon Corretto"
        "%USERPROFILE%\scoop\apps\temurin-lts-jdk"
        "%USERPROFILE%\scoop\apps\temurin21-jdk"
        "%USERPROFILE%\scoop\apps\openjdk"
    ) do (
        if exist "%%~B" (
            for /f "delims=" %%D in ('dir /b /ad /o-n "%%~B" 2^>nul') do (
                if "%JAVA_HOME%"=="" (
                    if exist "%%~B\%%D\jmods\java.base.jmod" set "JAVA_HOME=%%~B\%%D"
                )
            )
        )
    )
)

:java_ready
if not "%JAVA_HOME%"=="" goto :check_jmods
echo ERROR: Could not auto-detect a 64-bit JDK 17+ on this PC.
echo Please install a 64-bit JDK 17+ ^(e.g. Eclipse Adoptium Temurin 21 from https://adoptium.net^)
echo or set JAVA_HOME before running:  set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21..."
goto :fail

:check_jmods
if exist "%JAVA_HOME%\jmods\java.base.jmod" goto :jdk_ok
echo ERROR: "%JAVA_HOME%\jmods" not found. Ensure JAVA_HOME points to a full JDK 17+, not a JRE.
goto :fail

:jdk_ok
echo [1/4] Using JDK: "%JAVA_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM ============================================================================
REM 2. Auto-detect Maven (mvn.cmd) or download portable Maven if missing
REM ============================================================================
set "MVN_CMD="
for /f "delims=" %%M in ('where mvn.cmd 2^>nul') do if "%MVN_CMD%"=="" set "MVN_CMD=%%M"
if "%MVN_CMD%"=="" if not "%MAVEN_HOME%"=="" if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
if "%MVN_CMD%"=="" if not "%M2_HOME%"=="" if exist "%M2_HOME%\bin\mvn.cmd" set "MVN_CMD=%M2_HOME%\bin\mvn.cmd"
if "%MVN_CMD%"=="" if exist "%~dp0.mvn\apache-maven-3.9.9\bin\mvn.cmd" set "MVN_CMD=%~dp0.mvn\apache-maven-3.9.9\bin\mvn.cmd"
if "%MVN_CMD%"=="" if exist "%USERPROFILE%\scoop\apps\maven\current\bin\mvn.cmd" set "MVN_CMD=%USERPROFILE%\scoop\apps\maven\current\bin\mvn.cmd"
if "%MVN_CMD%"=="" if exist "C:\ProgramData\chocolatey\bin\mvn.cmd" set "MVN_CMD=C:\ProgramData\chocolatey\bin\mvn.cmd"

if "%MVN_CMD%"=="" (
    for %%D in ("%ProgramFiles%\Apache*" "%ProgramFiles%\Maven*" "C:\Apache*" "C:\Maven*" "C:\Tools\apache-maven*") do (
        for /f "delims=" %%F in ('dir /b /s "%%~D\mvn.cmd" 2^>nul') do if "%MVN_CMD%"=="" set "MVN_CMD=%%F"
    )
)

if not "%MVN_CMD%"=="" goto :mvn_ready
echo Maven not found on PATH — downloading portable Apache Maven 3.9.9 into .mvn\ ...
if not exist "%~dp0.mvn" mkdir "%~dp0.mvn"
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip' -OutFile '%~dp0.mvn\maven.zip'; Expand-Archive -Path '%~dp0.mvn\maven.zip' -DestinationPath '%~dp0.mvn' -Force; Remove-Item '%~dp0.mvn\maven.zip' -Force"
if exist "%~dp0.mvn\apache-maven-3.9.9\bin\mvn.cmd" set "MVN_CMD=%~dp0.mvn\apache-maven-3.9.9\bin\mvn.cmd"

:mvn_ready
if not "%MVN_CMD%"=="" goto :run_mvn
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0installer\inject-client-id.ps1" || goto :fail
set "MAVEN_OPTS=-Dorg.slf4j.simpleLogger.log.org.apache.maven.repository.internal.DefaultArtifactDescriptorReader=error -Dorg.slf4j.simpleLogger.log.org.apache.maven.internal.impl.resolver.DefaultArtifactDescriptorReader=error -Dorg.slf4j.simpleLogger.log.org.apache.maven.model.building=error"
echo ERROR: Maven ^(mvn.cmd^) could not be found or downloaded.
goto :fail

:run_mvn
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0installer\inject-client-id.ps1" || goto :fail
set "MAVEN_OPTS=-Dorg.slf4j.simpleLogger.log.org.apache.maven.repository.internal.DefaultArtifactDescriptorReader=error -Dorg.slf4j.simpleLogger.log.org.apache.maven.internal.impl.resolver.DefaultArtifactDescriptorReader=error -Dorg.slf4j.simpleLogger.log.org.apache.maven.model.building=error"
echo [2/4] Building with Maven: "%MVN_CMD%"
call "%MVN_CMD%" clean package || goto :fail

REM ============================================================================
REM 3. Build minimal Java runtime with jlink and prune unused tools/libraries
REM ============================================================================
echo [3/4] Building compact modular runtime image ^(target\runtime^)...
if exist target\runtime rmdir /s /q target\runtime
set "JLINK_COMPRESS=--compress=2"
"%JAVA_HOME%\bin\jlink.exe" --help 2>&1 | findstr /c:"zip-" >nul && set "JLINK_COMPRESS=--compress=zip-9"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0installer\optimize-runtime.ps1" -PreJlink
"%JAVA_HOME%\bin\jlink.exe" --module-path "target\modules;%JAVA_HOME%\jmods" --add-modules site.kpeclub.launcher,jdk.crypto.ec,jdk.crypto.mscapi --output target\runtime --strip-debug %JLINK_COMPRESS% --no-header-files --no-man-pages || goto :fail
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0installer\optimize-runtime.ps1" -PostJlink

REM Prune unused JDK CLI tools, accessibility bridges, sound/splash DLLs, and legal notices to minimize size
if exist "target\runtime\legal" rmdir /s /q "target\runtime\legal"
for %%E in (keytool.exe kinit.exe klist.exe ktab.exe jabswitch.exe jaccessinspector.exe jaccesswalker.exe rmiregistry.exe jrunscript.exe) do (
    if exist "target\runtime\bin\%%E" del /f /q "target\runtime\bin\%%E" >nul 2>&1
)
for %%D in (jsound.dll splashscreen.dll jawt.dll rmi.dll dt_shmem.dll dt_socket.dll jdwp.dll javaaccessbridge.dll windowsaccessbridge-64.dll windowsaccessbridge-32.dll w2k_lsa_auth.dll sspi_bridge.dll) do (
    if exist "target\runtime\bin\%%D" del /f /q "target\runtime\bin\%%D" >nul 2>&1
)

REM ============================================================================
REM 4. Auto-detect Inno Setup (6 or 7) and compile installer
REM ============================================================================
set "ISCC="
if exist "%LOCALAPPDATA%\Programs\Inno Setup 7\ISCC.exe" set "ISCC=%LOCALAPPDATA%\Programs\Inno Setup 7\ISCC.exe"
if exist "C:\Program Files (x86)\Inno Setup 7\ISCC.exe" set "ISCC=C:\Program Files (x86)\Inno Setup 7\ISCC.exe"
if exist "C:\Program Files\Inno Setup 7\ISCC.exe" set "ISCC=C:\Program Files\Inno Setup 7\ISCC.exe"
if exist "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" set "ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
if exist "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" set "ISCC=C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
if exist "C:\Program Files\Inno Setup 6\ISCC.exe" set "ISCC=C:\Program Files\Inno Setup 6\ISCC.exe"
if "%ISCC%"=="" (
    for /f "delims=" %%I in ('where ISCC.exe 2^>nul') do if "%ISCC%"=="" set "ISCC=%%I"
)
if not "%ISCC%"=="" goto :run_iscc
echo ERROR: Inno Setup ^(ISCC.exe^) not found. Install Inno Setup 6 or 7, or open KPEClubLauncher.iss in Inno Setup now that target\runtime is built.
goto :fail

:run_iscc
echo [4/5] Preparing KPE Club Publisher Certificate...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0installer\sign-installer.ps1" -PrepareCertOnly
echo [5/5] Compiling and signing installer with Inno Setup: "%ISCC%"
"%ISCC%" KPEClubLauncher.iss || goto :fail
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0installer\sign-installer.ps1" -InstallerExe "%~dp0Output\KPE-Club-Launcher-Setup-1.8.1-x64.exe"
echo Done! Signed installer created at Output\KPE-Club-Launcher-Setup-1.8.1-x64.exe
exit /b 0
:fail
echo BUILD FAILED
exit /b 1
