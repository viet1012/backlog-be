import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))


@pytest.fixture(scope="session")
def sample_pdf(tmp_path_factory):
    from make_sample_pdf import main
    return main(str(tmp_path_factory.mktemp("pdf") / "sample.pdf"))
