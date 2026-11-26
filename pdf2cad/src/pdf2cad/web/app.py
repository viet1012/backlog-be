"""Web server pdf2cad: kéo thả PDF trên trình duyệt -> tải DXF.

Chạy:  pdf2cad serve                             (chỉ máy này: http://localhost:5020)
       pdf2cad serve --host 0.0.0.0              (cho cả mạng LAN: http://<ip-máy>:5020)
Cổng mặc định 5020 (đổi bằng --port hoặc biến môi trường PDF2CAD_PORT).

API (JSON):
  POST   /api/jobs                       multipart: files[]=*.pdf + tuỳ chọn  -> {id}
  GET    /api/jobs/{id}                  trạng thái, tiến độ, báo cáo từng trang
  DELETE /api/jobs/{id}                  huỷ + xoá dữ liệu
  GET    /api/jobs/{id}/files/{name}     tải 1 file DXF
  GET    /api/jobs/{id}/zip              tải tất cả DXF (zip)
  GET    /api/jobs/{id}/preview/{name}   hình học DXF (JSON) để xem trước
  GET    /api/jobs/{id}/pdf/{idx}/{page}.png   ảnh trang PDF gốc
  GET    /api/health
"""
from __future__ import annotations

import io
import json
import logging
import os
import re
import shutil
import threading
import time
import uuid
import zipfile
import multiprocessing
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor, as_completed
from concurrent.futures.process import BrokenProcessPool
from dataclasses import dataclass, field, replace
from pathlib import Path

import pymupdf
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response, StreamingResponse

from .. import __version__, llm
from ..pipeline import (INPUT_EXTS, ConvertOptions, PageReport, _process_page, image_to_pdf,
                        parse_pages)


def _magic_ok(ext: str, head: bytes) -> bool:
    """Kiểm tra chữ ký file thật (không tin đuôi file)."""
    if ext == ".pdf":
        return head.startswith(b"%PDF-")
    if ext in (".tif", ".tiff"):
        return head[:4] in (b"II*\x00", b"MM\x00*")
    if ext == ".png":
        return head.startswith(b"\x89PNG")
    if ext in (".jpg", ".jpeg"):
        return head.startswith(b"\xff\xd8\xff")
    if ext == ".bmp":
        return head.startswith(b"BM")
    return False

STATIC = Path(__file__).parent / "static"
_ID_RE = re.compile(r"^[0-9a-f]{32}$")


# ----------------------------------------------------------------- cấu hình
@dataclass
class Settings:
    data_dir: Path = Path(os.environ.get("PDF2CAD_DATA", Path.home() / ".pdf2cad_web"))
    max_jobs: int = int(os.environ.get("PDF2CAD_MAX_JOBS", 1))        # job chạy song song
    page_workers: int | None = None                                    # process/1 job
    max_upload_mb: int = int(os.environ.get("PDF2CAD_MAX_UPLOAD_MB", 200))
    max_files: int = int(os.environ.get("PDF2CAD_MAX_FILES", 50))
    ttl_hours: float = float(os.environ.get("PDF2CAD_TTL_HOURS", 24))  # tự xoá job cũ
    templates_dir: Path | None = None                                  # None = mặc định


# ----------------------------------------------------------------- job
@dataclass
class Job:
    id: str
    dir: Path
    files: list[dict]                 # [{name, path, pages}]
    opt: ConvertOptions
    created: float = field(default_factory=time.time)
    status: str = "queued"            # queued | running | done | cancelled | error
    message: str = ""
    total_pages: int = 0
    done_pages: int = 0
    current: str = ""
    reports: list[dict] = field(default_factory=list)
    stop: bool = False
    finished: float | None = None
    done_keys: set = field(default_factory=set)
    client: dict | None = None        # máy đã tải lên (ip, mã trình duyệt) -> ghi nhật ký khi chuyển xong

    def _partial(self) -> tuple[float, str]:
        """Tiến độ các trang đang chạy dở (đọc file của process con) + bước hiện tại."""
        from ..progress import read
        pdir = self.dir / "progress"
        part, stage, best = 0.0, "", -1.0
        if self.status != "running" or not pdir.is_dir():
            return 0.0, ""
        for fp in pdir.glob("*.json"):
            if fp.stem in self.done_keys:
                continue
            d = read(fp)
            if not d:
                continue
            p = float(d.get("p", 0))
            part += min(p, 0.99)
            if p > best:
                best, stage = p, d.get("stage", "")
        return part, stage

    def public(self, queue_pos: int | None = None) -> dict:
        part, stage = self._partial()
        total = max(self.total_pages, 1)
        if self.status in ("done",):
            percent = 100.0
        else:
            percent = min(99.9, 100.0 * (self.done_pages + part) / total)
        return {
            "percent": round(percent, 1), "stage": stage,
            "id": self.id, "status": self.status, "message": self.message,
            "created": self.created, "finished": self.finished,
            "files": [{"name": f["name"], "pages": f["pages"]} for f in self.files],
            "total_pages": self.total_pages, "done_pages": self.done_pages,
            "current": self.current, "queue_position": queue_pos,
            "reports": self.reports,
        }


def _safe_name(name: str) -> str:
    name = Path(name.replace("\\", "/")).name
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")
    return name or "file.pdf"


class JobManager:
    def __init__(self, s: Settings):
        self.s = s
        self.jobs: dict[str, Job] = {}
        self.queue: list[str] = []
        self.lock = threading.Lock()
        self.pool = ThreadPoolExecutor(max_workers=max(1, s.max_jobs), thread_name_prefix="job")
        # Việc nặng (đọc PDF, OCR) chạy ở process riêng => server luôn phản hồi nhanh
        self.n_procs = s.page_workers or max(1, (os.cpu_count() or 2) - 1)
        self._procs: ProcessPoolExecutor | None = None
        self._procs_lock = threading.Lock()
        (s.data_dir / "jobs").mkdir(parents=True, exist_ok=True)
        threading.Thread(target=self._cleanup_loop, daemon=True).start()

    # --- tạo & chạy
    def submit(self, files: list[dict], opt: ConvertOptions, job_dir: Path, job_id: str) -> Job:
        job = Job(job_id, job_dir, files, opt,
                  total_pages=sum(len(self._pages_of(f["pages"], opt)) for f in files))
        with self.lock:
            self.jobs[job.id] = job
            self.queue.append(job.id)
        self.pool.submit(self._run, job)
        return job

    @staticmethod
    def _pages_of(n: int, opt: ConvertOptions) -> list[int]:
        return [p for p in (opt.pages or range(1, n + 1)) if 1 <= p <= n]

    def procs(self, reset: bool = False) -> ProcessPoolExecutor:
        with self._procs_lock:
            if reset and self._procs is not None:
                self._procs.shutdown(wait=False, cancel_futures=True)
                self._procs = None
            if self._procs is None:
                self._procs = ProcessPoolExecutor(
                    max_workers=self.n_procs, mp_context=multiprocessing.get_context("spawn"))
            return self._procs

    def shutdown(self):
        for j in self.jobs.values():
            j.stop = True
        self.pool.shutdown(wait=False, cancel_futures=True)
        with self._procs_lock:
            if self._procs is not None:
                self._procs.shutdown(wait=False, cancel_futures=True)

    def _run(self, job: Job):
        with self.lock:
            if job.id in self.queue:
                self.queue.remove(job.id)
        if job.stop:
            return
        job.status = "running"
        opt = replace(job.opt, out_dir=str(job.dir / "out"), workers=1,
                      progress_dir=str(job.dir / "progress"))
        try:
            tasks = [(f, p) for f in job.files for p in self._pages_of(f["pages"], opt)]
            try:
                pool = self.procs()
                futs = {pool.submit(_process_page, f["path"], p, opt, Path(f["name"]).stem): (f, p)
                        for f, p in tasks}
            except BrokenProcessPool:
                pool = self.procs(reset=True)
                futs = {pool.submit(_process_page, f["path"], p, opt, Path(f["name"]).stem): (f, p)
                        for f, p in tasks}
            job.current = job.files[0]["name"] if job.files else ""
            for fut in as_completed(futs):
                f, p = futs[fut]
                try:
                    r = fut.result()
                except BrokenProcessPool as e:      # process con chết (hết RAM...) => tạo lại pool
                    self.procs(reset=True)
                    r = PageReport(p, "error", 1.0, "-", error=f"Process xử lý bị dừng: {e}")
                except Exception as e:
                    r = PageReport(p, "error", 1.0, "-", error=f"{type(e).__name__}: {e}")
                rep = r.as_dict()
                rep["file"] = f["name"]
                rep["output"] = Path(r.output).name if r.output else None
                job.reports.append(rep)
                job.done_keys.add(f"{Path(f['name']).stem}_p{p:03d}")
                job.done_pages += 1
                job.current = f["name"]
                if job.stop:
                    for g in futs:
                        g.cancel()
                    break
            job.status = "cancelled" if job.stop else "done"
        except Exception as e:  # lỗi ngoài dự kiến
            job.status, job.message = "error", f"{type(e).__name__}: {e}"
        job.current = ""
        job.finished = time.time()
        if job.client:
            ok = sum(1 for r in job.reports if r.get("output"))
            err = sum(1 for r in job.reports if r.get("error"))
            names = ", ".join(f["name"] for f in job.files[:3]) + (" …" if len(job.files) > 3 else "")
            head = {"done": "chuyển xong", "cancelled": "đã dừng chuyển", "error": "chuyển lỗi"}[job.status]
            log_event(job.client, f"{head} {names}: {ok}/{job.total_pages} trang ra DXF"
                                  + (f", {err} trang lỗi" if err else "")
                                  + (f" ({job.message})" if job.message else "")
                                  + f", {job.finished - job.created:.0f} giây")

    def get(self, job_id: str) -> Job:
        if not _ID_RE.match(job_id) or job_id not in self.jobs:
            raise HTTPException(404, "Không tìm thấy job (có thể đã hết hạn)")
        return self.jobs[job_id]

    def queue_pos(self, job_id: str) -> int | None:
        with self.lock:
            return self.queue.index(job_id) + 1 if job_id in self.queue else None

    def delete(self, job_id: str):
        job = self.get(job_id)
        job.stop = True
        with self.lock:
            if job_id in self.queue:
                self.queue.remove(job_id)
            self.jobs.pop(job_id, None)
        # nếu đang chạy, thư mục sẽ được dọn bởi vòng cleanup
        if job.status != "running":
            shutil.rmtree(job.dir, ignore_errors=True)

    def _cleanup_loop(self):
        while True:
            try:
                ttl = self.s.ttl_hours * 3600
                now = time.time()
                for jid, job in list(self.jobs.items()):
                    if job.finished and now - job.finished > ttl:
                        self.jobs.pop(jid, None)
                        shutil.rmtree(job.dir, ignore_errors=True)
                # thư mục mồ côi (server khởi động lại / job đã xoá)
                for d in (self.s.data_dir / "jobs").iterdir():
                    if d.name not in self.jobs and now - d.stat().st_mtime > min(ttl, 3600):
                        shutil.rmtree(d, ignore_errors=True)
            except Exception:
                pass
            time.sleep(600)


# ----------------------------------------------------------------- xem trước DXF
def _sym(txt: str) -> str:
    return txt.replace("%%c", "Ø").replace("%%C", "Ø").replace("%%p", "±").replace("%%d", "°")


def dxf_preview(path: Path, max_entities: int = 60000) -> dict:
    """Rút gọn DXF thành JSON nhẹ cho canvas: l=[x1,y1,x2,y2], c=[x,y,r], a=[x,y,r,a0,a1],
    p=[[x,y]..], f=vùng tô, t=[x,y,h,rot,wf,text] (chân trái). Hỗ trợ cả DIMENSION/MTEXT."""
    import math as _m

    import ezdxf
    doc = ezdxf.readfile(path)
    hidden = {l.dxf.name for l in doc.layers if l.is_off() or l.is_frozen()}
    layers: dict[str, dict] = {}
    n = 0
    r = lambda v: round(float(v), 3)

    def text_at(x, y, h, rot, wf, txt, hfrac, vfrac):
        """hfrac/vfrac: điểm neo nằm ở bao nhiêu phần bề rộng/cao chữ -> quy về chân trái."""
        w = 0.6 * h * len(txt) * wf
        a = _m.radians(rot)
        dx, dy = -hfrac * w, -vfrac * h
        return [r(x + dx * _m.cos(a) - dy * _m.sin(a)), r(y + dx * _m.sin(a) + dy * _m.cos(a)),
                r(h), r(rot), r(wf), txt]

    def emit(e, layer):
        nonlocal n
        if n >= max_entities:
            return
        L = layers.setdefault(layer, {"l": [], "c": [], "a": [], "p": [], "t": [], "f": []})
        t = e.dxftype()
        if t == "LINE":
            s_, en = e.dxf.start, e.dxf.end
            L["l"].append([r(s_.x), r(s_.y), r(en.x), r(en.y)])
        elif t == "CIRCLE":
            L["c"].append([r(e.dxf.center.x), r(e.dxf.center.y), r(e.dxf.radius)])
        elif t == "ARC":
            L["a"].append([r(e.dxf.center.x), r(e.dxf.center.y), r(e.dxf.radius),
                           r(e.dxf.start_angle), r(e.dxf.end_angle)])
        elif t == "LWPOLYLINE":
            pts = [[r(x), r(y)] for x, y in e.get_points("xy")]
            if e.closed and pts:
                pts.append(pts[0])
            L["p"].append(pts)
        elif t == "HATCH":
            for pth in e.paths:
                vs = getattr(pth, "vertices", None)
                if vs:
                    L["f"].append([[r(v[0]), r(v[1])] for v in vs])
        elif t == "SOLID":                                   # mũi tên kích thước
            vs = [e.dxf.vtx0, e.dxf.vtx1, e.dxf.vtx3, e.dxf.vtx2]
            L["f"].append([[r(v.x), r(v.y)] for v in vs])
        elif t == "TEXT":
            txt = _sym(e.dxf.text)
            h, rot, wf = e.dxf.height, e.dxf.rotation, e.dxf.get("width", 1.0)
            ha, va = e.dxf.get("halign", 0), e.dxf.get("valign", 0)
            if ha or va:
                p_ = e.dxf.get("align_point", e.dxf.insert)
                hf = {0: 0, 1: 0.5, 2: 1, 4: 0.5}.get(ha, 0)
                vf = {0: 0, 1: 0, 2: 0.5, 3: 1}.get(va, 0) if ha != 4 else 0.5
                L["t"].append(text_at(p_.x, p_.y, h, rot, wf, txt, hf, vf))
            else:
                L["t"].append([r(e.dxf.insert.x), r(e.dxf.insert.y), r(h), r(rot), r(wf), txt])
        elif t == "MTEXT":
            txt = _sym(e.plain_text(split=False).replace("\n", " ").replace("^", "/")
                       .replace("/ ", "/"))
            h = e.dxf.char_height
            ap = e.dxf.get("attachment_point", 1)            # 1..9: TL TC TR ML MC MR BL BC BR
            hf = ((ap - 1) % 3) * 0.5
            vf = {0: 1, 1: 0.5, 2: 0}[(ap - 1) // 3]
            rot = _m.degrees(_m.atan2(e.dxf.text_direction.y, e.dxf.text_direction.x)) \
                if e.dxf.hasattr("text_direction") else e.dxf.get("rotation", 0)
            L["t"].append(text_at(e.dxf.insert.x, e.dxf.insert.y, h, rot, 1.0, txt, hf, vf))
        elif t in ("DIMENSION", "INSERT", "LEADER"):
            for v in e.virtual_entities():
                emit(v, layer)
            return
        else:
            return
        n += 1

    for e in doc.modelspace():
        if e.dxf.layer not in hidden:
            emit(e, e.dxf.layer)
    return {"layers": layers, "count": n, "truncated": n >= max_entities}


# ----------------------------------------------------------------- app
# ----------------------------------------------------------------- nhật ký truy cập
# Mỗi thao tác chính (mở trang, tải lên, tải về, chat bot, dạy bot) in 1 dòng: giờ, IP, tên máy, thao tác.
# Không ghi các request hỏi tiến độ mỗi giây. Tắt: PDF2CAD_ACCESS_LOG=off.
_HOSTNAMES: dict[str, str] = {}
_HOST_FAILED: dict[str, float] = {}      # IP tra không ra tên -> thử lại sau 10 phút
_ACCESS_POOL = None                      # 1 luồng: giữ đúng thứ tự dòng log, tra tên máy không chặn server


def _netbios_name(ip: str, timeout: float = 1.0) -> str:
    """Hỏi tên máy trực tiếp qua NetBIOS (UDP 137, như `nbtstat -A <ip>`). Dùng khi DNS không có bản
    ghi ngược (thường gặp với máy ở dải mạng khác). Máy client chặn UDP 137 thì trả ''."""
    import os as _os
    import socket
    import struct
    tid = _os.urandom(2)
    qname = b"\x20" + bytes(c for b in b"*" + b"\0" * 15 for c in (65 + (b >> 4), 65 + (b & 15))) + b"\0"
    pkt = tid + b"\x00\x00\x00\x01\x00\x00\x00\x00\x00\x00" + qname + b"\x00\x21\x00\x01"
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.settimeout(timeout)
            s.sendto(pkt, (ip, 137))
            data, _ = s.recvfrom(2048)
    except OSError:
        return ""
    try:
        if data[:2] != tid:
            return ""
        i = 12
        i += 2 if data[i] & 0xC0 == 0xC0 else data[i] + 2          # tên RR: con trỏ nén hoặc đủ 34 byte
        i += 10                                                     # type, class, ttl, rdlength
        for k in range(data[i]):
            off = i + 1 + 18 * k
            name, suffix = data[off:off + 15], data[off + 15]
            flags = struct.unpack(">H", data[off + 16:off + 18])[0]
            if suffix == 0x00 and not flags & 0x8000:               # tên máy (không phải tên nhóm)
                return name.decode("ascii", "replace").strip()
    except (IndexError, struct.error):
        pass
    return ""


def _client_name(ip: str) -> str:
    """Tên máy của IP: DNS ngược, không có thì hỏi NetBIOS. Lưu cache. Không tra được -> ''."""
    if ip in _HOSTNAMES:
        return _HOSTNAMES[ip]
    if time.time() < _HOST_FAILED.get(ip, 0):
        return ""
    import socket
    name = ""
    try:
        name = socket.gethostname() if ip in ("127.0.0.1", "::1") else socket.gethostbyaddr(ip)[0]
        name = name.split(".")[0] if not name.replace(".", "").isdigit() else ""
    except (OSError, UnicodeError):
        name = ""
    if not name and ip.count(".") == 3:
        name = _netbios_name(ip)
    if name:
        _HOSTNAMES[ip] = name
    else:
        _HOST_FAILED[ip] = time.time() + 600
    return name


_ACCESS_DIR: Path | None = None          # <data>/logs: lịch sử truy cập theo ngày (access-YYYY-MM-DD.jsonl)
_LAST_EVENT: dict = {}                   # chống lặp: cùng máy + cùng thao tác trong 10 giây thì bỏ
CID_COOKIE = "p2c_cid"                   # mã trình duyệt: phân biệt các máy dùng chung 1 IP (NAT, VPN)


def _access_on() -> bool:
    return os.environ.get("PDF2CAD_ACCESS_LOG", "on").strip().lower() not in ("off", "0", "false", "no")


def _cell_text(v):
    """Ô bảng tham số để hiển thị: {"range": [a, b], "also": [x]} -> "a~b/x"; số / chuỗi giữ nguyên."""
    if isinstance(v, dict):
        a, b = (v.get("range") or [None, None])[:2]
        fmt = lambda n: f"{n:g}" if isinstance(n, (int, float)) else str(n)
        parts = [fmt(x) for x in v.get("also") or []]
        if a is not None:
            parts.insert(0, fmt(a) if a == b else f"{fmt(a)}~{fmt(b)}")
        return "/".join(parts)
    if isinstance(v, list):
        return "/".join(str(x) for x in v)
    return v if isinstance(v, (int, float, str)) else ("" if v is None else str(v))


def _table_view(table: list) -> dict:
    """Bảng tham số dạng cột + hàng cho giao diện (cột theo thứ tự xuất hiện)."""
    columns: list[str] = []
    for row in table:
        for k in row:
            if k not in columns:
                columns.append(k)
    return {"columns": columns, "rows": [[_cell_text(row.get(c)) for c in columns] for row in table]}


def _trusted_proxies() -> set[str]:
    """IP của proxy tin cậy (vd backlog-be), cách nhau dấu phẩy: PDF2CAD_TRUSTED_PROXIES=192.168.122.16"""
    return {x.strip() for x in os.environ.get("PDF2CAD_TRUSTED_PROXIES", "").split(",") if x.strip()}


def client_of(request: Request) -> dict:
    ip = (request.client.host if request.client else "?").removeprefix("::ffff:")
    cid = re.sub(r"[^0-9a-f]", "", request.cookies.get(CID_COOKIE, ""))[:12]
    # đi qua proxy tin cậy (backlog-be đã xác thực người dùng): lấy IP máy người dùng + MSNV từ header.
    # Request từ nơi khác thì bỏ qua 2 header này (ai cũng tự đặt được).
    if ip in _trusted_proxies():
        fwd = request.headers.get("x-forwarded-for", "").split(",")[0].strip().removeprefix("::ffff:")
        user = re.sub(r"[^0-9A-Za-z_.-]", "", request.headers.get("x-employee-id", ""))[:20]
        return {"ip": fwd or ip, "cid": cid, "user": user, "via": ip}
    return {"ip": ip, "cid": cid}


def access_log(request: Request, action: str) -> None:
    log_event(client_of(request), action)


def log_event(client: dict, action: str) -> None:
    """In 1 dòng ra cửa sổ server và ghi vào lịch sử theo ngày. Tra tên máy ở luồng nền."""
    global _ACCESS_POOL
    if not _access_on():
        return
    ip, cid, user = client.get("ip", "?"), client.get("cid", ""), client.get("user", "")
    now = time.time()
    key = (user or cid or ip, action)
    if now - _LAST_EVENT.get(key, 0) < 10:
        return
    _LAST_EVENT[key] = now
    if len(_LAST_EVENT) > 5000:
        _LAST_EVENT.clear()
    t = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(now))
    if _ACCESS_POOL is None:
        from concurrent.futures import ThreadPoolExecutor
        _ACCESS_POOL = ThreadPoolExecutor(1, thread_name_prefix="access-log")
    log_dir = _ACCESS_DIR

    def emit():
        name = _client_name(ip)
        tag = f"NV {user}" if user else f"#{cid[:4]}" if cid else ""
        line = f"[{t}] {ip:<15} {(name or '(không rõ tên máy)'):<20} {tag:<5} {action}"
        try:            # chạy nền (dịch vụ) thì print đã được chuyển vào server.log
            print(line, flush=True)
        except (OSError, ValueError, UnicodeError):  # console bảng mã khác / đã đóng
            pass
        if log_dir is not None:
            try:
                log_dir.mkdir(parents=True, exist_ok=True)
                rec = {"time": t, "ip": ip, "host": name, "client": cid, "action": action}
                if user:                    # qua proxy backlog-be: MSNV đã xác thực + IP proxy
                    rec["user"], rec["via"] = user, client.get("via", "")
                with open(log_dir / f"access-{t[:10]}.jsonl", "a", encoding="utf-8") as fh:
                    fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
            except OSError:
                pass
    _ACCESS_POOL.submit(emit)


def create_app(settings: Settings | None = None) -> FastAPI:
    s = settings or Settings()
    s.data_dir = Path(s.data_dir)
    global _ACCESS_DIR
    _ACCESS_DIR = s.data_dir / "logs"
    mgr = JobManager(s)
    from contextlib import asynccontextmanager

    @asynccontextmanager
    async def lifespan(_app):
        yield
        mgr.shutdown()

    app = FastAPI(title="pdf2cad", version=__version__, docs_url="/api/docs", redoc_url=None,
                  lifespan=lifespan)
    app.state.manager, app.state.settings = mgr, s

    @app.get("/", response_class=HTMLResponse)
    def index(request: Request):
        resp = HTMLResponse((STATIC / "index.html").read_text(encoding="utf-8"))
        if not client_of(request)["cid"]:            # máy mới: cấp mã trình duyệt (giữ 1 năm)
            cid = uuid.uuid4().hex[:12]
            resp.set_cookie(CID_COOKIE, cid, max_age=365 * 86400, httponly=True, samesite="lax")
            log_event({**client_of(request), "cid": cid}, "mở trang web (lần đầu trên trình duyệt này)")
        else:
            access_log(request, "mở trang web")
        return resp

    @app.post("/api/log", status_code=204)
    async def client_log(request: Request):
        """Sự kiện từ trình duyệt: đóng tab, chọn file, đổi ngôn ngữ, chọn mẫu, mở/đóng trợ lý."""
        try:
            d = json.loads((await request.body())[:4000] or b"{}")
        except ValueError:
            return Response(status_code=204)
        ev = str(d.get("event", ""))
        txt = lambda v, n=80: " ".join(str(v).split())[:n]
        if ev == "close":
            secs = max(0, int(float(d.get("secs") or 0)))
            dur = f"{secs // 3600} giờ {secs % 3600 // 60} phút" if secs >= 3600 else \
                f"{secs // 60} phút {secs % 60} giây" if secs >= 60 else f"{secs} giây"
            access_log(request, f"đóng / tải lại tab (đã mở {dur})")
        elif ev == "files":
            names = [txt(n, 60) for n in (d.get("names") or [])[:50]]
            mb = float(d.get("mb") or 0)
            access_log(request, f"chọn {len(names)} file ({mb:.1f} MB, chưa tải lên): "
                                + ", ".join(names[:5]) + (f" … (+{len(names) - 5})" if len(names) > 5 else ""))
        elif ev == "lang":
            access_log(request, f"đổi ngôn ngữ: {txt(d.get('lang'), 5)}")
        elif ev == "template":
            access_log(request, f"chọn mẫu bot: {txt(d.get('name'), 60)}")
        elif ev == "bot":
            access_log(request, "mở trợ lý vẽ lại" if d.get("open") else "thu nhỏ trợ lý vẽ lại")
        return Response(status_code=204)

    @app.get("/api/health")
    def health():
        from .. import ocr
        running = sum(1 for j in mgr.jobs.values() if j.status == "running")
        import socket as _s
        return {"ok": True, "version": __version__, "ocr": ocr.available(),
                "host": _s.gethostname(), "ocr_device": getattr(ocr, "ENGINE_INFO", {}).get("device"),
                "running": running, "queued": len(mgr.queue),
                "max_upload_mb": s.max_upload_mb, "max_files": s.max_files, "llm": llm.status()}

    @app.post("/api/jobs")
    async def create_job(
        request: Request,
        files: list[UploadFile] = File(...),
        pages: str = Form(""),
        scale: float | None = Form(None),
        dpi: int = Form(300),
        thick: float = Form(0.5),
        fit_arcs: bool = Form(True),
        raster: bool = Form(True),
        ocr: str = Form("auto"),
        auto_rotate: bool = Form(True),
        remove_stamps: bool = Form(True),
        keep_glyphs: bool = Form(False),
    ):
        if not files:
            raise HTTPException(400, "Chưa có file")
        if len(files) > s.max_files:
            raise HTTPException(400, f"Tối đa {s.max_files} file mỗi lần")
        if ocr not in ("auto", "on", "off"):
            raise HTTPException(400, "ocr phải là auto|on|off")
        try:
            page_list = parse_pages(pages.strip() or None)
        except ValueError:
            raise HTTPException(400, "Ô 'Trang' không hợp lệ (vd: 1-3,5)")
        if scale is not None and not (0 < scale < 1e5):
            raise HTTPException(400, "Tỷ lệ không hợp lệ")

        job_id = uuid.uuid4().hex
        job_dir = s.data_dir / "jobs" / job_id
        (job_dir / "in").mkdir(parents=True)
        limit = s.max_upload_mb * 1024 * 1024
        total, saved, used = 0, [], set()
        try:
            for i, up in enumerate(files):
                name = _safe_name(up.filename or f"file{i + 1}.pdf")
                ext = Path(name).suffix.lower()
                if ext not in INPUT_EXTS:
                    raise HTTPException(400, f"{name}: chỉ nhận PDF, TIFF, PNG, JPG, BMP")
                stem, k = Path(name).stem, 1
                while name.lower() in used:          # trùng tên => thêm hậu tố
                    k += 1
                    name = f"{stem}_{k}{ext}"
                used.add(name.lower())
                dest = job_dir / "in" / name
                with open(dest, "wb") as fh:
                    head = await up.read(8)
                    if not _magic_ok(ext, head):
                        raise HTTPException(400, f"{name}: nội dung không đúng định dạng {ext}")
                    fh.write(head)
                    total += len(head)
                    while chunk := await up.read(1 << 20):
                        total += len(chunk)
                        if total > limit:
                            raise HTTPException(413, f"Tổng dung lượng vượt {s.max_upload_mb} MB")
                        fh.write(chunk)
                try:
                    path = dest
                    if ext != ".pdf":                  # ảnh scan -> PDF (khổ theo DPI ảnh)
                        path = image_to_pdf(dest, dest.with_name(dest.name + ".pdf"))
                    with pymupdf.open(path) as d:
                        n = d.page_count
                except Exception:
                    raise HTTPException(400, f"{name}: file hỏng, có mật khẩu hoặc không đọc được")
                saved.append({"name": name, "path": str(path), "pages": n})
        except HTTPException:
            shutil.rmtree(job_dir, ignore_errors=True)
            raise

        opt = ConvertOptions(pages=page_list, scale=scale, dpi=dpi, workers=s.page_workers,
                             fit_arcs=fit_arcs, raster=raster, thick_width=thick,
                             ocr=ocr, auto_rotate=auto_rotate, remove_stamps=remove_stamps,
                             keep_glyphs=keep_glyphs)
        job = mgr.submit(saved, opt, job_dir, job_id)
        job.client = client_of(request)
        names = ", ".join(f["name"] for f in saved[:3]) + (f" … (+{len(saved) - 3})" if len(saved) > 3 else "")
        access_log(request, f"tải lên và chuyển {len(saved)} file ({total / 1048576:.1f} MB, "
                            f"{job.total_pages} trang): {names}")
        return JSONResponse(job.public(mgr.queue_pos(job.id)), status_code=201)

    @app.get("/api/jobs/{job_id}")
    def job_status(job_id: str):
        job = mgr.get(job_id)
        return job.public(mgr.queue_pos(job_id))

    @app.delete("/api/jobs/{job_id}")
    def job_delete(job_id: str, request: Request):
        job = mgr.jobs.get(job_id)
        if job is not None:
            access_log(request, "dừng và xoá job: " + ", ".join(f["name"] for f in job.files[:3]))
        mgr.delete(job_id)
        return {"ok": True}

    def _out_file(job: Job, name: str) -> Path:
        p = (job.dir / "out" / _safe_name(name)).resolve()
        if p.parent != (job.dir / "out").resolve() or not p.is_file():
            raise HTTPException(404, "Không có file")
        return p

    @app.get("/api/jobs/{job_id}/files/{name}")
    def job_file(job_id: str, name: str, request: Request):
        p = _out_file(mgr.get(job_id), name)
        access_log(request, f"tải về {p.name}")
        return FileResponse(p, media_type="application/dxf", filename=p.name)

    @app.get("/api/jobs/{job_id}/zip")
    def job_zip(job_id: str, request: Request):
        job = mgr.get(job_id)
        access_log(request, f"tải về zip ({len(job.files)} file gốc)")
        out = job.dir / "out"
        files = sorted(out.glob("*.dxf")) if out.exists() else []
        if not files:
            raise HTTPException(404, "Chưa có file DXF")
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for f in files:
                z.write(f, f.name)
        stem = Path(job.files[0]["name"]).stem if len(job.files) == 1 else "pdf2cad"
        return StreamingResponse(iter([buf.getvalue()]), media_type="application/zip",
                                 headers={"Content-Disposition":
                                          f"attachment; filename*=UTF-8''{_q(stem)}_dxf.zip"})

    @app.get("/api/jobs/{job_id}/preview/{name}")
    def job_preview(job_id: str, name: str, request: Request):
        p = _out_file(mgr.get(job_id), name)
        access_log(request, f"xem DXF {p.name}")
        cache = p.with_suffix(".preview.json")
        if not cache.exists():
            cache.write_text(json.dumps(dxf_preview(p), ensure_ascii=False), encoding="utf-8")
        return Response(cache.read_text(encoding="utf-8"), media_type="application/json")

    @app.get("/api/jobs/{job_id}/pdf/{idx}/{page}.png")
    def job_pdf_page(job_id: str, idx: int, page: int, request: Request, dpi: int = 100):
        job = mgr.get(job_id)
        if not (0 <= idx < len(job.files)):
            raise HTTPException(404, "Không có file")
        f = job.files[idx]
        if not (1 <= page <= f["pages"]):
            raise HTTPException(404, "Không có trang")
        access_log(request, f"xem bản gốc {f['name']} trang {page}")
        with pymupdf.open(f["path"]) as d:
            png = d[page - 1].get_pixmap(dpi=min(max(dpi, 30), 200), alpha=False).tobytes("png")
        return Response(png, media_type="image/png")

    # ---- tab 3D cho bản DXF chuyển từ PDF/scan: chi tiết tròn xoay -> 3D, chỉnh từng đoạn -> vẽ DXF mới
    from .. import turned

    @app.post("/api/jobs/{job_id}/model3d/{name}")
    def job_model3d(job_id: str, name: str, request: Request):
        p = _out_file(mgr.get(job_id), name)
        try:
            prof = turned.extract_profile(p)
        except Exception as e:
            prof, err = None, f"{type(e).__name__}: {e}"
        else:
            err = None
        rep = next((r for r in mgr.get(job_id).reports if r.get("output") == p.name), {})
        access_log(request, f"xem 3D {p.name}" + ("" if prof else " (không nhận ra chi tiết tròn xoay)"))
        return {"available": prof is not None, "profile": prof, "error": err, "title": p.stem,
                "scale_source": rep.get("scale_source"), "scale": rep.get("scale")}

    @app.post("/api/jobs/{job_id}/redraw3d/{name}")
    def job_redraw3d(job_id: str, name: str, body: dict, request: Request):
        job = mgr.get(job_id)
        src = _out_file(job, name)
        steps = []
        for s in (body.get("steps") or [])[:60]:
            try:
                steps.append({k: float(str(s.get(k, 0)).replace(",", ".")) for k in ("len", "D", "d")})
            except (ValueError, AttributeError):
                raise HTTPException(400, "Kích thước không hợp lệ")
        stem = re.sub(r"_3d\d*$", "", src.stem)
        i = 1
        while (job.dir / "out" / f"{stem}_3d{i}.dxf").exists():
            i += 1
        out = job.dir / "out" / f"{stem}_3d{i}.dxf"
        try:
            turned.draw_turned(steps, out, title=stem[:40],
                               note="DRAWN FROM EDITED 3D PROFILE: " + "  ".join(
                                   f"{tpl._fmt(s['len'])}x%%c{tpl._fmt(s['D'])}" + (f"/%%c{tpl._fmt(s['d'])}" if s["d"] else "")
                                   for s in steps)[:200])
        except ValueError as e:
            raise HTTPException(400, str(e))
        prof = turned.steps_profile(steps)
        job.reports.append({"file": (job.files[0]["name"] if job.files else stem), "page": 1, "kind": "3d-edit",
                            "output": out.name, "counts": {"Step": len(prof["steps"])}, "scale": 1.0,
                            "scale_source": "3d", "seconds": 0.0, "error": None, "warning": None, "rotated": 0})
        access_log(request, f"vẽ lại từ 3D {src.name} -> {out.name} (L={tpl._fmt(prof['L'])}, "
                            f"Φmax={tpl._fmt(prof['Dmax'])}, {len(prof['steps'])} đoạn)")
        return {"job": job.id, "output": out.name, "profile": prof}

    # ------------------------------------------------------------- mẫu tham số + chat vẽ lại
    from .. import template as tpl
    from ..i18n import desc_bi, en_of, ja_of

    learned_dir = Path(os.environ.get("PDF2CAD_LEARNED", tpl.LEARNED_DIR))

    def _templates():
        return {t.name: t for t in tpl.list_templates(s.templates_dir, learned_dir)}

    @app.post("/api/jobs/{job_id}/learn/{name}")
    def job_learn(job_id: str, name: str, request: Request):
        """Dạy bot bản vẽ vừa chuyển: đọc khung tên + bảng tham số -> mẫu nháp."""
        from ..learn import learn_from_dxf
        job = mgr.get(job_id)
        p = _out_file(job, name)
        access_log(request, f"dạy bot từ {p.name}")
        try:
            res = learn_from_dxf(p, learned_dir, source=name)
        except Exception as e:
            raise HTTPException(500, f"{type(e).__name__}: {e}")
        if not res:
            raise HTTPException(422, "Không tìm thấy bảng tham số (hàng tiêu đề D/H/T/L...) trên bản vẽ này")
        return {"templates": [{k: v for k, v in r.items() if k != "dir"} for r in res]}

    @app.get("/api/templates")
    def templates_list():
        out = []
        for t in _templates().values():
            keys = tpl.keys_of(t)
            k0 = keys[0]
            out.append({"name": t.name, "title": t.title, "key": ", ".join(keys),
                        "keys": [_v for _v in tpl.candidates(t, {}, k0)],
                        "kind": t.spec.get("kind", "warp"), "draft": bool(t.spec.get("draft")),
                        "params": {p: d.get("desc", "") for p, d in t.params.items()
                                   if not d.get("derived")},
                        "params_en": {p: en_of(desc_bi(d)) for p, d in t.params.items()
                                      if not d.get("derived")},
                        "params_ja": {p: ja_of(desc_bi(d)) for p, d in t.params.items()
                                      if not d.get("derived")},
                        "rules": [r["text"] for r in t.spec.get("rules", [])],
                        "example": (t.spec.get("examples") or [f"{k0}.{t.spec['table'][-1][k0]} L=80"])[0],
                        **_table_view(t.spec.get("table") or [])})
        return out

    # ---- bot hội thoại (giữ ngữ cảnh theo session)
    from ..bot import Bot, Session
    sessions: dict[str, Session] = {}

    def _draw_into_job(t, vals) -> dict:
        job_id = uuid.uuid4().hex
        job_dir = s.data_dir / "jobs" / job_id
        (job_dir / "out").mkdir(parents=True)
        g = tpl.generate(t, vals, job_dir / "out" / f"{tpl.file_stem(t, vals)}.dxf")
        out_name = Path(g["output"]).name
        mgr.jobs[job_id] = Job(job_id, job_dir, [], ConvertOptions(), status="done",
                               finished=time.time(),
                               reports=[{"file": t.name, "page": 1, "kind": "template",
                                         "output": out_name, "counts": {}, "scale": 1.0,
                                         "scale_source": "template", "seconds": 0.0,
                                         "error": None, "warning": None, "rotated": 0}])
        return {"job": job_id, "output": out_name, "scale": g.get("scale"),
                "check": [[n, float(w), float(gg)] for n, w, gg in g["check"]]}

    @app.post("/api/bot")
    def bot_reply(body: dict, request: Request):
        tps = _templates()
        if not tps:
            raise HTTPException(404, "Chưa có mẫu nào")
        name = str(body.get("template") or next(iter(tps)))
        t = tps.get(name)
        if t is None:
            raise HTTPException(404, "Không có mẫu này")
        now = time.time()
        for k in [k for k, v in sessions.items() if now - v.updated > 6 * 3600]:
            sessions.pop(k, None)
        sid = str(body.get("session") or "")
        sess = sessions.get(sid) if _ID_RE.match(sid) else None
        if sess is None or sess.template != name:
            sid = uuid.uuid4().hex
            sess = sessions[sid] = Session(name)
        if len(sessions) > 2000:                       # chặn phình bộ nhớ
            oldest = min(sessions, key=lambda k: sessions[k].updated)
            sessions.pop(oldest, None)
        msg = str(body.get("message", ""))[:500]
        lang = body.get("lang") if body.get("lang") in ("en", "ja") else "vi"
        bot = Bot(t, lambda vals: _draw_into_job(t, vals))
        engine = "rules"
        try:
            cl = llm.client()
            r = None
            if cl is not None and msg.strip():
                try:                                   # LLM hiểu câu -> gọi công cụ của bot
                    r, model = llm.LlmBot(bot, cl).respond(sess, msg, lang)
                    engine = f"llm:{model}"
                except llm.LlmError as e:              # LM Studio tắt / lỗi -> bot theo luật
                    logging.getLogger("pdf2cad").info("LLM không dùng được (%s) -> bot theo luật", e)
            if r is None:
                r = bot.respond(sess, msg)
        except Exception as e:
            raise HTTPException(500, f"{type(e).__name__}: {e}")
        if msg.strip():
            short = " ".join(msg.split())
            short = short[:60] + ("…" if len(short) > 60 else "")
            access_log(request, f"bot [{name}] “{short}”" + (f" -> {r.output}" if r.output else ""))
        return {"session": sid, "engine": engine, **r.as_dict()}

    # ---- tab 3D: mặt cắt chi tiết + chỉnh kích thước bất kỳ rồi vẽ lại
    from .. import solid3d

    @app.post("/api/model3d")
    def model3d(body: dict):
        t = _templates().get(str(body.get("template") or ""))
        if t is None:
            raise HTTPException(404, "Không có mẫu này")
        vals = {k: v for k, v in (body.get("params") or {}).items()}
        keys = tpl.keys_of(t)
        try:
            vals = {k: float(v) if isinstance(v, (int, float)) or str(v).replace(".", "", 1).isdigit() else v
                    for k, v in vals.items()}
            if any(k not in vals for k in keys) or "L" not in vals:
                # chưa có bản vẽ: lấy cỡ đầu tiên của bảng + L hợp lệ đầu tiên để luôn có hình xem / chỉnh
                req = {k: v for k, v in vals.items() if isinstance(v, float) and k in t.params}
                for k in keys:
                    if k not in req:
                        c = tpl.candidates(t, req, k)
                        if c:
                            req[k] = c[0]
                if "L" not in req:
                    rows = tpl.matching_rows(t, req)
                    first = tpl.cell_first(tpl.cell_info(rows[0].get("L"), float(t.spec.get("values_step", 10)))) \
                        if rows else None
                    if first is not None:
                        req["L"] = first
                vals, _, errs = tpl.resolve_params(t, req)
                if errs:
                    raise ValueError("; ".join(str(e) for e in errs))
            prof = solid3d.profile(t, vals)
        except Exception as e:                      # thiếu / sai tham số -> chưa dựng được khối
            prof, err = None, f"{type(e).__name__}: {e}"
        else:
            err = None
        params = []
        for p, d in t.params.items():
            if d.get("derived") or d.get("info"):
                continue
            db = desc_bi(d)
            item = {"name": p, "value": vals.get(p), "key": p in keys,
                    "desc": str(db), "desc_en": en_of(db), "desc_ja": ja_of(db)}
            if p in keys:
                item["choices"] = tpl.candidates(t, {k: vals[k] for k in keys if k in vals and k != p}, p)
            params.append(item)
        return {"available": prof is not None, "profile": prof, "error": err, "params": params,
                "title": t.title, "values": {k: v for k, v in vals.items() if isinstance(v, (int, float, str))}}

    @app.post("/api/bot/apply")
    def bot_apply(body: dict, request: Request):
        """Áp kích thước người dùng chỉnh ở tab 3D (giá trị tuyệt đối) vào phiên bot rồi vẽ lại DXF."""
        tps = _templates()
        name = str(body.get("template") or "")
        t = tps.get(name)
        if t is None:
            raise HTTPException(404, "Không có mẫu này")
        sid = str(body.get("session") or "")
        sess = sessions.get(sid) if _ID_RE.match(sid) else None
        if sess is None or sess.template != name:
            sid = uuid.uuid4().hex
            sess = sessions[sid] = Session(name)
        values = {}
        for k, v in (body.get("values") or {}).items():
            try:
                values[str(k)] = float(str(v).replace(",", "."))
            except ValueError:
                continue
        try:
            r = Bot(t, lambda vals: _draw_into_job(t, vals)).apply(sess, values, {})
        except Exception as e:
            raise HTTPException(500, f"{type(e).__name__}: {e}")
        kv = ", ".join(f"{k}={tpl._fmt(v)}" for k, v in values.items())
        access_log(request, f"chỉnh kích thước ở tab 3D [{name}] {kv}" + (f" -> {r.output}" if r.output else ""))
        return {"session": sid, "engine": "manual", **r.as_dict()}

    @app.post("/api/templates/{name}/chat")
    def templates_chat(name: str, body: dict):
        t = _templates().get(name)
        if t is None:
            raise HTTPException(404, "Không có mẫu này")
        msg = str(body.get("message", ""))[:500]
        job_id = uuid.uuid4().hex
        job_dir = s.data_dir / "jobs" / job_id
        (job_dir / "out").mkdir(parents=True)
        try:
            res = tpl.chat(t, msg, job_dir / "out")
        except Exception as e:
            shutil.rmtree(job_dir, ignore_errors=True)
            raise HTTPException(500, f"{type(e).__name__}: {e}")
        res["check"] = [[n, float(w), float(g)] for n, w, g in res.get("check", [])]
        if not res.get("output"):
            shutil.rmtree(job_dir, ignore_errors=True)
            return res
        out_name = Path(res["output"]).name
        job = Job(job_id, job_dir, [], ConvertOptions(), status="done", finished=time.time(),
                  reports=[{"file": t.name, "page": 1, "kind": "template", "output": out_name,
                            "counts": {}, "scale": 1.0, "scale_source": "template",
                            "seconds": 0.0, "error": None, "warning": None, "rotated": 0}])
        mgr.jobs[job_id] = job
        res.update(job=job_id, output=out_name)
        return res

    return app


def _q(s: str) -> str:
    from urllib.parse import quote
    return quote(s)


def _disable_quickedit():
    """Windows: tắt QuickEdit của cửa sổ console này.

    Khi QuickEdit bật, lỡ bấm chuột vào cửa sổ đen (tiêu đề hiện "Select") thì mọi lệnh ghi
    ra console bị treo => server ngừng trả lời, trình duyệt báo ERR_CONNECTION_TIMED_OUT.
    """
    if os.name != "nt":
        return
    try:
        import ctypes
        k32 = ctypes.windll.kernel32
        h = k32.GetStdHandle(-10)                       # STD_INPUT_HANDLE
        mode = ctypes.c_uint32()
        if k32.GetConsoleMode(h, ctypes.byref(mode)):
            ENABLE_QUICK_EDIT, ENABLE_EXTENDED_FLAGS = 0x0040, 0x0080
            k32.SetConsoleMode(h, (mode.value & ~ENABLE_QUICK_EDIT) | ENABLE_EXTENDED_FLAGS)
    except Exception:
        pass


def _existing_server(port: int) -> str | None:
    """Có tiến trình nào đang giữ cổng chưa? Trả về mô tả, hoặc None nếu cổng trống."""
    import socket
    import urllib.request
    with socket.socket() as c:
        c.settimeout(1)
        if c.connect_ex(("127.0.0.1", port)) != 0:
            return None
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/health", timeout=3) as r:
            info = json.loads(r.read())
            return f"pdf2cad {info.get('version', '?')} (đang hoạt động)"
    except Exception:
        return "chương trình khác hoặc pdf2cad bị treo (không phản hồi)"


def _bind(host: str, port: int):
    """Tạo socket lắng nghe. Windows: SO_EXCLUSIVEADDRUSE để 2 tiến trình không cùng chiếm 1 cổng."""
    import socket
    fam = socket.AF_INET6 if ":" in host else socket.AF_INET
    sock = socket.socket(fam, socket.SOCK_STREAM)
    if os.name == "nt":
        sock.setsockopt(socket.SOL_SOCKET, getattr(socket, "SO_EXCLUSIVEADDRUSE", -5), 1)
    else:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((host, port))
    sock.listen(128)
    sock.set_inheritable(True)
    return sock


def _lan_ips() -> list[str]:
    """Các địa chỉ IPv4 của máy (để báo cho client)."""
    import socket as _s
    ips: list[str] = []
    try:
        for info in _s.getaddrinfo(_s.gethostname(), None, _s.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    try:  # IP của card mạng ra ngoài (không gửi gói tin nào)
        with _s.socket(_s.AF_INET, _s.SOCK_DGRAM) as u:
            u.connect(("10.255.255.255", 1))
            ip = u.getsockname()[0]
            if not ip.startswith("127.") and ip not in ips:
                ips.insert(0, ip)
    except OSError:
        pass
    return ips


def _setup_log_file(path: str) -> None:
    """Chạy nền (pythonw) không có cửa sổ: chuyển print + log của uvicorn ra file xoay vòng."""
    import logging
    import logging.handlers
    import sys
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    h = logging.handlers.RotatingFileHandler(p, maxBytes=5_000_000, backupCount=5, encoding="utf-8")
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    root = logging.getLogger()
    root.addHandler(h)
    root.setLevel(logging.INFO)
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access", "pdf2cad"):
        lg = logging.getLogger(name)
        lg.addHandler(h)
        lg.propagate = False

    class _ToLog:
        def __init__(self, level):
            self.level, self.buf = level, ""
        def write(self, msg):
            self.buf += msg
            while "\n" in self.buf:
                line, self.buf = self.buf.split("\n", 1)
                if line.strip():
                    logging.getLogger("pdf2cad").log(self.level, line)
        def flush(self):
            pass
        def isatty(self):
            return False

    sys.stdout = _ToLog(logging.INFO)
    sys.stderr = _ToLog(logging.ERROR)


def serve(host: str = "127.0.0.1", port: int = 5020, settings: Settings | None = None,
          open_browser: bool = False, log_file: str | None = None) -> int:
    import sys

    import uvicorn

    from ..cli import utf8_stdio
    utf8_stdio()                     # output bị chuyển hướng (cp1252) không làm dừng server khi in tiếng Việt
    if log_file or sys.stdout is None:  # pythonw: stdout = None
        _setup_log_file(log_file or str((settings or Settings()).data_dir / "server.log"))
    _disable_quickedit()
    url = f"http://localhost:{port}"
    other = _existing_server(port)
    if other:
        print(f"Cổng {port} đang bị chiếm bởi: {other}.")
        if other.startswith("pdf2cad") and "hoạt động" in other:
            print(f"Server đã chạy sẵn -> mở {url}")
            if open_browser:
                import webbrowser
                webbrowser.open(url)
            return 0
        print("Hãy đóng cửa sổ server cũ (hoặc tắt tiến trình python.exe trong Task Manager), "
              "hoặc chạy với cổng khác: --port 5021")
        return 2
    try:
        sock = _bind(host, port)
    except OSError as e:
        print(f"Không mở được cổng {port}: {e}. Thử --port khác.")
        return 2

    s = settings or Settings()
    app = create_app(s)
    print(f"pdf2cad web {__version__}: {url}   (dữ liệu tạm: {s.data_dir})")
    if host in ("0.0.0.0", "::"):
        ips = _lan_ips()
        if ips:
            for ip in ips:
                print(f"Máy khác truy cập: http://{ip}:{port}")
        else:
            print(f"Đang mở cho cả mạng: máy khác truy cập http://<IP-máy-này>:{port}")
    print("Đóng cửa sổ này (hoặc Ctrl+C) để tắt server.")
    if _access_on():
        print(f"\nNhật ký truy cập (lưu tại {s.data_dir / 'logs'}\\access-<ngày>.jsonl):\n"
              f"{'[thời gian]':<21} {'IP':<15} {'tên máy':<20} {'máy':<5} thao tác")
    if open_browser:
        import webbrowser
        threading.Timer(1.5, lambda: webbrowser.open(url)).start()
    # access_log tắt: trang web hỏi tiến độ mỗi giây, ghi log từng request vừa rối vừa chậm
    config = uvicorn.Config(app, log_level="warning", access_log=False)
    uvicorn.Server(config).run(sockets=[sock])
    return 0


def main() -> int:
    """Lệnh pdf2cad-web (tương đương: pdf2cad serve ...)."""
    import sys

    from ..cli import main as cli_main
    return cli_main(["serve", *sys.argv[1:]])
