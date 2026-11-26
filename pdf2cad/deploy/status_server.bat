@echo off
rem Kiem tra server: trang thai, dia chi cho client, log gan nhat
cd /d "%~dp0.."
call "%~dp0server_config.bat"
echo --- Trang thai ---
curl -s -m 3 http://127.0.0.1:%PORT%/api/health && echo. || echo   SERVER KHONG CHAY (xem log ben duoi)
echo.
echo --- Dia chi cho may client ---
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /r /c:"IPv4"') do (
    for /f "tokens=*" %%b in ("%%a") do echo   http://%%b:%PORT%
)
echo.
echo --- 15 dong log cuoi (%DATA%\logs\server.log) ---
if exist "%DATA%\logs\server.log" powershell -NoProfile -Command "Get-Content -Encoding UTF8 -Tail 15 '%DATA%\logs\server.log'"
if /i not "%1"=="nopause" pause
