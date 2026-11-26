@echo off
rem Tat server (khong go cai dat). Bat lai: start_server.bat
call "%~dp0server_config.bat"
if not exist "%DATA%" mkdir "%DATA%"
echo stop> "%DATA%\stop.flag"
schtasks /end /tn "%TASK%" >nul 2>&1
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='python.exe' or Name='pythonw.exe'\" | Where-Object { $_.CommandLine -like '*pdf2cad serve*' } | ForEach-Object { taskkill /PID $_.ProcessId /T /F | Out-Null }"
if /i not "%1"=="quiet" (echo Da tat server. & pause)
