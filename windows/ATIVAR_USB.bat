@echo off
setlocal
set "ADB=adb"
where adb >nul 2>nul
if errorlevel 1 (
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    ) else if exist "%LOCALAPPDATA%\OpenDroid\Sdk\platform-tools\adb.exe" (
        set "ADB=%LOCALAPPDATA%\OpenDroid\Sdk\platform-tools\adb.exe"
    ) else (
        echo ADB nao encontrado. Instale Android SDK Platform-Tools.
        pause
        exit /b 1
    )
)
echo Conecte o Android via USB e autorize a depuracao USB.
"%ADB%" devices
"%ADB%" reverse tcp:8765 tcp:8765
if errorlevel 1 (
    echo Erro na conexao USB. Ative Depuracao USB e autorize este computador.
    pause
    exit /b 1
)
echo.
echo Pronto! No Android, use o endereco: 127.0.0.1:8765
echo Inicie o servidor OpenMirror Windows antes de conectar.
pause
