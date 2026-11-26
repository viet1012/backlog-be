@echo off
rem Cap nhat sau khi chep code moi (hoac git pull) vao thu muc nay. Chay bang Administrator.
net session >nul 2>&1 || (echo Can chay bang quyen Administrator. & pause & exit /b 1)
cd /d "%~dp0.."
if exist .git (git pull 2>nul)
call "%~dp0install_server.bat"
