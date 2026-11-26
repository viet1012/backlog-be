# pdf2cad

Chuyển bản vẽ kỹ thuật PDF sang DXF 2D (mm, tỷ lệ 1:1), làm nền tảng cho bước dựng 3D.
Chạy hoàn toàn cục bộ, không gửi dữ liệu bản vẽ ra ngoài.

## Cài đặt

Yêu cầu Python 3.10+.

```bash
python -m venv .venv
.venv\Scripts\activate            # Windows  (Linux/macOS: source .venv/bin/activate)
pip install -e ".[raster]"        # lõi + xử lý ảnh scan
pip install -e ".[dev]"           # thêm công cụ test & benchmark
```

## Vẽ lại từ mẫu tham số (chat)

Bản vẽ họ sản phẩm (kích thước ghi bằng ký hiệu L, S, ØH… + bảng giá trị) được lưu thành **mẫu**
trong `templates/<tên>/` (`base.dxf` + `template.json`). Sau đó chỉ cần gõ yêu cầu, tool vẽ lại
bản vẽ **đúng tỷ lệ 1:1** theo kích thước mong muốn:

```bash
pdf2cad draw                                   # liệt kê mẫu
pdf2cad draw dsjc-b "No.16 L=80 P=15" -o out   # hoặc: "số 13 dài 70 H=18"
```

**Bot dùng LLM cục bộ (LM Studio)**: khi LM Studio bật server (tab *Developer → Start Server*, cổng 1234),
câu chat được LLM hiểu (tiếng Việt/Anh, câu tự do) rồi gọi công cụ của bot: đặt/đổi kích thước, xem bảng,
giải thích tham số, bản vẽ mới, lấy file. **Tra bảng, quy tắc NOTE, vẽ DXF và đo lại vẫn do pdf2cad làm** nên
LLM không bịa số liệu. Model tự chọn (cần hỗ trợ tool calling): `qwen3.5-9b` → `gpt-oss-20b` → `qwen3.6-27b`…
LM Studio tắt/lỗi thì tự quay về bot theo luật bên dưới. Dòng dưới tên bot hiện "AI <model>" hoặc "bot luật".
Cấu hình: `PDF2CAD_LLM=auto|on|off`, `PDF2CAD_LLM_URL` (mặc định `http://127.0.0.1:1234/v1`),
`PDF2CAD_LLM_MODEL`, `PDF2CAD_LLM_TIMEOUT` (giây); server: `LLM`, `LLM_URL`, `LLM_MODEL` trong
`deploy\server_config.bat`. Chỉ trỏ tới server LLM trong mạng nội bộ.

Bot theo luật (dự phòng, chạy cục bộ, không cần LLM):
- Nhớ ngữ cảnh: các kích thước đã nói được giữ cho câu sau; tự hỏi lại khi thiếu No/L kèm gợi ý
  giá trị hợp lệ; trả lời ngắn ("16", "60") được hiểu theo câu hỏi trước.
- Hiểu câu sửa đổi: "tăng L thêm 10", "giảm chiều dài xuống 55", "dài hơn 5", "H+2", "đổi P thành 15",
  "Đổi No", "bản vẽ mới"; hỏi đáp: "P là gì?", "có những size nào?", "tải file".
- Mỗi lần vẽ: báo thay đổi so với bản trước, giá trị tự chọn theo bảng, cảnh báo ngoài chuẩn,
  kết quả đo lại; nút tải DXF ngay trong tin nhắn, hình hiện ở khung xem bên phải.
- API: `POST /api/bot {template, session, message}`.

Cách hoạt động:
- `template.json` ghi mỗi ký hiệu kích thước đo giữa 2 điểm mốc nào trên hình (dọc trục `x_dims`,
  đường kính quanh trục `r_dims`), bảng tham số theo `No.`, quy tắc NOTE (`rules`) và ràng buộc (`checks`).
- Khi vẽ: giải hệ tìm vị trí mới của các điểm mốc → co giãn tuyến tính từng đoạn toàn bộ hình chiếu
  (đường, cung, nét cắt, chữ) → thay ký hiệu bằng số, dời dung sai cho khỏi đè, cập nhật khung tên
  → đo lại từng kích thước để xác nhận khớp.
- Tham số thiếu lấy theo bảng; NOTE tự áp dụng (vd No.8 L=40 ⇒ S=15); giá trị ngoài bảng vẫn vẽ nhưng cảnh báo.

Hạn chế hiện tại: mẫu DSJC-B được dựng bán thủ công (điểm mốc lấy tự động theo hình học, ánh xạ
ký hiệu ↔ đường kích thước do người xác nhận); ℓ = chiều sâu ren là **giả định**; chữ chú thích giữ cỡ
nên đôi chỗ có thể chạm nhau; chưa vẽ lại hình chi tiết (DETAIL B) và chưa xuất 3D.

## Tốc độ OCR và GPU

OCR là bước chậm duy nhất (đọc PDF vector chỉ vài ms). Trên máy 2 nhân: ~20 s/trang A4 300 dpi
(phát hiện vùng chữ ~13 s, nhận dạng ~7 s). Máy nhiều nhân hơn sẽ nhanh hơn tương ứng.

- Đã tối ưu: chữ ngắn thử hướng chữ chủ đạo trước, đủ chắc thì dừng (số lần nhận dạng giảm ~50%,
  kết quả giống hệt).
- **GPU (DirectML — Intel/AMD/NVIDIA trên Windows), mặc định `auto`**: trên Windows cài sẵn
  `onnxruntime-directml` (`run_server.bat` / `run_gui.bat` tự thay bản CPU). `auto` dùng GPU khi máy có
  **card rời** (NVIDIA, AMD Radeon RX/Pro, Intel Arc A/B); chỉ có card tích hợp thì dùng CPU — đo trên
  Intel Graphics: GPU 48 s so với CPU 14,5 s/trang. GPU/driver lỗi thì tự quay về CPU.
  `install_gpu.bat` đo CPU so với GPU trên máy bạn rồi cho chọn: tự động / luôn GPU (`.venv\ocr_gpu.on`)
  / luôn CPU (`.venv\ocr_cpu.on`). Hoặc đặt `PDF2CAD_OCR_DEVICE=auto|gpu|cpu`.
- Đo tay: `pdf2cad bench-ocr file.pdf --device cpu gpu`.
- `PDF2CAD_OCR_MAX_SIDE` (mặc định 5000): giảm (vd 3000) nhanh hơn ~20% nhưng dung sai chữ nhỏ dễ đọc sai.
- Lưu ý: GPU tích hợp dùng chung RAM; nếu RAM gần đầy, đặt số tiến trình = 1 (`--workers 1`) để
  không mỗi tiến trình nạp một bản model.
- Cài lại thư viện bằng `pip install -e ".[web]"` sẽ kéo `onnxruntime` (CPU) về đè; chạy lại
  `install_gpu.bat` nếu đã dùng GPU.

## Bản scan (TIFF / PNG / JPG)

Web, app desktop và dòng lệnh nhận trực tiếp `.tif/.tiff/.png/.jpg/.bmp` (TIFF nhiều trang: mỗi trang
1 DXF). Ảnh được đổi sang PDF tạm, khổ giấy tính theo DPI ghi trong ảnh. Trang scan:

- **OCR trước, dò nét sau**: chữ được nhận dạng thành TEXT (`TEXT_OCR`, cần soát ở `TEXT_CHECK`)
  rồi xoá khỏi ảnh, nên bước dò nét (OpenCV) không còn sinh vạch vụn từ chữ. Tỷ lệ "SCALE 1:2"
  đọc được từ chữ OCR.
- **Ký hiệu sửa đổi △n**: tam giác đều 3–9 mm có số bên trong được nhận trước OCR, vẽ lại sạch
  (tam giác + số, layer `REVISION`) và xoá khỏi ảnh — OCR không còn đọc lẫn "△8 30/40" thành "830/40".
  Nhận cả tam giác kẹp trong ô bảng sửa đổi (đáy trùng đường kẻ). Tam giác không có số (▽ nhám, ⚠) giữ nguyên.
- **Xoá dấu mộc** (mặc định bật; tắt: `--keep-stamps` / bỏ chọn trong app, web): vòng tròn 5–30 mm có chữ
  SPC / MassPro / PE hoặc tên tháng (APR...) bên trong; mộc chữ ký đứng cạnh mộc đã xác nhận cũng được nhận.
  Đường kẻ dài hơn mộc đi xuyên qua (khung tên, bảng) được giữ lại. Vòng tròn của bản vẽ không có chữ mộc
  nên không bị xoá. Lưu ý: chữ thật nằm dưới mộc (tên người ký trong bảng) có thể mất theo.
- **Đường nét**: đường ngang/dọc tách bằng lọc hình thái → đoạn thẳng đúng trục; nét xiên còn lại ở `RASTER`.
  Ảnh lệch ≥ 0.2° được xoay thẳng trước.
  - **Độ dày nét** đo bằng tổng độ đậm ảnh xám theo pháp tuyến (`raster.gray_width`, chính xác dưới 1 px, không bị
    lượng tử hoá khi scan 200 dpi phóng lên 300 dpi: nét mảnh = 3.0 px, nét đậm ≈ 4.4–4.6 px).
    **Ngưỡng đậm/mảnh tự tính theo từng trang** (`_thick_threshold`: nét mảnh = độ dày phổ biến nhất, nét đậm = trung vị
    các nét dày hơn rõ rệt) thay cho ngưỡng cố định 0.42 mm (trước đây gần hết đường bao bản scan bị xếp vào `THIN`).
  - **Nét đứt**: dùng cả gạch ngắn 0.8–2.5 mm; dãy ≥ 3 gạch, khe đều → `HIDDEN` (gạch đều ≤ 8 mm, kể cả nét khuất toàn
    gạch 2 mm) / `CENTER` (gạch dài đều xen chấm / gạch ngắn, hoặc gạch dài với khe rộng). Dãy chịu được ảnh còn lệch
    0.3–0.4 mm; cắt bỏ gạch đầu/cuối bất thường (nét chỉ dẫn nối vào). Đường liền bị chữ cắt (gạch/khe không đều) giữ nguyên.
  - **Đường tròn / cung** (`scan_arcs.rebuild_arcs`): nối dây cung LSD + đoạn ngang/dọc ngắn thành chuỗi (gãy ≤ 50°, cùng
    chiều cong) → fit đường tròn; gộp 2 mép của nét đậm, hấp thụ cung lệch, nối khe ≤ 30°; tinh chỉnh tâm/bán kính
    theo đường tâm mực thật; đậm/mảnh theo độ dày đo trên cung. Lục giác (gãy 60°) không bị nhận nhầm.
  - Đo trên `samples/DR-TE-19350-02.tif`: hình chiếu cạnh từ ~20 đoạn RASTER thành 2 `CIRCLE` đồng tâm (r 10.24 / 3.712 mm,
    lệch tâm 0.03 mm); đường bao chi tiết `OUTLINE`; đường tâm `CENTER`; nét khuất lỗ `HIDDEN` đúng chiều dài lỗ.
- **Xoá chữ không làm đứt bảng**: đường kẻ đi xuyên qua vùng chữ được giữ lại.
- **Sửa chữ OCR theo ngữ cảnh**: "二" (OCR đọc nhầm 2 vạch đứng) → `11`; trong chuỗi số `l I | !` → 1,
  `O o` → 0; chuỗi chỉ toàn vạch, ngoặc vuông lẻ, thuật ngữ dính rác ("ISQUARENESS") → `TEXT_CHECK`.
- **Sửa chữ theo ngữ cảnh bản vẽ** (`ocr.fix_terms`, `ocr.harmonize`): đơn vị `1Omm`→`10mm`, `0.Imm`→`0.1mm`;
  vát mép `CO.1~O.2`→`C0.1~0.2`; `JIS B O405-n`→`0405-m`; mã vật liệu (`SKDII`→`SKD11`); từ chuyên ngành IN HOA
  sai đúng 1 ký tự (`APPROVEQ`, `INCREWENTS`, `GERERAL`, `CHÀMFER`); mã đọc đúng ≥ 2 lần trong trang thì sửa chỗ lệch
  ký tự dễ nhầm (`SPLB-8`→`SPLB-B`, `SP8`→`SPB`). Chữ chạm đường kẻ dài (khung bảng) được đọc lại sau khi xoá đường kẻ
  (`[. FUKASAWA]`→`M. FUKASAWA`, `…~1501`→`…~150`). Test: `tests/test_ocr_terms.py`.
- **OCR theo từng ô bảng**: ô = vùng khép kín bởi đường kẻ, nằm trong lưới (≥ 2 ô kề). Chữ OCR trải qua
  ≥ 2 ô hoặc nửa trong nửa ngoài bảng ("DSPLB-B35") được đọc lại riêng từng ô và phần ngoài bảng; ô có mực
  mà không có chữ được đọc lại (chỉ nhận chữ tin cậy ≥ 0.85). Ô 1 dòng chỉ chạy nhận dạng (nhanh).
- **Tỷ lệ ở ô khung tên**: đọc ô dưới/cạnh nhãn "Scale"; ghi `1:2` thì áp dụng, ghi `FREE`/`NTS` thì báo
  cảnh báo "không theo tỷ lệ".
- Hình học từ ảnh scan vẫn chỉ ở mức **tham khảo** (sai số ~0.5 mm, nét đứt/đường tâm bị đứt khúc,
  dấu mộc thành nét). Với chi tiết đơn giản nên dùng **mẫu vẽ mới** bên dưới thay vì dựng lại từ ảnh.

## Mẫu vẽ mới (generator) — SPLB-B, DSPLB-B

Khi bản gốc chỉ là ảnh scan, mẫu `"kind": "generator"` **vẽ mới hoàn toàn** bản vẽ sạch từ bộ tham số
(`src/pdf2cad/gen.py`): hình chiếu đứng + cạnh, **DIMENSION thật** (sửa được trong CAD) kèm dung sai,
khung GD&T, chuẩn A, ký hiệu nhám, vát mép, ghi chú, khung tên; tỷ lệ in 1:k tự chọn cho vừa A4.

| Mẫu | Nguồn | Khoá bảng | Ví dụ chat |
|---|---|---|---|
| `splb-b` | DR-TE-19344-01.tif | D (+ Material ID) | `D=30 L=120` |
| `dsplb-b` | DR-TE-19350-02.tif | D, d (+ C, Y) | `D=30 d=9 L=120` |

- Bảng nhập từ OCR và đối chiếu ảnh gốc; danh sách L giữ đúng dạng trên bản vẽ (`"20~50/55/60~150"`).
  **Giả định**: "a~b (10mm increments)" = a rồi các bội số 10 đến b (vd 15~60 → 15, 20, 30…60). Cần xác nhận.
- Dung sai theo quy tắc: SPLB-B L>200 ⇒ +1.2/+0.6; DSPLB-B d<14 ⇒ ±0.1, d≥14 ⇒ ±0.2.
- Bot hỏi lần lượt từng khoá (D rồi d) với giá trị hợp lệ; đổi D thì d không còn hợp lệ sẽ bị bỏ và hỏi lại.

## Tự học bản vẽ mới ("Dạy bot")

Sau khi chuyển một bản vẽ (PDF/TIFF), bấm **🤖 Dạy bot** ở dòng kết quả trên web (hoặc
`pdf2cad learn ban_ve.tif`). Tool đọc:
- **Khung tên**: Drawing Number, Parts Name, Drawing Title, vật liệu (`MATERIAL:...` trên bảng), nhám.
- **Bảng tham số**: hàng tiêu đề có cột `L` (D, H, T, L ...); cột theo đường kẻ dọc; ô gộp nhiều hàng
  theo đường kẻ ngang; tiêu đề OCR sót/sai ("0" thay "D") suy từ bảng khác cùng số cột.
  Ô không đọc được để trống — bot sẽ **hỏi người dùng** thay vì đoán.
- Mỗi bảng thành 1 mẫu **nháp** (🆕 trong danh sách bot) lưu ở `templates/learned/`
  (`PDF2CAD_LEARNED`). Vẽ mới bằng bộ vẽ trụ / **trụ có mũ** (khi bảng có cột H và T: mũ ΦH dày T, thân ΦD, dài L).

Giới hạn: chỉ hiểu bảng dạng "khoá + các cột số + cột L"; hình dạng chi tiết chỉ trụ / trụ có mũ;
dung sai theo ghi chú chưa được học. Luôn kiểm tra bảng ("Có những size nào?") trước khi dùng.

## Web server (chạy trên trình duyệt)

```bash
pip install -e ".[web]"
pdf2cad serve                                # chỉ máy này:   http://localhost:5020
pdf2cad serve --host 0.0.0.0                 # cả mạng LAN:   http://<IP-máy-chủ>:5020
pdf2cad serve --port 9000                    # cổng khác (hoặc biến môi trường PDF2CAD_PORT)
```

Windows: bấm đúp `run_server.bat` — chạy ngay, **không cần nhập gì**: cổng lấy từ `deploy\server_config.bat`
(`PORT=5020`, dùng chung với dịch vụ trên server), mở cho cả mạng LAN (cần mở cổng trên Windows Firewall).
`run_server.bat 9000` ép cổng khác, `run_server.bat local` chỉ máy này. Chỉ khi cổng đã bị chương trình khác
chiếm mới hỏi cổng khác. Lần đầu tự tạo `.venv` và cài thư viện.

Docker (Linux server):

```bash
docker build -t pdf2cad .
docker run -d --name pdf2cad -p 5020:5020 -v pdf2cad-data:/data --restart unless-stopped pdf2cad
```

- Kéo thả nhiều PDF → theo dõi tiến độ → tải từng DXF hoặc tải hết (zip); xem trước PDF/DXF, bật/tắt layer.
- Hàng đợi: mặc định chạy 1 job một lúc (`--jobs`), các job sau xếp hàng. Mỗi job tách trang ra nhiều
  process (`--workers`).
- File tải lên và kết quả lưu tạm ở `--data-dir` (mặc định `~/.pdf2cad_web`), tự xoá sau `--ttl` giờ (24).
  Mã job ngẫu nhiên 128 bit; người có link `#job=...` mới xem được kết quả.
- Giới hạn: `--max-upload` MB mỗi lần (200), tối đa 50 file. Chỉ nhận file bắt đầu bằng `%PDF-`.
- **Không có đăng nhập**: chỉ mở trong mạng nội bộ; nếu đưa ra ngoài, đặt sau reverse proxy có xác thực (IIS / nginx).
- API JSON cho hệ thống khác: `POST /api/jobs`, `GET /api/jobs/{id}`, `GET /api/jobs/{id}/zip` … — tài liệu tại `/api/docs`.

**Xử lý sự cố web**

| Hiện tượng | Nguyên nhân / cách xử lý |
|---|---|
| `ERR_CONNECTION_TIMED_OUT` | Có tiến trình cũ treo giữ cổng: đóng các cửa sổ server cũ hoặc tắt `python.exe` trong Task Manager rồi chạy lại. Bản mới đã tắt QuickEdit (bấm chuột vào cửa sổ đen không còn làm treo server) và báo rõ khi cổng bị chiếm |
| `ERR_CONNECTION_REFUSED` | Server chưa chạy / cửa sổ server đã đóng |
| Máy khác không vào được | Mở cổng 5020 (hoặc `PORT` trong `deploy\server_config.bat`) trên Windows Firewall |

**Nhật ký truy cập**: cửa sổ server (dịch vụ: `server.log`) in từng thao tác của từng máy, và lưu lịch sử theo
ngày ở `<thư mục dữ liệu>\logs\access-YYYY-MM-DD.jsonl` (mỗi dòng 1 JSON: `time, ip, host, client, action`):
mở trang, chọn file, tải lên + chuyển, **chuyển xong** (số trang ra DXF, lỗi, thời gian), xem bản gốc / DXF,
tải về file / zip, dừng job, chat bot (câu chat + file vẽ ra), dạy bot, chọn mẫu, đổi ngôn ngữ, mở/thu nhỏ trợ lý,
**đóng / tải lại tab** (đã mở bao lâu). Tên máy tra ngược DNS, không có thì hỏi NetBIOS; cột `máy` (#xxxx) là
mã trình duyệt (cookie) để phân biệt nhiều máy dùng chung IP. Không ghi request hỏi tiến độ; cùng thao tác lặp
trong 10 giây chỉ ghi 1 lần. Tắt bằng `PDF2CAD_ACCESS_LOG=off`.

Chạy sau proxy **backlog-be** (`/api/pdf2cad/**`, màn Setting › PDF → CAD): đặt `PDF2CAD_TRUSTED_PROXIES=<IP máy backlog-be>`
để nhật ký ghi IP máy người dùng (`X-Forwarded-For`) và MSNV đã xác thực (`X-Employee-Id`, cột `user`). Request từ IP khác bỏ qua 2 header này.
`GET /api/templates` trả thêm `columns` + `rows` (bảng tham số để hiển thị; ô khoảng `{"range":[a,b],"also":[x]}` → `"a~b/x"`).

| Biến môi trường | Mặc định | Ý nghĩa |
|---|---|---|
| `PDF2CAD_TRUSTED_PROXIES` | (trống) | IP proxy tin cậy, cách nhau dấu phẩy |
| `PDF2CAD_DATA` | `~/.pdf2cad_web` | Thư mục dữ liệu tạm |
| `PDF2CAD_MAX_JOBS` | 1 | Job chạy đồng thời |
| `PDF2CAD_MAX_UPLOAD_MB` | 200 | Dung lượng tối đa mỗi lần tải lên |
| `PDF2CAD_TTL_HOURS` | 24 | Thời gian giữ kết quả |

## Giao diện desktop (kéo thả)

```bash
pip install -e ".[gui]"           # thêm PySide6
pdf2cad-gui                       # hoặc: python -m pdf2cad gui  /  bấm đúp run_gui.bat
```

- Kéo thả file PDF hoặc cả thư mục vào cửa sổ (hoặc kéo thả PDF lên `run_gui.bat`).
- Xem trước PDF, chỉnh tuỳ chọn (tỷ lệ, trang, DPI, số tiến trình…), bấm **Chuyển sang DXF**.
- Bảng kết quả theo từng trang; double-click để xem DXF ngay trong app, chuột phải để mở file/thư mục.
- Mặc định lưu DXF cạnh file PDF trong thư mục `<tên>_dxf`; tuỳ chọn được nhớ cho lần sau.

## PDF có chữ bị "outline" (OCR)

PDF in bằng **Microsoft Print to PDF** hoặc xuất từ Solid Edge/Inventor với tuỳ chọn chuyển chữ
thành hình học **không có lớp text**: mỗi chữ chỉ là một hình tô đặc. Khi đó DXF chỉ có HATCH,
nhấp đúp không sửa được chữ. pdf2cad tự phát hiện trường hợp này và nhận dạng lại bằng OCR
(RapidOCR, mô hình kèm sẵn trong gói pip, chạy offline):

```bash
pip install -e ".[ocr]"                        # GUI ([gui]) đã gồm sẵn OCR
pdf2cad convert drawing.pdf                    # --ocr auto (mặc định) | on | off
```

| Layer | Nội dung |
|---|---|
| `TEXT_OCR` | Chữ OCR (TEXT, font Arial, sửa được). Φ → `%%c`, ± → `%%p` |
| `TEXT_CHECK` (đỏ) | Chữ OCR độ tin cậy thấp — cần soát lại |
| `TEXT_GLYPH` (tắt sẵn) | Nét chữ gốc đã được thay bằng TEXT. Bật lên để đối chiếu |
| `FILL` | Hình tô đặc còn lại (mũi tên, ký hiệu) và chữ OCR không đọc được |

- OCR 4 lượt: (1) nhận dạng cả trang; (2) nét chữ còn sót (chữ ngắn sát đường kích thước/khung GD&T)
  được gom cụm, vẽ lại trên nền sạch và đọc lại; (3) ghép nét sót vào chữ liền kề ("0." + "1");
  (4) nối mảnh cùng dòng do font CAD giãn chữ ("3." + "2a" → "3.2a"), không nối qua đường kẻ bảng
  và không gộp dung sai vào kích thước.
- Ký hiệu GD&T (◎ ⌭ ○ ⊥) và chữ đơn độ tin cậy thấp được giữ nguyên nét gốc, không đổi thành chữ sai.
- Điểm chèn, chiều cao và hệ số rộng (width factor) của TEXT lấy theo bao của nét chữ gốc → chồng khít bản vẽ.
- Chữ dọc được nhận đúng góc xoay. Bản vẽ khổ ngang bị in nằm nghiêng trên khổ đứng sẽ tự xoay lại
  theo hướng chữ chủ đạo (tắt bằng `--no-rotate`).
- Tốc độ: ~10–25 s/trang A4 (CPU). OCR chỉ chạy khi trang không có lớp text.

## Sử dụng (dòng lệnh)

```bash
pdf2cad inspect drawing.pdf                    # phân loại trang vector/scan, dò tỷ lệ
pdf2cad convert drawing.pdf -o out             # mỗi trang -> 1 file DXF
pdf2cad convert drawing.pdf --pages 1-3,5 --workers 4
pdf2cad convert scan.pdf --scale 2 --dpi 300   # ép tỷ lệ 1:2 (bắt buộc với bản scan)
pdf2cad convert drawing.pdf --json             # báo cáo JSON để tích hợp hệ thống khác
pdf2cad bench drawing.pdf --dpi 300            # đo tốc độ PyMuPDF vs pypdfium2
```

Dùng như thư viện:

```python
from pdf2cad import convert, ConvertOptions

if __name__ == "__main__":          # bắt buộc trên Windows khi chạy song song
    for r in convert("drawing.pdf", ConvertOptions(out_dir="out", workers=4)):
        print(r.page, r.kind, r.scale, r.output, r.counts)
```

## Kiến trúc

```
PDF ─► classify.py ──┬─ VECTOR ─► vector_extract.py ─┐
     (vector/scan)   │   get_drawings, fit Bezier→Arc,│
                     │   gộp 4 cung→Circle, phân layer ├─► dxf_writer.py ─► DXF (mm)
                     └─ RASTER ─► raster.py ──────────┘     ezdxf R2007
                         OpenCV: LSD + gộp đồng tuyến,
                         HoughCircles + lọc mực
scale.py   : pt→mm, đọc "SCALE 1:2 / TỶ LỆ / 尺度" từ khung tên
pipeline.py: song song theo trang (ProcessPoolExecutor), lỗi 1 trang không dừng cả lô
```

| Module | Vai trò |
|---|---|
| `geometry.py` | Primitive (Line, Arc, Circle, Polyline, Text), fit cung tròn, gộp cung, gộp đoạn đồng tuyến |
| `layers.py` | Gán layer: OUTLINE (nét đậm), THIN, HIDDEN (nét đứt), CENTER (chấm gạch), FILL, TEXT, RASTER |
| `vector_extract.py` | Đọc lệnh vẽ PDF, lật trục Y, trích text kèm góc xoay |
| `ocr.py` | OCR chữ bị outline → TEXT, gom nét chữ gốc sang `TEXT_GLYPH` |
| `raster.py` | Vector hoá ảnh scan (thử nghiệm) |
| `bench.py` | Benchmark render & trích xuất |

## Độ chính xác hiện tại (bản vẽ mẫu, `tools/make_sample_pdf.py`)

| Loại trang | Kết quả |
|---|---|
| Vector | Kích thước đúng tuyệt đối (100 / 60 / 50 / 90 mm, R10), đúng layer, tự đọc tỷ lệ 1:2 |
| Scan 200 dpi | Sai số ≤ 0.5 mm trên cạnh dài, lỗ R10.04; chữ bị vector hoá thành nét (chưa OCR) |

Tốc độ (trang A3/A4 mẫu): trích vector ~3 ms/trang, render 300 dpi ~80 ms/trang.

## Hạn chế đã biết

- Đường kích thước được xuất dưới dạng LINE + TEXT, chưa thành đối tượng DIMENSION.
- Bản scan: đã OCR chữ; chưa nhận nét đứt/đường tâm, dấu mộc/chữ ký tay vẫn thành nét.
- Text: font PDF không được giữ (dùng Arial), chiều cao chữ ước lượng = 0.7 × font size.
- OCR: ký hiệu ℓ (chữ l viết tay) đọc thành "l"/"I"; ký hiệu GD&T giữ dạng nét (HATCH), chưa thành
  ký tự; ghi chú nhiều dòng có thể bị tách TEXT ở đầu dòng ("*." | "16≤No..."). Luôn soát lại layer `TEXT_CHECK`. Chưa OCR cho trang scan.
- Bản vẽ có nhiều khung nhìn tỷ lệ khác nhau trên cùng trang: hiện áp 1 tỷ lệ cho cả trang.

## Cải tiến độ chính xác – việc tiếp theo

Cách làm đã dùng (nên giữ): chạy `samples/`, đối chiếu ảnh gốc (cắt vùng quanh từng chữ / vẽ nét DXF chồng lên ảnh
đã xoay thẳng), sửa, chạy lại và so sánh từng thực thể với lần trước để chắc không làm hỏng chỗ đang đúng.

Vòng 1 (OCR chữ) và vòng 2 (nét vẽ bản scan) đã xong phần lớn. Còn lại:

**Nét vẽ bản scan**
- Thời gian bản scan tăng từ ~10–15 s lên ~17–23 s/trang (dựng cung: lọc đoạn trùng O(n²)); cần lưới không gian để tăng tốc.
- Elip (hình chiếu trục đo) đang được xấp xỉ bằng vài cung tròn; nên fit elip (`ELLIPSE`) hoặc để nguyên `RASTER`.
- Nét xiên (`RASTER`) chưa phân loại đậm/mảnh và chưa lọc rác (ký hiệu nhám √, nét chữ ký, viền mộc còn sót).
- Nét đứt / chấm gạch xiên và đường tròn nét đứt (vòng chia) chưa nhận.
- Độ dày bản scan có thể khác theo chiều ngang / dọc (máy scan): khung bản vẽ 19344 có cạnh dọc đậm, cạnh ngang mảnh – hiện
  giữ trung thực theo ảnh; có thể thêm luật "cạnh khép kín của cùng 1 hình thì cùng độ dày".
- Vài nét chữ chưa bị xoá hết (vạch đứng 2.5–2.7 mm cạnh chữ "d<14 => d±0.1") vẫn thành đoạn `THIN` ngắn.
- `tests/test_stamps.py::test_revision_mark` đã lỗi từ trước vòng 1 (đọc thừa "1" trong ký hiệu △), chưa xử lý.

**OCR chữ**
- Chưa sửa: `T. NGHE` (ảnh là NGHI), ký hiệu viết tay `ℓ` (`→15≤ιC<L`), chữ ký / tên dính dấu mộc, đường dẫn file in mờ.
- `JIS B 0405-M` (chữ M hoa) chưa đưa về `-m`.

**PDF vector** (chưa bắt đầu): đo lại fit cung / layer / tỷ lệ trên `21.DR-TE-20352-00 (DSJC-B).pdf`; trang có nhiều khung
nhìn khác tỷ lệ.

**Vẽ lại từ mẫu (bot)** (chưa bắt đầu): đo đúng / sai khi hiểu câu, tra bảng, vẽ lại (chữ đè nhau, kích thước sau vẽ lại),
"Dạy bot" đọc bảng tham số.

## Lộ trình

1. **2D nâng cao**: nối LINE thành polyline kín, nhận diện khung tên/khung bản vẽ, tách từng hình chiếu (view) theo cụm hình học.
2. **Scan**: OCR (PaddleOCR / Tesseract) cho text & kích thước, nhận nét đứt, tự hiệu chỉnh tỷ lệ bằng kích thước ghi chú.
3. **3D bán tự động**: chọn biên dạng từ view → Extrude/Revolve bằng CadQuery hoặc FreeCAD Python → xuất STEP.
4. **3D tự động**: tái tạo khối từ 3 hình chiếu cho chi tiết lăng trụ đơn giản.
5. **GUI**: ✅ PySide6 kéo thả (`gui.py`), ✅ web server (`web/`). Tiếp theo: đăng nhập AD/SSO cho bản web.

## Phát triển

```bash
python tools/make_sample_pdf.py samples/sample_drawing.pdf
pytest -q
```

## Lưu ý license

| Thư viện | License | Ghi chú |
|---|---|---|
| PyMuPDF | AGPL-3.0 / thương mại | Dùng nội bộ thường không vấn đề; phân phối ra ngoài cần xem xét license thương mại hoặc thay bằng pypdfium2 |
| ezdxf | MIT | |
| OpenCV | Apache-2.0 | |
| FastAPI / uvicorn | MIT / BSD-3 | |
| RapidOCR / onnxruntime | Apache-2.0 / MIT | Mô hình PP-OCR (Apache-2.0) |
| pypdfium2 | Apache-2.0 / BSD-3 | Phương án thay thế PyMuPDF |


## Triển khai lên server (client truy cập qua IP:PORT)

Chạy `deploy\install_server.bat` bằng quyền Administrator trên server. Client mở `http://<IP-server>:5020`. Chi tiết xem [deploy/DEPLOY.md](deploy/DEPLOY.md).
