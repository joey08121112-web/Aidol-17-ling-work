"""Product cue assets must be reproducible without any speech synthesis or private audio."""
from pathlib import Path
from scripts.generate_android_cues import generate


def test_bundled_cues_match_nonverbal_synthesis(tmp_path):
    generate(tmp_path)
    source = Path(__file__).resolve().parents[1] / 'android-app/app/src/main/res/raw'
    generated = {p.name for p in tmp_path.glob('cue_*.wav')}
    assert generated == {p.name for p in source.glob('cue_*.wav')}
    assert len(generated) == 24
    for name in generated:
        assert (tmp_path / name).read_bytes() == (source / name).read_bytes(), name
