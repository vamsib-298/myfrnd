"""Extract a looping stride from CMU 07_01 (cgspeed BVH conversion)."""
import sys
from pathlib import Path

import numpy as np
from scipy.signal import find_peaks
from scipy.spatial.transform import Rotation


def read_positions(file):
    hierarchy, motion = Path(file).read_text().split("MOTION")
    header, clock, rows = motion.strip().split("\n", 2)
    samples = np.loadtxt(rows.splitlines())
    dt = float(clock.split(":")[1])
    assert len(samples) == int(header.split(":")[1])
    stack, joints, offsets, parents, channels = [], [], [], [], []
    for line in hierarchy.splitlines():
        words = line.split()
        if not words:
            continue
        if words[0] in ("ROOT", "JOINT", "End"):
            parents.append(stack[-1] if stack else -1)
            stack.append(len(joints))
            joints.append(words[-1])
            channels.append([])
        elif words[0] == "OFFSET":
            offsets.append(np.array(words[1:], dtype=float))
        elif words[0] == "CHANNELS":
            channels[-1] = words[2:]
        elif words[0] == "}":
            stack.pop()
    positions, rotations = [], []
    cursor = 0
    for parent, offset, names in zip(parents, offsets, channels):
        xyz = np.tile(offset, (len(samples), 1))
        angles, order = [], ""
        for name in names:
            column = samples[:, cursor]
            cursor += 1
            if name.endswith("position"):
                xyz[:, "XYZ".index(name[0])] += column
            else:
                angles.append(column)
                order += name[0]
        turn = Rotation.from_euler(order, np.array(angles).T, degrees=True) if order else Rotation.identity()
        if parent >= 0:
            xyz = positions[parent] + rotations[parent].apply(xyz)
            turn = rotations[parent] * turn
        positions.append(xyz)
        rotations.append(turn)
    return dict(zip(joints, positions)), dt


def extract(file, output):
    points, dt = read_positions(file)
    root = points["Hips"]
    direction = root[-1] - root[0]
    heading = np.arctan2(direction[0], direction[2])
    align = Rotation.from_euler("y", -heading)
    points = {k: align.apply(v) for k, v in points.items()}
    hips = points["Hips"]
    leading = (points["LeftFoot"] - hips)[:, 2]
    peaks, _ = find_peaks(leading, distance=round(.75/dt), prominence=2)
    if len(peaks) < 2:
        raise ValueError("Capture does not contain a complete left-foot stride")
    start, end = peaks[:2]
    times = np.linspace(start, end, 49)
    forward = hips[end, 2] - hips[start, 2]
    leg = sum(np.linalg.norm(points[a][start]-points[b][start]) for a,b in
              [("LeftUpLeg","LeftLeg"),("LeftLeg","LeftFoot")])
    wanted = ["Hips","Neck","LeftArm","RightArm"]
    for side in ("Left","Right"):
        wanted.extend(side + n for n in ("UpLeg","Leg","Foot","ToeBase","ForeArm","Hand"))
    tracks = {}
    for name in wanted:
        trace = points[name] - hips
        if name == "Hips":
            trace = hips.copy()
            trace[:, 2] -= np.arange(len(hips)) * forward / (end-start)
            trace -= trace[start:end].mean(axis=0)
        samples = np.column_stack([np.interp(times, np.arange(len(trace)), axis) for axis in trace.T])
        # Distribute the difference between consecutive heel strikes over the whole stride.
        t = np.linspace(0, 1, len(times))[:, None]
        samples -= (3*t*t-2*t*t*t)*(samples[-1]-samples[0])
        tracks[name] = (samples/leg).astype(np.float32)
    np.savez_compressed(output, **tracks, duration=(end-start)*dt, stride=forward/leg)
    print("Heel strikes", peaks, "cycle seconds", (end-start)*dt, "stride / leg", forward/leg)


if __name__ == "__main__":
    extract(sys.argv[1], Path(__file__).with_name("cmu_walk_07_01.npz"))
