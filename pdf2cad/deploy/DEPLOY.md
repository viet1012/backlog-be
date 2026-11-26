# Triển khai pdf2cad lên server (client truy cập qua IP:PORT)

## Yêu cầu
- Windows 10/11 hoặc Windows Server, 64-bit.
- Python 3.10–3.12 (khi cài, tick **Add python.exe to PATH**).
- Cổng mặc định **5020**. Đổi trong `deploy\server_config.bat` nếu bị trùng (`run_server.bat` cũng đọc file này).

## Cài đặt (một lần)
1. Chép cả thư mục `pdf2cad` lên server, ví dụ `C:\pdf2cad`. Nên để ở ổ C, không để trong thư mục cá nhân.
2. Sửa cấu hình `deploy\server_config.bat` nếu cần: `PORT`, `WORKERS`, `DATA`, `OCR_DEVICE`.
3. Chuột phải `deploy\install_server.bat` → **Run as administrator**. Script sẽ:
   - tạo `.venv` và cài thư viện;
   - mở cổng trên Windows Firewall, **chỉ cho mạng LAN cùng dải**;
   - đăng ký Task Scheduler `pdf2cad-server` chạy bằng tài khoản SYSTEM khi khởi động máy, tự chạy lại nếu server tắt;
   - in ra địa chỉ cho client.

Server **không có Internet**: trên một máy có mạng (cùng phiên bản Python), chạy `deploy\make_offline_bundle.bat`. Chép cả thư mục, kèm `wheels\`, sang server rồi cài như trên.

## Client sử dụng
Mở trình duyệt (Chrome/Edge) và vào `http://<IP-server>:5020`, ví dụ `http://192.168.122.16:5020`. Client không cần cài gì.

## Vận hành
| Việc | File |
|---|---|
| Xem trạng thái, IP, log | `deploy\status_server.bat` |
| Tắt / bật | `deploy\stop_server.bat` / `deploy\start_server.bat` |
| Cập nhật code mới | chép đè code (hoặc `git pull`) rồi chạy `deploy\update_server.bat` (Admin) |
| Gỡ | `deploy\uninstall_server.bat`, dữ liệu vẫn được giữ lại |

Thư mục dữ liệu (`C:\pdf2cad_data`) gồm:
- `jobs\`: file tạm, tự xoá sau `TTL` giờ;
- `logs\server.log`: log của server;
- `templates\`: các mẫu đã "Dạy bot". **Nên sao lưu thư mục này.**

## Lưu ý
- **Bảo mật:** web không có đăng nhập. Chỉ dùng trong mạng nội bộ, không NAT/mở cổng ra Internet. Rule firewall đã giới hạn ở `localsubnet`. Nếu client ở VLAN/dải IP khác, sửa rule trong *Windows Defender Firewall → Inbound Rules → pdf2cad-server → Scope*.
- **GPU:** mặc định `OCR_DEVICE=auto` — server có card rời (NVIDIA/AMD/Intel Arc) thì OCR chạy GPU, không thì CPU; `gpu` = ép GPU, `cpu` = ép CPU. Gói `onnxruntime-directml` được cài tự động. Dịch vụ chạy bằng SYSTEM nên DirectML có thể không khởi tạo được. Khi đó tool tự quay về CPU; kiểm tra bằng `"ocr_device"` trong status. LM Studio đang chiếm khoảng 12 GB VRAM, nên giữ `WORKERS` ≤ 4 khi bật GPU.
- **RAM:** mỗi worker dùng khoảng 1–2 GB khi OCR bản scan. Với 64 GB RAM, có thể đặt `JOBS=3`, `WORKERS=6`.
- **Client không vào được:**
  1. Trên server, mở `http://localhost:5020`.
  2. Chạy `status_server.bat`.
  3. Từ client, ping IP server.
  4. Kiểm tra firewall hoặc phần mềm diệt virus.
