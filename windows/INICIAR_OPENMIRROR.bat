@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel%==0 (
    set "PYCMD=py -3"
) else (
    where python >nul 2>nul
    if errorlevel 1 (
        echo Python nao encontrado. Instale Python 3.11 ou superior e marque "Add to PATH".
        pause
        exit /b 1
    )
    set "PYCMD=python"
)
if not exist ".venv\Scripts\python.exe" (
    echo Criando ambiente Python...
    %PYCMD% -m venv .venv
    if errorlevel 1 goto error
)
".venv\Scripts\python.exe" -m pip install -r requirements.txt -q
if errorlevel 1 goto error
echo Iniciando OpenMirror...
".venv\Scripts\python.exe" openmirror_windows.py
if errorlevel 1 goto error
exit /b 0
:error
echo.
echo Erro ao iniciar. Confira a conexao com a internet na instalacao inicial.
pause
exit /b 1
