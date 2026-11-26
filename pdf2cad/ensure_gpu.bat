@echo off
rem Dam bao OCR chay duoc tren GPU (DirectML: GPU Intel/AMD/NVIDIA tren Windows 10/11).
rem Thay onnxruntime (CPU) bang onnxruntime-directml neu chua co. Khong cai duoc thi giu CPU.
rem   ensure_gpu.bat <python.exe> [thu muc wheels de cai offline]
setlocal
set "PYX=%~1"
if "%PYX%"=="" set "PYX=%~dp0.venv\Scripts\python.exe"
"%PYX%" -c "import onnxruntime as o,sys; sys.exit(0 if 'DmlExecutionProvider' in o.get_available_providers() else 1)" >nul 2>&1 && exit /b 0
echo Cai goi OCR tren GPU (onnxruntime-directml) ...
rem 2 goi dung chung module "onnxruntime": go ban CPU truoc, cai lai DirectML (force) cho du file
"%PYX%" -m pip uninstall -y onnxruntime >nul 2>&1
if not "%~2"=="" (
    "%PYX%" -m pip install --force-reinstall --no-deps --no-index --find-links "%~2" onnxruntime-directml >nul 2>&1 && goto ok
) else (
    "%PYX%" -m pip install --force-reinstall --no-deps onnxruntime-directml >nul 2>&1 && goto ok
)
echo   ! Khong cai duoc onnxruntime-directml - OCR se chay CPU
if not "%~2"=="" (
    "%PYX%" -m pip install --no-index --find-links "%~2" onnxruntime >nul 2>&1
) else (
    "%PYX%" -m pip install onnxruntime >nul 2>&1
)
exit /b 0
:ok
echo   Da cai - OCR uu tien GPU (tu quay ve CPU neu GPU loi)
exit /b 0
