@echo off
cd /d "%~dp0"

echo === Puppy AI ===
echo.

echo 1. Compiling...
call mvn clean compile
if %ERRORLEVEL% neq 0 (
    echo Compilation failed. Exiting.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo 3. Launching Puppy AI...
call mvn javafx:run

pause
