@echo off
rem Mo giao dien pdf2cad. Co the keo tha file PDF len file .bat nay.
cd /d "%~dp0"
rem OCR mac dinh tu dong (uu tien GPU khi co card roi); install_gpu.bat tao co ep GPU / CPU
if exist ".venv\ocr_gpu.on" set PDF2CAD_OCR_DEVICE=gpu
if exist ".venv\ocr_cpu.on" set PDF2CAD_OCR_DEVICE=cpu
if not exist ".venv\Scripts\pythonw.exe" (
    echo Chua co .venv. Dang tao va cai dat...
    python -m venv .venv || (echo Can cai Python 3.10+ & pause & exit /b 1)
    ".venv\Scripts\python.exe" -m pip install -e ".[gui]" || (pause & exit /b 1)
)
".venv\Scripts\python.exe" -c "import PySide6, rapidocr" 2>nul || ".venv\Scripts\python.exe" -m pip install -e ".[gui]"
if not exist ".venv\ocr_cpu.on" call "%~dp0ensure_gpu.bat" ".venv\Scripts\python.exe"
start "" ".venv\Scripts\pythonw.exe" -m pdf2cad gui %*
