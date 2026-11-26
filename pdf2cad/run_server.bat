@echo off
rem Chay web server pdf2cad (khong can nhap gi).
rem   Cau hinh: deploy\server_config.bat (PORT mac dinh 5020, LLM, OCR...) - dung chung voi dich vu tren server.
rem   run_server.bat               -> cong theo cau hinh, cho ca mang LAN (http://<IP-may>:<port>)
rem   run_server.bat 9000          -> ep cong 9000
rem   run_server.bat local         -> chi may nay truy cap
rem   run_server.bat 9000 local    -> cong 9000 + chi may nay (thu tu tham so tuy y)
rem   Cong bi chuong trinh khac chiem thi moi hoi cong khac.
rem   Can mo cong tuong ung tren Windows Firewall de may khac truy cap.
setlocal
cd /d "%~dp0"
rem In tieng Viet/Nhat an toan khi output bi chuyen huong (cong cu quan ly tien trinh, log)
set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8
set PORT=5020
if exist "deploy\server_config.bat" call "deploy\server_config.bat"
set CFG_PORT=%PORT%
set "PORT="
if defined OCR_DEVICE set PDF2CAD_OCR_DEVICE=%OCR_DEVICE%
if defined LLM set PDF2CAD_LLM=%LLM%
if defined LLM_URL set PDF2CAD_LLM_URL=%LLM_URL%
if defined LLM_MODEL set PDF2CAD_LLM_MODEL=%LLM_MODEL%
rem OCR: install_gpu.bat tao co ep GPU / CPU (uu tien hon cau hinh)
if exist ".venv\ocr_gpu.on" set PDF2CAD_OCR_DEVICE=gpu
if exist ".venv\ocr_cpu.on" set PDF2CAD_OCR_DEVICE=cpu
set HOST=0.0.0.0

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="local" (set HOST=127.0.0.1) else if /i "%~1"=="lan" (set HOST=0.0.0.0) else (set "PORT=%~1")
shift
goto parse
:parsed
if "%PORT%"=="" set PORT=%CFG_PORT%
goto check

:ask
set "PORT="
set /p "PORT=Nhap cong khac (vd 5021): "
if "%PORT%"=="" goto ask

:check
echo %PORT%| findstr /r "^[0-9][0-9]*$" >nul || (echo Cong "%PORT%" khong hop le. & goto ask)
if %PORT% LSS 1 (echo Cong phai tu 1 den 65535. & goto ask)
if %PORT% GTR 65535 (echo Cong phai tu 1 den 65535. & goto ask)
netstat -ano | findstr /r /c:":%PORT% .*LISTENING" >nul && (echo Cong %PORT% dang duoc chuong trinh khac su dung ^(co the server pdf2cad da chay san^). & goto ask)

if exist ".venv\Scripts\python.exe" (
    ".venv\Scripts\python.exe" -c "pass" >nul 2>&1 || (
        echo .venv tro toi Python khong ton tai tren may nay. Dang xoa va tao lai...
        rmdir /s /q ".venv"
    )
)
if not exist ".venv\Scripts\python.exe" (
    echo Chua co .venv. Dang tao va cai dat...
    python -m venv .venv || (echo Can cai Python 3.10+ & pause & exit /b 1)
)
".venv\Scripts\python.exe" -c "import fastapi, uvicorn, multipart, rapidocr" 2>nul || ".venv\Scripts\python.exe" -m pip install -e ".[web]" || (pause & exit /b 1)
if not exist ".venv\ocr_cpu.on" call "%~dp0ensure_gpu.bat" ".venv\Scripts\python.exe"

echo.
echo Server: http://localhost:%PORT%
if "%HOST%"=="0.0.0.0" echo Mang LAN: http://^<IP-may^>:%PORT%  (nho mo cong %PORT% tren Windows Firewall)
echo.
".venv\Scripts\python.exe" -m pdf2cad serve --host %HOST% --port %PORT% --open
pause
