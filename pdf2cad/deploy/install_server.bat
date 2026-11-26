@echo off
rem ===== Cai pdf2cad thanh dich vu web tren server Windows =====
rem Chay bang quyen Administrator (chuot phai -> Run as administrator).
rem Sau khi cai: client mo trinh duyet http://<IP-server>:<PORT>
setlocal
net session >nul 2>&1 || (echo Can chay bang quyen Administrator. & pause & exit /b 1)
cd /d "%~dp0.."
call "%~dp0server_config.bat"
set APP=%CD%
set PY=%APP%\.venv\Scripts\python.exe

echo [1/5] Tao moi truong Python ...
if not exist "%PY%" (
    py -3.12 -m venv .venv 2>nul || py -3.11 -m venv .venv 2>nul || py -3.10 -m venv .venv 2>nul || python -m venv .venv
)
if not exist "%PY%" (echo Khong tao duoc .venv - hay cai Python 3.10-3.12, tick Add to PATH. & pause & exit /b 1)

echo [2/5] Cai thu vien ...
if exist "%APP%\wheels" (
    echo   - cai offline tu thu muc wheels
    "%PY%" -m pip install --no-index --find-links "%APP%\wheels" -e ".[web]" || (pause & exit /b 1)
) else (
    "%PY%" -m pip install --upgrade pip >nul
    "%PY%" -m pip install -e ".[web]" || (pause & exit /b 1)
)
if /i not "%OCR_DEVICE%"=="cpu" (
    if exist "%APP%\wheels" (call "%APP%\ensure_gpu.bat" "%PY%" "%APP%\wheels") else (call "%APP%\ensure_gpu.bat" "%PY%")
)

echo [3/5] Thu muc du lieu %DATA% ...
if not exist "%DATA%\logs" mkdir "%DATA%\logs"
if not exist "%DATA%\templates" if exist "%APP%\templates\learned" xcopy "%APP%\templates\learned" "%DATA%\templates\" /E /I /Q /Y >nul

echo [4/5] Mo cong %PORT% tren Windows Firewall (chi mang LAN noi bo) ...
netsh advfirewall firewall delete rule name="%TASK%" >nul 2>&1
netsh advfirewall firewall add rule name="%TASK%" dir=in action=allow protocol=TCP localport=%PORT% remoteip=localsubnet profile=any >nul || echo   ! Khong them duoc rule firewall

echo [5/5] Dang ky chay nen khi khoi dong may ...
call "%~dp0stop_server.bat" quiet
schtasks /create /tn "%TASK%" /tr "\"%APP%\deploy\run_service.bat\"" /sc onstart /ru SYSTEM /rl HIGHEST /f >nul || (pause & exit /b 1)
schtasks /run /tn "%TASK%" >nul

echo.
echo Dang khoi dong server (lan dau co the mat 20-40 giay) ...
for /l %%i in (1,1,30) do (
    curl -s -m 2 http://127.0.0.1:%PORT%/api/health >nul 2>&1 && goto ok
    timeout /t 2 /nobreak >nul
)
echo ! Server chua phan hoi. Xem log: %DATA%\logs\server.log
pause & exit /b 1
:ok
echo.
echo ===== CAI DAT XONG =====
call "%~dp0status_server.bat" nopause
pause
