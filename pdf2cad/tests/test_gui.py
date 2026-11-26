import os

import pytest

pytest.importorskip("PySide6")
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")


def test_gui_convert(sample_pdf, tmp_path, monkeypatch):
    from PySide6.QtCore import QCoreApplication, QSettings
    from PySide6.QtWidgets import QApplication

    from pdf2cad.gui import MainWindow

    app = QApplication.instance() or QApplication([])
    QCoreApplication.setOrganizationName("pdf2cad-test")
    QSettings().clear()
    w = MainWindow()
    w.add_files([str(sample_pdf)])
    assert w.list.count() == 1
    w.beside.setChecked(False)
    w.out_dir.setText(str(tmp_path))
    w.auto_scale.setChecked(False)
    w.scale.setValue(2)
    w.workers.setValue(1)
    w.start()
    while w.thread is not None:
        app.processEvents()
    assert w.n_ok == 2 and w.n_err == 0
    assert w.table.rowCount() == 2
    assert len(list(tmp_path.glob("*.dxf"))) == 2
    assert "đối tượng" in w.preview_info.text()
    w.close()
