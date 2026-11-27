"""Rebuild the shipped person using the scan rigging pipeline."""
from pathlib import Path
import runpy

if __name__ == "__main__":
    runpy.run_path(str(Path(__file__).parent / "companion-model" / "rig_scan.py"), run_name="__main__")
