import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

FIXTURES = Path(__file__).parent / "fixtures"
TEST_ZIM = Path(__file__).resolve().parents[3] / "testdata" / "granger-test.zim"


def jl(name):
    return [json.loads(l) for l in (FIXTURES / name).read_text().splitlines() if l.strip()]
