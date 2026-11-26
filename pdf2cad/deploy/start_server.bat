@echo off
rem Bat lai server da cai (chay bang Administrator)
call "%~dp0server_config.bat"
schtasks /run /tn "%TASK%" || (echo Chua cai - chay install_server.bat & pause & exit /b 1)
echo Da bat. Doi vai giay roi chay status_server.bat de kiem tra.
pause
