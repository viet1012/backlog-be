@echo off
rem Duoc Task Scheduler goi khi khoi dong may. Tu chay lai server neu bi tat/loi.
rem Khong can chay tay file nay - dung install_server.bat / status_server.bat.
cd /d "%~dp0.."
call "%~dp0server_config.bat"
set APP=%CD%
set PY=%APP%\.venv\Scripts\python.exe
set PDF2CAD_DATA=%DATA%
set PDF2CAD_TEMPLATES=%APP%\templates
set PDF2CAD_LEARNED=%DATA%\templates
set PDF2CAD_OCR_DEVICE=%OCR_DEVICE%
set PDF2CAD_LLM=%LLM%
set PDF2CAD_LLM_URL=%LLM_URL%
set PDF2CAD_LLM_MODEL=%LLM_MODEL%
set PYTHONIOENCODING=utf-8
if not exist "%DATA%\logs" mkdir "%DATA%\logs"
del "%DATA%\stop.flag" >nul 2>&1
:loop
"%PY%" -m pdf2cad serve --host 0.0.0.0 --port %PORT% --jobs %JOBS% --workers %WORKERS% --data-dir "%DATA%" --max-upload %MAX_UPLOAD% --ttl %TTL% --log-file "%DATA%\logs\server.log"
if exist "%DATA%\stop.flag" goto :eof
echo %date% %time% server dung (ma %errorlevel%), chay lai sau 10 giay>> "%DATA%\logs\service.log"
timeout /t 10 /nobreak >nul
goto loop
