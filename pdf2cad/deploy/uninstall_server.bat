@echo off
rem Go dich vu + rule firewall. GIU LAI thu muc du lieu (%DATA%) va ma nguon.
net session >nul 2>&1 || (echo Can chay bang quyen Administrator. & pause & exit /b 1)
call "%~dp0server_config.bat"
call "%~dp0stop_server.bat" quiet
schtasks /delete /tn "%TASK%" /f
netsh advfirewall firewall delete rule name="%TASK%"
echo Da go. Du lieu van o %DATA% (xoa tay neu khong can).
pause
