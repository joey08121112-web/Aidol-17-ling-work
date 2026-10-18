"""Generate deterministic, nonverbal product earcons using only Python's standard library."""
import argparse
import json
import math
from pathlib import Path
import re
import struct
import wave

ROOT = Path(__file__).resolve().parents[1]


def render(recipe, rate):
    frames = round(recipe['duration_ms'] * rate / 1000)
    duration = (frames - 1) / rate
    pcm = bytearray()
    for frame in range(frames):
        t = frame / rate
        # Each note has a rounded attack and a smooth tail; mode cues repeat it after a short gap.
        attack = math.sin(math.pi / 2 * min(1, t / .035)) ** 2
        release = math.sin(math.pi / 2 * min(1, (duration - t) / .140)) ** 2
        envelope = attack * release * math.exp(-3.5 * t / duration)
        phase = 2 * math.pi * (recipe['start_hz'] * t + (recipe['end_hz'] - recipe['start_hz']) * t * t / (2 * duration))
        tone = (math.sin(phase) + .12 * math.sin(2 * phase) * math.exp(-8 * t)) / 1.12
        pcm.extend(struct.pack('<h', round(32767 * recipe.get('gain', .13) * envelope * tone)))
    note = bytes(pcm)
    gap = bytes(round(recipe.get('gap_ms', 0) * rate / 1000) * 2)
    return gap.join([note] * recipe.get('repetitions', 1))


def generate(output):
    spec = json.loads((ROOT / 'scripts/android_cues.json').read_text(encoding='utf-8'))
    output.mkdir(parents=True, exist_ok=True)
    for name in spec['labels']:
        if not re.fullmatch(r'cue_[a-z]+', name):
            raise ValueError('Invalid product cue name')
        recipe = spec['tones'].get(name, spec['tones']['default'])
        with wave.open(str(output / (name + '.wav')), 'wb') as sound:
            sound.setparams((spec['channels'], spec['bits_per_sample'] // 8, spec['sample_rate'], 0, 'NONE', 'not compressed'))
            sound.writeframes(render(recipe, spec['sample_rate']))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'android-app/app/src/main/res/raw')
    generate(parser.parse_args().output)
