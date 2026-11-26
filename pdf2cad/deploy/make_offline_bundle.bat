@echo off
rem Chay tren may CO Internet (cung Windows 64-bit, cung phien ban Python voi server)
rem de tai san thu vien vao thu muc wheels\. Chep ca thu muc pdf2cad sang server roi chay install_server.bat.
cd /d "%~dp0.."
python -m pip download -d wheels ".[web]" setuptools wheel || (pause & exit /b 1)
python -m pip download -d wheels onnxruntime-directml
echo Xong: thu muc wheels\
pause
