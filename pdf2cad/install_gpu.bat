@echo off
rem OCR mac dinh UU TIEN GPU khi may co card roi (DirectML), loi thi tu ve CPU.
rem File nay: cai goi GPU neu thieu, do toc do CPU so voi GPU tren may nay, roi chon tu dong / GPU / CPU
rem (card tich hop Intel/AMD dung chung RAM: do thuc te OCR cham hon CPU ~3 lan).
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (echo Chua co .venv - hay chay run_server.bat truoc & pause & exit /b 1)
set PY=.venv\Scripts\python.exe
echo [1/3] Kiem tra goi onnxruntime-directml ...
call "%~dp0ensure_gpu.bat" "%PY%"
echo.
echo [2/3] Do toc do OCR tren 1 trang mau (CPU so voi GPU) ...
if exist "samples\21.DR-TE-20352-00 (DSJC-B).pdf" (
    %PY% -m pdf2cad bench-ocr "samples\21.DR-TE-20352-00 (DSJC-B).pdf" --device cpu gpu
) else (
    %PY% -m pdf2cad bench-ocr "samples\sample_drawing.pdf" --device cpu gpu
)
echo.
echo [3/3] Chon thiet bi OCR:
echo   A = Tu dong (mac dinh): dung GPU khi may co card roi NVIDIA/AMD/Intel Arc, con lai dung CPU
echo   G = Luon dung GPU (ke ca card tich hop) - chon neu dong [gpu] o tren nhanh hon [cpu]
echo   C = Luon dung CPU
choice /c AGC /m "Chon"
del ".venv\ocr_gpu.on" >nul 2>&1
del ".venv\ocr_cpu.on" >nul 2>&1
if errorlevel 3 (echo on> ".venv\ocr_cpu.on" & echo Da chon CPU.& goto done)
if errorlevel 2 (echo on> ".venv\ocr_gpu.on" & echo Da chon GPU.& goto done)
echo Da chon tu dong.
:done
pause
