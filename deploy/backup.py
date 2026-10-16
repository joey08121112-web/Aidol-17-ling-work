"""Take consistent SQLite snapshots without copying live WAL files."""
import argparse
from contextlib import closing
from pathlib import Path
import sqlite3

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
parser.add_argument("destination", type=Path)
args = parser.parse_args()
args.destination.mkdir(parents=True, exist_ok=True)
count = 0
for source in args.source.rglob("*.sqlite3"):
    destination = args.destination / source.relative_to(args.source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(f"file:{source.as_posix()}?mode=ro", uri=True)) as reader:
        with closing(sqlite3.connect(destination)) as writer:
            reader.backup(writer)
    count += 1
print(f"Backed up {count} SQLite databases.")
