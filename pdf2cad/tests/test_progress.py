import time

from pdf2cad import progress


def test_progress_file(tmp_path):
    f = tmp_path / "p" / "a_p001.json"
    progress.start(str(f))
    progress.stage(0.1, "OCR", 0.5)
    progress.sub(1, 2)
    time.sleep(0.3)
    progress.sub(2, 2)
    d = progress.read(f)
    assert d["stage"] == "OCR" and abs(d["p"] - 0.5) < 1e-6
    progress.creep(0, 1, 0.5)
    time.sleep(1.0)
    p = progress.read(f)["p"]
    assert 0.1 < p < 0.5                      # nhích trong bước, không vượt quá
    progress.stage(1.0, "Xong")
    assert progress.read(f)["p"] == 1.0
    progress.start(None)
