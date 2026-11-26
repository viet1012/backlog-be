@echo off
rem ===== Cau hinh pdf2cad (sua roi chay lai update_server.bat; run_server.bat cung doc file nay) =====
rem Cong web: client truy cap http://<IP-server>:%PORT%
set PORT=5020
rem So job (lan upload) xu ly cung luc
set JOBS=2
rem So trang xu ly song song trong 1 job (moi worker ~1-2GB RAM)
set WORKERS=4
rem Thu muc du lieu: file upload, ket qua, log, mau bot da hoc (giu lai khi cap nhat/go)
set DATA=C:\pdf2cad_data
rem OCR: auto (uu tien GPU khi co card roi NVIDIA/AMD/Intel Arc, loi thi ve CPU) | gpu (ep GPU) | cpu
set OCR_DEVICE=auto
rem Bot chat dung LLM cuc bo (LM Studio: tab Developer -> Start Server, cong 1234)
rem   LLM: auto (dung khi LM Studio chay, khong thi bot luat) | on | off
set LLM=auto
set LLM_URL=http://127.0.0.1:1234/v1
rem   Ten model trong LM Studio; de trong = tu chon (uu tien qwen3.5-9b, gpt-oss-20b, qwen3.6-27b)
set LLM_MODEL=
rem Dung luong upload toi da (MB)
set MAX_UPLOAD=200
rem Xoa ket qua cu sau bao nhieu gio
set TTL=24
rem Ten tac vu chay nen (Task Scheduler) va ten rule firewall
set TASK=pdf2cad-server
