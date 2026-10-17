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


def test_mode_cues_have_two_clear_notes_and_more_level_than_general_cue():
    import array
    import math
    import sys
    import wave
    source = Path(__file__).resolve().parents[1] / 'android-app/app/src/main/res/raw'
    def samples(name):
        with wave.open(str(source / ('cue_' + name + '.wav')), 'rb') as stream:
            assert stream.getframerate() == 24000
            pcm = array.array('h', stream.readframes(stream.getnframes()))
        if sys.byteorder != 'little':
            pcm.byteswap()
        return pcm
    general = samples('ready')
    for name in ['life', 'work', 'commute']:
        pcm = samples(name)
        assert .7 <= len(pcm) / 24000 <= .85
        assert max(map(abs, pcm)) > 4 * max(map(abs, general))
        assert max(map(abs, pcm)) < 16000  # Substantial digital headroom remains.
        # Count audible runs in 10ms RMS windows, rather than individual waveform zero crossings.
        runs = 0
        previous = False
        for i in range(0, len(pcm), 240):
            block = pcm[i:i+240]
            audible = math.sqrt(sum(x*x for x in block) / len(block)) > 80
            if audible and not previous:
                runs += 1
            previous = audible
        assert runs == 2, name
