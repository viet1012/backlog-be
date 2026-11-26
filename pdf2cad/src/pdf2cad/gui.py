"""Giao diện đồ hoạ pdf2cad: kéo thả PDF -> DXF.

Chạy:  pdf2cad-gui            (sau khi pip install -e ".[gui]")
       python -m pdf2cad gui
       run_gui.bat [file.pdf ...]   (Windows; có thể kéo thả PDF lên file .bat)
"""
from __future__ import annotations

import math
import multiprocessing
import os
import sys
from dataclasses import replace
from pathlib import Path

import pymupdf
from PySide6.QtCore import (QObject, QPointF, QRectF, QSettings, Qt, QThread, QTimer, QUrl,
                            Signal)
from PySide6.QtGui import (QAction, QBrush, QColor, QDesktopServices, QFont, QImage,
                           QPainter, QPainterPath, QPen, QPixmap)
from PySide6.QtWidgets import (QAbstractItemView, QApplication, QCheckBox, QDoubleSpinBox,
                               QFileDialog, QFormLayout, QFrame, QGraphicsScene,
                               QGraphicsView, QGroupBox, QHBoxLayout, QHeaderView, QLabel,
                               QLineEdit, QListWidget, QListWidgetItem, QMainWindow,
                               QMessageBox, QProgressBar, QPushButton, QSpinBox, QSplitter,
                               QTableWidget, QTableWidgetItem, QTabWidget, QVBoxLayout,
                               QWidget)

from . import __version__
from .classify import classify_page
from .pipeline import INPUT_EXTS, ConvertOptions, PageReport, convert, parse_pages

APP = "pdf2cad"
COLS = ["File", "Trang", "Loại", "Tỷ lệ", "Nguồn tỷ lệ", "Đối tượng", "Thời gian", "Kết quả"]


def _collect_pdfs(paths) -> list[Path]:
    """Nhận file hoặc thư mục (quét đệ quy *.pdf, *.tif, *.png, *.jpg...)."""
    out: list[Path] = []
    for p in map(Path, paths):
        if p.is_dir():
            out += sorted(q for q in p.rglob("*") if q.suffix.lower() in INPUT_EXTS)
        elif p.suffix.lower() in INPUT_EXTS and p.is_file():
            out.append(p)
    return out


def _open_path(path: str | Path):
    QDesktopServices.openUrl(QUrl.fromLocalFile(str(path)))


# ----------------------------------------------------------------- vùng kéo thả
class DropZone(QFrame):
    filesDropped = Signal(list)

    _CSS = ("DropZone{border:2px dashed %s;border-radius:10px;background:%s;}"
            "QLabel{color:#555;font-size:14px;background:transparent;border:none;}")

    def __init__(self):
        super().__init__()
        self.setAcceptDrops(True)
        self.setMinimumHeight(120)
        self.setCursor(Qt.PointingHandCursor)
        lab = QLabel("⬇  Kéo thả PDF / TIFF / ảnh scan hoặc thư mục vào đây\n(hoặc bấm để chọn file)")
        lab.setAlignment(Qt.AlignCenter)
        QVBoxLayout(self).addWidget(lab)
        self._style(False)

    def _style(self, hot: bool):
        self.setStyleSheet(self._CSS % (("#1a73e8", "#e8f0fe") if hot else ("#9aa0a6", "#fafafa")))

    def mousePressEvent(self, e):
        files, _ = QFileDialog.getOpenFileNames(self, "Chọn file PDF",
                                                QSettings().value("last_dir", ""),
                                                "Bản vẽ (*.pdf *.tif *.tiff *.png *.jpg *.jpeg *.bmp)")
        if files:
            QSettings().setValue("last_dir", str(Path(files[0]).parent))
            self.filesDropped.emit(files)

    def dragEnterEvent(self, e):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()
            self._style(True)

    def dragLeaveEvent(self, e):
        self._style(False)

    def dropEvent(self, e):
        self._style(False)
        self.filesDropped.emit([u.toLocalFile() for u in e.mimeData().urls() if u.isLocalFile()])


# ----------------------------------------------------------------- xem trước
class ZoomView(QGraphicsView):
    """QGraphicsView có zoom bằng con lăn, kéo để pan, double-click để vừa khung."""

    def __init__(self, dark=False):
        super().__init__()
        self.setScene(QGraphicsScene(self))
        self.setRenderHints(QPainter.Antialiasing | QPainter.SmoothPixmapTransform)
        self.setDragMode(QGraphicsView.ScrollHandDrag)
        self.setTransformationAnchor(QGraphicsView.AnchorUnderMouse)
        self.setBackgroundBrush(QColor("#1e1e1e" if dark else "#e0e0e0"))
        self._msg = None

    def message(self, text: str):
        self.scene().clear()
        self.resetTransform()
        self._msg = self.scene().addText(text)
        self._msg.setDefaultTextColor(QColor("#888"))

    def fit(self):
        r = self.scene().itemsBoundingRect()
        if r.isValid():
            self.fitInView(r.adjusted(-10, -10, 10, 10), Qt.KeepAspectRatio)

    def wheelEvent(self, e):
        f = 1.25 if e.angleDelta().y() > 0 else 0.8
        self.scale(f, f)

    def mouseDoubleClickEvent(self, e):
        self.fit()

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self.fit()


class PdfPreview(ZoomView):
    def show_pdf(self, path: Path, pno: int = 1) -> str:
        self.scene().clear()
        self.resetTransform()
        try:
            with pymupdf.open(path) as doc:
                pno = max(1, min(pno, doc.page_count))
                page = doc[pno - 1]
                pix = page.get_pixmap(dpi=110, alpha=False)
                img = QImage(pix.samples, pix.width, pix.height, pix.stride,
                             QImage.Format_RGB888).copy()
                self.scene().addPixmap(QPixmap.fromImage(img))
                kinds = [classify_page(p).value for p in doc]
                info = (f"{path.name} — {doc.page_count} trang "
                        f"(vector {kinds.count('vector')}, scan {kinds.count('raster')}) | "
                        f"trang {pno}: {page.rect.width * 25.4 / 72:.0f}×"
                        f"{page.rect.height * 25.4 / 72:.0f} mm")
        except Exception as ex:
            self.message(f"Không mở được PDF:\n{ex}")
            return ""
        QTimer.singleShot(0, self.fit)
        return info


class DxfPreview(ZoomView):
    """Vẽ nhanh LINE / CIRCLE / ARC / LWPOLYLINE / TEXT để kiểm tra kết quả."""

    LAYER_COLORS = {"OUTLINE": "#ffffff", "THIN": "#4fc3f7", "HIDDEN": "#ffd54f",
                    "CENTER": "#ef5350", "FILL": "#9e9e9e", "TEXT": "#81c784",
                    "RASTER": "#ce93d8", "TEXT_OCR": "#81c784", "TEXT_CHECK": "#ff5252"}

    def __init__(self):
        super().__init__(dark=True)

    def show_dxf(self, path: str) -> str:
        import ezdxf
        self.scene().clear()
        self.resetTransform()
        try:
            msp = ezdxf.readfile(path).modelspace()
        except Exception as ex:
            self.message(f"Không đọc được DXF:\n{ex}")
            return ""
        paths: dict[str, QPainterPath] = {}
        n = 0
        fy = lambda x, y: QPointF(x, -y)  # DXF trục Y hướng lên
        doc_layers = msp.doc.layers
        hidden = {l.dxf.name for l in doc_layers if l.is_off() or l.is_frozen()}
        def flat(entities):          # DIMENSION/INSERT -> các nét con (đường, chữ, mũi tên)
            for e in entities:
                if e.dxftype() in ("DIMENSION", "INSERT", "LEADER"):
                    for v in e.virtual_entities():
                        v.dxf.layer = e.dxf.layer
                        yield v
                else:
                    yield e

        for e in flat(msp):
            t = e.dxftype()
            if e.dxf.layer in hidden:
                continue
            if t == "MTEXT":         # chữ kích thước: quy về TEXT đơn giản để xem
                txt = (e.plain_text(split=False).replace("%%c", "Ø").replace("%%p", "±")
                       .replace("^", "/").replace("/ ", "/"))
                ins, h = e.dxf.insert, e.dxf.char_height
                ap = e.dxf.get("attachment_point", 1)
                w = 0.6 * h * len(txt)
                x0 = ins.x - ((ap - 1) % 3) * 0.5 * w
                y0 = ins.y - {0: 1, 1: 0.5, 2: 0}[(ap - 1) // 3] * h
                item = self.scene().addText(txt, QFont("Arial", 10))
                item.setDefaultTextColor(QColor(self.LAYER_COLORS.get("TEXT_OCR")))
                item.setScale(h / 10 * 1.4)
                item.setPos(x0, -y0 - h * 1.6)
                n += 1
                continue
            pp = paths.setdefault(e.dxf.layer, QPainterPath())
            if t == "LINE":
                pp.moveTo(fy(e.dxf.start.x, e.dxf.start.y))
                pp.lineTo(fy(e.dxf.end.x, e.dxf.end.y))
            elif t == "CIRCLE":
                c, r = e.dxf.center, e.dxf.radius
                pp.addEllipse(fy(c.x, c.y), r, r)
            elif t == "ARC":
                c, r = e.dxf.center, e.dxf.radius
                sweep = (e.dxf.end_angle - e.dxf.start_angle) % 360 or 360
                rect = QRectF(c.x - r, -c.y - r, 2 * r, 2 * r)
                a0 = math.radians(e.dxf.start_angle)
                pp.moveTo(fy(c.x + r * math.cos(a0), c.y + r * math.sin(a0)))
                pp.arcTo(rect, e.dxf.start_angle, sweep)
            elif t == "LWPOLYLINE":
                pts = [fy(x, y) for x, y in e.get_points("xy")]
                if pts:
                    pp.moveTo(pts[0])
                    for q in pts[1:]:
                        pp.lineTo(q)
                    if e.closed:
                        pp.closeSubpath()
            elif t == "TEXT":
                h = max(e.dxf.height, 0.1)
                f = QFont("Arial")
                f.setPointSizeF(10)
                ins = e.dxf.insert
                item = self.scene().addText(e.dxf.text, f)
                item.setDefaultTextColor(QColor(self.LAYER_COLORS.get(e.dxf.layer,
                                                                      self.LAYER_COLORS["TEXT"])))
                s = h / 10 * 1.4
                item.setScale(s)
                item.setRotation(-e.dxf.rotation)
                item.setPos(ins.x, -ins.y - h * 1.6)
            else:
                continue
            n += 1
        for layer, pp in paths.items():
            pen = QPen(QColor(self.LAYER_COLORS.get(layer, "#ffffff")))
            pen.setCosmetic(True)
            pen.setWidthF(1.6 if layer == "OUTLINE" else 1.0)
            if layer == "HIDDEN":
                pen.setStyle(Qt.DashLine)
            elif layer == "CENTER":
                pen.setStyle(Qt.DashDotLine)
            self.scene().addPath(pp, pen, QBrush(Qt.NoBrush))
        QTimer.singleShot(0, self.fit)
        r = self.scene().itemsBoundingRect()
        return f"{Path(path).name} — {n} đối tượng, khung {r.width():.1f}×{r.height():.1f} mm"


# ----------------------------------------------------------------- luồng xử lý
class Worker(QObject):
    page_done = Signal(str, object, int, int)   # pdf, PageReport, done, total
    file_started = Signal(int, int, str)        # chỉ số file, tổng file, tên
    finished = Signal(bool)                     # True nếu bị dừng giữa chừng

    def __init__(self, files: list[Path], opt: ConvertOptions, beside_pdf: bool):
        super().__init__()
        self.files, self.opt, self.beside_pdf = files, opt, beside_pdf
        self._stop = False

    def stop(self):
        self._stop = True

    def run(self):
        for i, f in enumerate(self.files):
            if self._stop:
                break
            self.file_started.emit(i, len(self.files), f.name)
            opt = self.opt
            if self.beside_pdf:
                opt = replace(opt, out_dir=str(f.parent / f"{f.stem}_dxf"))
            try:
                convert(f, opt,
                        on_page=lambda r, d, t, f=f: self.page_done.emit(str(f), r, d, t),
                        should_stop=lambda: self._stop)
            except Exception as ex:  # lỗi mở file...
                self.page_done.emit(str(f), PageReport(0, "error", 1.0, "-",
                                                       error=f"{type(ex).__name__}: {ex}"), 1, 1)
        self.finished.emit(self._stop)


# ----------------------------------------------------------------- cửa sổ chính
class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"pdf2cad {__version__} — PDF → DXF")
        self.resize(1280, 800)
        self.setAcceptDrops(True)  # thả vào bất kỳ đâu trong cửa sổ
        self.s = QSettings()
        self.thread: QThread | None = None
        self.worker: Worker | None = None
        self.last_out: str | None = None

        # --- cột trái: file + tuỳ chọn
        self.drop = DropZone()
        self.drop.filesDropped.connect(self.add_files)
        self.list = QListWidget()
        self.list.setSelectionMode(QAbstractItemView.ExtendedSelection)
        self.list.currentItemChanged.connect(self._preview_selected)
        b_rm, b_clr = QPushButton("Xoá mục chọn"), QPushButton("Xoá hết")
        b_rm.clicked.connect(lambda: [self.list.takeItem(self.list.row(i))
                                      for i in self.list.selectedItems()])
        b_clr.clicked.connect(self.list.clear)
        row = QHBoxLayout()
        row.addWidget(b_rm)
        row.addWidget(b_clr)

        g_files = QGroupBox("File PDF")
        v = QVBoxLayout(g_files)
        v.addWidget(self.drop)
        v.addWidget(self.list, 1)
        v.addLayout(row)

        g_opt = QGroupBox("Tuỳ chọn")
        form = QFormLayout(g_opt)
        self.beside = QCheckBox("Lưu cạnh file PDF (thư mục <tên>_dxf)")
        self.out_dir = QLineEdit()
        b_out = QPushButton("…")
        b_out.setFixedWidth(32)
        b_out.clicked.connect(self._pick_out)
        out_row = QHBoxLayout()
        out_row.addWidget(self.out_dir)
        out_row.addWidget(b_out)
        self.beside.toggled.connect(lambda on: (self.out_dir.setEnabled(not on),
                                                b_out.setEnabled(not on)))
        self.pages = QLineEdit()
        self.pages.setPlaceholderText("tất cả  (vd: 1-3,5)")
        self.auto_scale = QCheckBox("Tự đọc từ khung tên")
        self.scale = QDoubleSpinBox()
        self.scale.setRange(0.001, 10000)
        self.scale.setDecimals(3)
        self.scale.setPrefix("1 : ")
        self.auto_scale.toggled.connect(lambda on: self.scale.setEnabled(not on))
        sc_row = QHBoxLayout()
        sc_row.addWidget(self.auto_scale)
        sc_row.addWidget(self.scale)
        self.dpi = QSpinBox()
        self.dpi.setRange(72, 1200)
        self.dpi.setSingleStep(50)
        self.workers = QSpinBox()
        self.workers.setRange(0, os.cpu_count() or 8)
        self.workers.setSpecialValueText("Tự động")
        self.thick = QDoubleSpinBox()
        self.thick.setRange(0.01, 10)
        self.thick.setSingleStep(0.05)
        self.thick.setSuffix(" pt")
        self.arcs = QCheckBox("Fit cung tròn / đường tròn")
        self.raster = QCheckBox("Xử lý trang scan (OpenCV)")
        self.ocr = QCheckBox("OCR chữ khi PDF không có lớp text")
        self.ocr.setToolTip("PDF in bằng Print to PDF / chữ bị outline: chữ chỉ là hình vẽ.\n"
                            "Bật để nhận dạng lại thành TEXT sửa được (layer TEXT_OCR;\n"
                            "chữ cần soát ở TEXT_CHECK; nét chữ gốc ở TEXT_GLYPH - tắt sẵn).")
        self.rotate = QCheckBox("Tự xoay bản vẽ theo hướng chữ")
        self.stamps = QCheckBox("Bản scan: xoá dấu mộc (SPC, MassPro, chữ ký)")
        self.open_after = QCheckBox("Mở thư mục kết quả khi xong")

        form.addRow(self.beside)
        form.addRow("Thư mục ra:", out_row)
        form.addRow("Trang:", self.pages)
        form.addRow("Tỷ lệ:", sc_row)
        form.addRow("DPI (scan):", self.dpi)
        form.addRow("Số tiến trình:", self.workers)
        form.addRow("Ngưỡng nét đậm:", self.thick)
        form.addRow(self.arcs)
        form.addRow(self.raster)
        form.addRow(self.ocr)
        form.addRow(self.rotate)
        form.addRow(self.stamps)
        form.addRow(self.open_after)

        self.btn_run = QPushButton("▶  Chuyển sang DXF")
        self.btn_run.setMinimumHeight(40)
        self.btn_run.setStyleSheet("QPushButton{background:#1a73e8;color:white;font-weight:bold;"
                                   "border-radius:6px;font-size:14px}"
                                   "QPushButton:disabled{background:#9aa0a6}")
        self.btn_run.clicked.connect(self.start)
        self.btn_stop = QPushButton("■  Dừng")
        self.btn_stop.setMinimumHeight(40)
        self.btn_stop.setEnabled(False)
        self.btn_stop.clicked.connect(self.stop)
        run_row = QHBoxLayout()
        run_row.addWidget(self.btn_run, 3)
        run_row.addWidget(self.btn_stop, 1)

        left = QWidget()
        lv = QVBoxLayout(left)
        lv.setContentsMargins(0, 0, 0, 0)
        lv.addWidget(g_files, 1)
        lv.addWidget(g_opt)
        lv.addLayout(run_row)

        # --- cột phải: xem trước + kết quả
        self.pdf_view = PdfPreview()
        self.dxf_view = DxfPreview()
        self.pdf_view.message("Chọn một file PDF để xem trước")
        self.dxf_view.message("Double-click một dòng kết quả để xem DXF")
        self.tabs = QTabWidget()
        self.tabs.addTab(self.pdf_view, "PDF gốc")
        self.tabs.addTab(self.dxf_view, "DXF kết quả")
        self.preview_info = QLabel()
        self.preview_info.setStyleSheet("color:#555")

        self.table = QTableWidget(0, len(COLS))
        self.table.setHorizontalHeaderLabels(COLS)
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.verticalHeader().hide()
        hh = self.table.horizontalHeader()
        hh.setSectionResizeMode(QHeaderView.ResizeToContents)
        hh.setSectionResizeMode(len(COLS) - 1, QHeaderView.Stretch)
        self.table.cellDoubleClicked.connect(self._row_dblclick)
        self.table.setContextMenuPolicy(Qt.ActionsContextMenu)
        for text, fn in (("Xem DXF", self._view_selected_dxf),
                         ("Mở file DXF", lambda: self._open_selected(False)),
                         ("Mở thư mục chứa", lambda: self._open_selected(True))):
            a = QAction(text, self.table)
            a.triggered.connect(fn)
            self.table.addAction(a)

        self.progress = QProgressBar()
        self.status_lbl = QLabel("Sẵn sàng")
        b_open = QPushButton("Mở thư mục kết quả")
        b_open.clicked.connect(lambda: self.last_out and _open_path(self.last_out))
        st = QHBoxLayout()
        st.addWidget(self.status_lbl, 1)
        st.addWidget(self.progress, 2)
        st.addWidget(b_open)

        res = QWidget()
        rv = QVBoxLayout(res)
        rv.setContentsMargins(0, 0, 0, 0)
        rv.addWidget(self.table)
        rv.addLayout(st)

        top = QWidget()
        tv = QVBoxLayout(top)
        tv.setContentsMargins(0, 0, 0, 0)
        tv.addWidget(self.tabs, 1)
        tv.addWidget(self.preview_info)

        right = QSplitter(Qt.Vertical)
        right.addWidget(top)
        right.addWidget(res)
        right.setSizes([480, 300])

        split = QSplitter()
        split.addWidget(left)
        split.addWidget(right)
        split.setSizes([400, 880])
        self.setCentralWidget(split)
        self._load_settings()

    # ---------------- settings
    def _load_settings(self):
        s = self.s
        b = lambda k, d: str(s.value(k, d)).lower() in ("true", "1")
        self.beside.setChecked(b("beside", True))
        self.out_dir.setText(s.value("out_dir", str(Path.home() / "pdf2cad_out")))
        self.out_dir.setEnabled(not self.beside.isChecked())
        self.auto_scale.setChecked(b("auto_scale", True))
        self.scale.setValue(float(s.value("scale", 1.0)))
        self.scale.setEnabled(not self.auto_scale.isChecked())
        self.dpi.setValue(int(s.value("dpi", 300)))
        self.workers.setValue(int(s.value("workers", 0)))
        self.thick.setValue(float(s.value("thick", 0.5)))
        self.arcs.setChecked(b("arcs", True))
        self.raster.setChecked(b("raster", True))
        self.ocr.setChecked(b("ocr", True))
        self.rotate.setChecked(b("rotate", True))
        self.stamps.setChecked(b("stamps", True))
        self.open_after.setChecked(b("open_after", False))
        geo = s.value("geometry")
        if geo is not None:
            self.restoreGeometry(geo)

    def _save_settings(self):
        s = self.s
        for k, v in (("beside", self.beside.isChecked()), ("out_dir", self.out_dir.text()),
                     ("auto_scale", self.auto_scale.isChecked()),
                     ("scale", self.scale.value()), ("dpi", self.dpi.value()),
                     ("workers", self.workers.value()), ("thick", self.thick.value()),
                     ("arcs", self.arcs.isChecked()), ("raster", self.raster.isChecked()),
                     ("ocr", self.ocr.isChecked()), ("rotate", self.rotate.isChecked()),
                     ("stamps", self.stamps.isChecked()),
                     ("open_after", self.open_after.isChecked()),
                     ("geometry", self.saveGeometry())):
            s.setValue(k, v)

    def closeEvent(self, e):
        if self.thread and self.thread.isRunning():
            if QMessageBox.question(self, APP, "Đang chuyển đổi. Dừng và thoát?") != QMessageBox.Yes:
                e.ignore()
                return
            self.stop()
            self.thread.quit()
            self.thread.wait(5000)
        self._save_settings()
        super().closeEvent(e)

    # ---------------- file list
    def dragEnterEvent(self, e):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()

    def dropEvent(self, e):
        self.add_files([u.toLocalFile() for u in e.mimeData().urls() if u.isLocalFile()])

    def add_files(self, paths):
        existing = {self.list.item(i).data(Qt.UserRole) for i in range(self.list.count())}
        pdfs = _collect_pdfs(paths)
        added = 0
        for p in pdfs:
            if str(p) in existing:
                continue
            it = QListWidgetItem(p.name)
            it.setToolTip(str(p))
            it.setData(Qt.UserRole, str(p))
            self.list.addItem(it)
            added += 1
        if not pdfs:
            self.status_lbl.setText("Không tìm thấy file PDF trong mục vừa thả")
        else:
            self.status_lbl.setText(f"Đã thêm {added} file ({self.list.count()} trong danh sách)")
            self.list.setCurrentRow(self.list.count() - 1)

    def _files(self) -> list[Path]:
        return [Path(self.list.item(i).data(Qt.UserRole)) for i in range(self.list.count())]

    def _preview_selected(self, cur, _prev=None):
        if cur is None:
            return
        self.tabs.setCurrentWidget(self.pdf_view)
        self.preview_info.setText(self.pdf_view.show_pdf(Path(cur.data(Qt.UserRole))))

    def _pick_out(self):
        d = QFileDialog.getExistingDirectory(self, "Chọn thư mục lưu DXF", self.out_dir.text())
        if d:
            self.out_dir.setText(d)

    # ---------------- chạy
    def _options(self) -> ConvertOptions:
        return ConvertOptions(
            out_dir=self.out_dir.text().strip() or "out",
            pages=parse_pages(self.pages.text().strip() or None),
            scale=None if self.auto_scale.isChecked() else self.scale.value(),
            dpi=self.dpi.value(),
            workers=self.workers.value() or None,
            fit_arcs=self.arcs.isChecked(),
            raster=self.raster.isChecked(),
            thick_width=self.thick.value(),
            ocr="auto" if self.ocr.isChecked() else "off",
            auto_rotate=self.rotate.isChecked(),
            remove_stamps=self.stamps.isChecked(),
        )

    def start(self):
        files = self._files()
        if not files:
            QMessageBox.information(self, APP, "Hãy kéo thả ít nhất một file PDF vào.")
            return
        try:
            opt = self._options()
        except ValueError:
            QMessageBox.warning(self, APP, "Ô 'Trang' không hợp lệ. Ví dụ đúng: 1-3,5")
            return
        self._save_settings()
        self.table.setRowCount(0)
        self.progress.setRange(0, len(files) * 100)
        self.progress.setValue(0)
        self.n_ok = self.n_err = 0
        self.last_out = None if self.beside.isChecked() else opt.out_dir
        self._set_running(True)

        self.thread = QThread(self)
        self.worker = Worker(files, opt, self.beside.isChecked())
        self.worker.moveToThread(self.thread)
        self.thread.started.connect(self.worker.run)
        self.worker.file_started.connect(self._on_file)
        self.worker.page_done.connect(self._on_page)
        self.worker.finished.connect(self._on_finished)
        self.worker.finished.connect(self.thread.quit)
        self.thread.finished.connect(self.worker.deleteLater)
        self.thread.start()

    def stop(self):
        if self.worker:
            self.worker.stop()
            self.status_lbl.setText("Đang dừng… (chờ các trang đang chạy xong)")
            self.btn_stop.setEnabled(False)

    def _set_running(self, on: bool):
        self.btn_run.setEnabled(not on)
        self.btn_stop.setEnabled(on)
        self.drop.setEnabled(not on)

    def _on_file(self, i, n, name):
        self._file_idx = i
        self.progress.setValue(i * 100)
        self.status_lbl.setText(f"[{i + 1}/{n}] {name}")

    def _on_page(self, pdf: str, r: PageReport, done: int, total: int):
        self.progress.setValue(self._file_idx * 100 + int(done * 100 / max(total, 1)))
        row = self.table.rowCount()
        self.table.insertRow(row)
        counts = ", ".join(f"{k}:{v}" for k, v in sorted(r.counts.items()) if v)
        result = r.error or r.output or "(bỏ qua — trang trống hoặc scan bị tắt)"
        if r.rotated:
            result += f"  [xoay {r.rotated}°]"
        if r.warning:
            result += f"  ⚠ {r.warning}"
        vals = [Path(pdf).name, str(r.page), r.kind,
                f"1:{r.scale:g}" if r.kind != "error" else "-", r.scale_source,
                counts, f"{r.seconds:.2f}s", result]
        for c, v in enumerate(vals):
            it = QTableWidgetItem(v)
            it.setToolTip(v)
            if c == 0:
                it.setData(Qt.UserRole, r.output)
            if r.error:
                it.setForeground(QColor("#d93025"))
            elif r.warning:
                it.setForeground(QColor("#b06000"))
            self.table.setItem(row, c, it)
        if r.error:
            self.n_err += 1
        elif r.output:
            self.n_ok += 1
            if self.last_out is None or self.beside.isChecked():
                self.last_out = str(Path(r.output).parent)
            if self.n_ok == 1:   # tự hiện DXF đầu tiên
                self._show_dxf(r.output)
        self.table.scrollToBottom()

    def _on_finished(self, stopped: bool):
        self._set_running(False)
        self.thread = self.worker = None
        if not stopped:
            self.progress.setValue(self.progress.maximum())
        msg = f"{'Đã dừng' if stopped else 'Hoàn tất'}: {self.n_ok} DXF"
        if self.n_err:
            msg += f", {self.n_err} trang lỗi"
        self.status_lbl.setText(msg)
        if self.open_after.isChecked() and self.last_out and self.n_ok:
            _open_path(self.last_out)

    # ---------------- kết quả
    def _selected_output(self) -> str | None:
        r = self.table.currentRow()
        return self.table.item(r, 0).data(Qt.UserRole) if r >= 0 else None

    def _show_dxf(self, path: str):
        self.tabs.setCurrentWidget(self.dxf_view)
        self.preview_info.setText(self.dxf_view.show_dxf(path))

    def _view_selected_dxf(self):
        out = self._selected_output()
        if out:
            self._show_dxf(out)

    def _row_dblclick(self, row, _col):
        self._view_selected_dxf()

    def _open_selected(self, folder: bool):
        out = self._selected_output()
        if out:
            _open_path(Path(out).parent if folder else out)


def main(argv=None) -> int:
    multiprocessing.freeze_support()  # cần khi đóng gói bằng PyInstaller
    argv = sys.argv if argv is None else argv
    app = QApplication.instance() or QApplication(argv)
    app.setOrganizationName("SPC")
    app.setApplicationName(APP)
    app.setStyle("Fusion")
    w = MainWindow()
    w.show()
    if len(argv) > 1:  # pdf2cad-gui a.pdf b.pdf  /  kéo thả lên run_gui.bat
        w.add_files(argv[1:])
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
