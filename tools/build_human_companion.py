import io
import json
import math
import struct
import numpy as np
from PIL import Image, ImageDraw


def smoothstep(edge0, edge1, x):
    t = np.clip((x - edge0) / (edge1 - edge0 + 1e-8), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def create_weave_texture(base_rgb, accent_rgb, size=128):
    img = Image.new("RGB", (size, size), base_rgb)
    draw = ImageDraw.Draw(img)
    for y in range(0, size, 2):
        alpha = 0.05 * math.sin(y * math.pi / 4.0)
        c = tuple(int(max(0, min(255, base_rgb[i] * (1.0 - alpha) + accent_rgb[i] * alpha))) for i in range(3))
        draw.line([(0, y), (size, y)], fill=c)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=92, progressive=False)
    return buf.getvalue()


class MeshBuilder:
    def __init__(self, bone_positions):
        self.bones = bone_positions

    @staticmethod
    def compute_smooth_normals(verts, indices):
        normals = np.zeros_like(verts, dtype=np.float32)
        tris = indices.reshape(-1, 3)
        v0 = verts[tris[:, 0]]
        v1 = verts[tris[:, 1]]
        v2 = verts[tris[:, 2]]
        fn = np.cross(v1 - v0, v2 - v0)
        np.add.at(normals, tris[:, 0], fn)
        np.add.at(normals, tris[:, 1], fn)
        np.add.at(normals, tris[:, 2], fn)
        lens = np.linalg.norm(normals, axis=1, keepdims=True)
        return np.where(lens > 1e-8, normals / np.maximum(lens, 1e-8), np.array([0, 1, 0], dtype=np.float32))

    @staticmethod
    def build_uv_sphere(center, radii, stacks=24, slices=32):
        cx, cy, cz = center
        rx, ry, rz = radii
        verts, uvs, indices = [], [], []
        for i in range(stacks + 1):
            v = i / stacks
            phi = v * math.pi
            y = math.cos(phi)
            sin_phi = math.sin(phi)
            for j in range(slices + 1):
                u = j / slices
                theta = u * 2.0 * math.pi
                x = sin_phi * math.sin(theta)
                z = sin_phi * math.cos(theta)
                verts.append([cx + rx * x, cy + ry * y, cz + rz * z])
                uvs.append([u, v])
        stride = slices + 1
        for i in range(stacks):
            for j in range(slices):
                a = i * stride + j
                b = a + stride
                c = b + 1
                d = a + 1
                indices.extend([a, b, d, b, c, d])
        v_arr = np.asarray(verts, dtype=np.float32)
        idx_arr = np.asarray(indices, dtype=np.uint32)
        n_arr = MeshBuilder.compute_smooth_normals(v_arr, idx_arr)
        return v_arr, n_arr, np.asarray(uvs, dtype=np.float32), idx_arr

    @staticmethod
    def build_curved_tube(points, radii, slices=12, cap=True):
        """Builds a smooth tube along an arbitrary 3D centerline (for smile, eyebrows, drawstrings)."""
        pts = np.asarray(points, dtype=np.float32)
        n_pts = len(pts)
        verts, uvs, indices = [], [], []
        for i in range(n_pts):
            p = pts[i]
            if i == 0:
                tangent = pts[1] - pts[0]
            elif i == n_pts - 1:
                tangent = pts[-1] - pts[-2]
            else:
                tangent = pts[i + 1] - pts[i - 1]
            tangent = tangent / max(1e-8, float(np.linalg.norm(tangent)))
            up = np.array([0.0, 0.0, 1.0], dtype=np.float32)
            if abs(float(np.dot(tangent, up))) > 0.92:
                up = np.array([0.0, 1.0, 0.0], dtype=np.float32)
            side = np.cross(up, tangent)
            side /= max(1e-8, float(np.linalg.norm(side)))
            binorm = np.cross(tangent, side)
            r = radii[i]
            v_coord = i / max(1, n_pts - 1)
            for j in range(slices + 1):
                u_coord = j / slices
                ang = u_coord * 2.0 * math.pi
                offset = r * (math.cos(ang) * side + math.sin(ang) * binorm)
                verts.append((p + offset).tolist())
                uvs.append([u_coord, v_coord])
        stride = slices + 1
        for i in range(n_pts - 1):
            for j in range(slices):
                a = i * stride + j
                b = a + stride
                c = b + 1
                d = a + 1
                indices.extend([a, d, b, b, d, c])
        if cap:
            c0 = len(verts)
            verts.append(pts[0].tolist())
            uvs.append([0.5, 0.0])
            for j in range(slices):
                indices.extend([c0, j, j + 1])
            c1 = len(verts)
            verts.append(pts[-1].tolist())
            uvs.append([0.5, 1.0])
            base = (n_pts - 1) * stride
            for j in range(slices):
                indices.extend([c1, base + j + 1, base + j])
        v_arr = np.asarray(verts, dtype=np.float32)
        idx_arr = np.asarray(indices, dtype=np.uint32)
        n_arr = MeshBuilder.compute_smooth_normals(v_arr, idx_arr)
        return v_arr, n_arr, np.asarray(uvs, dtype=np.float32), idx_arr

    @staticmethod
    def build_profile_tube(rings, slices=30, cap_bottom=True, cap_top=True):
        verts, uvs, indices = [], [], []
        n_rings = len(rings)
        for ri, ring in enumerate(rings):
            cx, cy, cz, rx, rz = ring[:5]
            v_coord = ri / max(1, n_rings - 1)
            for si in range(slices + 1):
                u_coord = si / slices
                ang = u_coord * 2.0 * math.pi
                verts.append([cx + rx * math.sin(ang), cy, cz + rz * math.cos(ang)])
                uvs.append([u_coord, v_coord])
        stride = slices + 1
        for ri in range(n_rings - 1):
            for si in range(slices):
                a = ri * stride + si
                b = a + stride
                c = b + 1
                d = a + 1
                indices.extend([a, d, b, b, d, c])
        if cap_bottom:
            c_idx = len(verts)
            r0 = rings[0]
            verts.append([r0[0], r0[1], r0[2]])
            uvs.append([0.5, 0.0])
            for si in range(slices):
                indices.extend([c_idx, si, si + 1])
        if cap_top:
            c_idx = len(verts)
            rt = rings[-1]
            verts.append([rt[0], rt[1], rt[2]])
            uvs.append([0.5, 1.0])
            base = (n_rings - 1) * stride
            for si in range(slices):
                indices.extend([c_idx, base + si + 1, base + si])
        v_arr = np.asarray(verts, dtype=np.float32)
        idx_arr = np.asarray(indices, dtype=np.uint32)
        n_arr = MeshBuilder.compute_smooth_normals(v_arr, idx_arr)
        return v_arr, n_arr, np.asarray(uvs, dtype=np.float32), idx_arr

    @staticmethod
    def build_arm_tube(stations, slices=24, cap_start=True, cap_end=True):
        verts, uvs, indices = [], [], []
        n_st = len(stations)
        sign_x = 1.0 if stations[-1][0] >= stations[0][0] else -1.0
        for si_ring, (x, y, z, ry, rz) in enumerate(stations):
            v_coord = si_ring / max(1, n_st - 1)
            for j in range(slices + 1):
                u_coord = j / slices
                ang = u_coord * 2.0 * math.pi
                verts.append([x, y + ry * math.cos(ang), z + rz * math.sin(ang)])
                uvs.append([u_coord, v_coord])
        stride = slices + 1
        for i in range(n_st - 1):
            for j in range(slices):
                a = i * stride + j
                b = a + stride
                c = b + 1
                d = a + 1
                if sign_x > 0:
                    indices.extend([a, d, b, b, d, c])
                else:
                    indices.extend([a, b, d, b, c, d])
        if cap_start:
            c_idx = len(verts)
            s0 = stations[0]
            verts.append([s0[0], s0[1], s0[2]])
            uvs.append([0.5, 0.0])
            for j in range(slices):
                if sign_x > 0:
                    indices.extend([c_idx, j, j + 1])
                else:
                    indices.extend([c_idx, j + 1, j])
        if cap_end:
            c_idx = len(verts)
            sn = stations[-1]
            verts.append([sn[0], sn[1], sn[2]])
            uvs.append([0.5, 1.0])
            base = (n_st - 1) * stride
            for j in range(slices):
                if sign_x > 0:
                    indices.extend([c_idx, base + j + 1, base + j])
                else:
                    indices.extend([c_idx, base + j, base + j + 1])
        v_arr = np.asarray(verts, dtype=np.float32)
        idx_arr = np.asarray(indices, dtype=np.uint32)
        n_arr = MeshBuilder.compute_smooth_normals(v_arr, idx_arr)
        return v_arr, n_arr, np.asarray(uvs, dtype=np.float32), idx_arr

    @staticmethod
    def merge_parts(parts):
        all_v, all_n, all_uv, all_i, all_j, all_w = [], [], [], [], [], []
        offset = 0
        for v, n, uv, idx, j, w in parts:
            all_v.append(v)
            all_n.append(n)
            all_uv.append(uv)
            all_i.append(idx + offset)
            all_j.append(j)
            all_w.append(w)
            offset += len(v)
        return (
            np.vstack(all_v),
            np.vstack(all_n),
            np.vstack(all_uv),
            np.concatenate(all_i),
            np.vstack(all_j),
            np.vstack(all_w),
        )

    @staticmethod
    def rigid_weights(n_verts, joint_idx):
        j = np.zeros((n_verts, 4), dtype=np.uint16)
        w = np.zeros((n_verts, 4), dtype=np.float32)
        j[:, 0] = joint_idx
        w[:, 0] = 1.0
        return j, w

    @staticmethod
    def chain_weights(coords, joint_ids, joint_anchors):
        n = len(coords)
        j = np.zeros((n, 4), dtype=np.uint16)
        w = np.zeros((n, 4), dtype=np.float32)
        pts = np.asarray(joint_anchors, dtype=np.float32)
        asc = pts[-1] >= pts[0]
        if not asc:
            pts = pts[::-1]
            j_ids = list(reversed(joint_ids))
        else:
            j_ids = list(joint_ids)

        for idx_v, c in enumerate(coords):
            if c <= pts[0]:
                j[idx_v, 0] = j_ids[0]
                w[idx_v, 0] = 1.0
            elif c >= pts[-1]:
                j[idx_v, 0] = j_ids[-1]
                w[idx_v, 0] = 1.0
            else:
                k = int(np.searchsorted(pts, c, side="right")) - 1
                k = max(0, min(len(pts) - 2, k))
                t = float((c - pts[k]) / (pts[k + 1] - pts[k] + 1e-8))
                t_smooth = t * t * (3.0 - 2.0 * t)
                j[idx_v, 0] = j_ids[k]
                j[idx_v, 1] = j_ids[k + 1]
                w[idx_v, 0] = 1.0 - t_smooth
                w[idx_v, 1] = t_smooth
        return j, w


def build_human_character(input_glb, output_glb):
    with open(input_glb, "rb") as f:
        f.read(12)
        jl, _ = struct.unpack("<II", f.read(8))
        gltf = json.loads(f.read(jl).decode("utf-8"))
        bl, _ = struct.unpack("<II", f.read(8))
        orig_bin = bytearray(f.read(bl))

    skin = gltf["skins"][0]
    ibm_acc = gltf["accessors"][skin["inverseBindMatrices"]]
    bv = gltf["bufferViews"][ibm_acc["bufferView"]]
    ibm_off = bv.get("byteOffset", 0) + ibm_acc.get("byteOffset", 0)

    bone_pos = {}
    for idx, _ in enumerate(skin["joints"]):
        m = np.array(struct.unpack_from("<16f", orig_bin, ibm_off + idx * 64)).reshape((4, 4)).T
        bone_pos[idx] = np.linalg.inv(m)[:3, 3]

    mb = MeshBuilder(bone_pos)

    # =========================================================================
    # 1. SKIN: Head, Cute Nose, Ears, Neck, Sculpted Hands
    # =========================================================================
    skin_parts = []

    hv, hn, huv, hi = mb.build_uv_sphere((0.0, 1.666, 0.015), (0.104, 0.122, 0.112), stacks=28, slices=36)
    for k in range(len(hv)):
        vx, vy, vz = hv[k]
        if vy < 1.65:
            t = min(1.0, (1.65 - vy) / 0.11)
            hv[k, 0] *= (1.0 - 0.22 * t)
            if vz > 0.01:
                hv[k, 2] -= 0.010 * t * t
        if 1.60 < vy < 1.66 and vz > 0.02 and abs(vx) > 0.035:
            hv[k, 2] += 0.008 * math.sin((vy - 1.60) / 0.06 * math.pi)
    hn = mb.compute_smooth_normals(hv, hi)
    hj, hw = mb.rigid_weights(len(hv), 5)
    skin_parts.append((hv, hn, huv, hi, hj, hw))

    nv, nn, nuv, ni = mb.build_uv_sphere((0.0, 1.648, 0.125), (0.014, 0.014, 0.016), stacks=14, slices=18)
    nj, nw = mb.rigid_weights(len(nv), 5)
    skin_parts.append((nv, nn, nuv, ni, nj, nw))

    for sx in (1.0, -1.0):
        ev, en, euv, ei = mb.build_uv_sphere((sx * 0.102, 1.654, 0.004), (0.018, 0.028, 0.020), stacks=14, slices=18)
        ej, ew = mb.rigid_weights(len(ev), 5)
        skin_parts.append((ev, en, euv, ei, ej, ew))

    neck_rings = [
        (0.0, 1.465, -0.022, 0.056, 0.056),
        (0.0, 1.505, -0.020, 0.050, 0.052),
        (0.0, 1.548, -0.012, 0.048, 0.050),
        (0.0, 1.585, -0.005, 0.052, 0.054),
    ]
    nev, nen, neuv, nei = mb.build_profile_tube(neck_rings, slices=24)
    nej, new = mb.chain_weights(nev[:, 1], [3, 4, 5], [1.44, 1.515, 1.58])
    skin_parts.append((nev, nen, neuv, nei, nej, new))

    for sx, hand_j in [(1.0, 12), (-1.0, 36)]:
        stations = [
            (sx * 0.692, 1.438, -0.050, 0.032, 0.026),
            (sx * 0.725, 1.438, -0.050, 0.036, 0.030),
            (sx * 0.775, 1.436, -0.048, 0.038, 0.026),
            (sx * 0.830, 1.434, -0.048, 0.033, 0.020),
            (sx * 0.866, 1.433, -0.048, 0.018, 0.013),
        ]
        pv, pn, puv, pi = mb.build_arm_tube(stations, slices=18)
        pj, pw = mb.rigid_weights(len(pv), hand_j)
        skin_parts.append((pv, pn, puv, pi, pj, pw))
        tv, tn, tuv, ti = mb.build_uv_sphere((sx * 0.766, 1.412, -0.012), (0.031, 0.015, 0.017), stacks=12, slices=16)
        tj, tw = mb.rigid_weights(len(tv), hand_j)
        skin_parts.append((tv, tn, tuv, ti, tj, tw))

    # =========================================================================
    # 2. FACE: Friendly Eyes, Catchlights, Eyebrow Curves, Smile Ribbon
    # =========================================================================
    eye_white_parts = []
    eye_dark_parts = []
    smile_parts = []

    for sx in (1.0, -1.0):
        ex = sx * 0.035
        ey = 1.668
        ez = 0.112
        sv, sn, suv, si = mb.build_uv_sphere((ex, ey, ez), (0.0175, 0.0195, 0.011), stacks=14, slices=18)
        sj, sw = mb.rigid_weights(len(sv), 5)
        eye_white_parts.append((sv, sn, suv, si, sj, sw))

        iv, in_, iuv, ii = mb.build_uv_sphere((ex, ey, ez + 0.0075), (0.012, 0.014, 0.008), stacks=14, slices=18)
        ij, iw = mb.rigid_weights(len(iv), 5)
        eye_dark_parts.append((iv, in_, iuv, ii, ij, iw))

        cv, cn, cuv, ci = mb.build_uv_sphere((ex + 0.004, ey + 0.005, ez + 0.014), (0.004, 0.004, 0.003), stacks=8, slices=10)
        cj, cw = mb.rigid_weights(len(cv), 5)
        eye_white_parts.append((cv, cn, cuv, ci, cj, cw))

        # Smooth curved eyebrow tube
        brow_pts = []
        brow_rad = []
        for k in range(9):
            t = (k / 8.0) - 0.5
            bx = ex + t * 0.036
            by = 1.698 + 0.005 * (1.0 - 4.0 * t * t)
            bz = 0.114 - 0.010 * (t * t * 2.5)
            brow_pts.append([bx, by, bz])
            brow_rad.append(0.0048 * (1.0 - 0.55 * abs(t)))
        bv_, bn_, buv_, bi_ = mb.build_curved_tube(brow_pts, brow_rad, slices=10)
        bj, bw = mb.rigid_weights(len(bv_), 5)
        eye_dark_parts.append((bv_, bn_, buv_, bi_, bj, bw))

    # Smooth curved warm smile ribbon (zero separated spheres!)
    smile_pts = []
    smile_rad = []
    for k in range(13):
        t = (k / 12.0) - 0.5
        mx = t * 0.052
        my = 1.610 + 0.010 * (t * t * 4.0)
        mz = 0.118 - 0.013 * (t * t * 3.0)
        smile_pts.append([mx, my, mz])
        smile_rad.append(0.0045 * (1.0 - 0.6 * abs(t)))
    mv, mn, muv, mi_ = mb.build_curved_tube(smile_pts, smile_rad, slices=10)
    mj, mw = mb.rigid_weights(len(mv), 5)
    smile_parts.append((mv, mn, muv, mi_, mj, mw))

    # =========================================================================
    # 3. HAIR: Conformal Back/Top Hair Shell + Wavy Top Locks (ZERO chin/jaw rim!)
    # =========================================================================
    hair_parts = []
    hx, hy, hz = 0.0, 1.670, 0.012
    hrx, hry, hrz = 0.113, 0.128, 0.120
    shell_v, shell_n, shell_uv, shell_i = mb.build_uv_sphere((hx, hy, hz), (hrx, hry, hrz), stacks=36, slices=44)
    for k in range(len(shell_v)):
        vx, vy, vz = shell_v[k]
        # Hair only exists above nape/ear line (y >= 1.65 in back, y >= 1.722 across forehead)!
        front_blend = float(smoothstep(-0.045, 0.020, vz - hz))
        cutoff_y = 1.648 + 0.074 * front_blend + 0.008 * math.sin(vx * 22.0) * front_blend
        tuck = 1.0 - float(smoothstep(cutoff_y - 0.016, cutoff_y + 0.012, vy))
        if tuck > 0.0:
            d = shell_v[k] - np.array([hx, hy, hz], dtype=np.float32)
            shell_v[k] = np.array([hx, hy, hz], dtype=np.float32) + d * (1.0 - 0.35 * tuck)
    shell_n = mb.compute_smooth_normals(shell_v, shell_i)
    shell_j, shell_w = mb.rigid_weights(len(shell_v), 5)
    hair_parts.append((shell_v, shell_n, shell_uv, shell_i, shell_j, shell_w))

    # Stylized wavy top volume locks (flowing cleanly over hairline without forehead gaps)
    wavy_locks = [
        ((-0.026, 1.768, 0.042), (0.068, 0.040, 0.072)),
        ((0.036, 1.760, 0.050), (0.060, 0.036, 0.064)),
        ((0.000, 1.786, 0.010), (0.086, 0.046, 0.084)),
        ((-0.064, 1.748, 0.018), (0.044, 0.036, 0.054)),
        ((0.064, 1.745, 0.016), (0.044, 0.036, 0.052)),
    ]
    for ctr, rad in wavy_locks:
        lv, ln, luv, li = mb.build_uv_sphere(ctr, rad, stacks=16, slices=22)
        lj, lw = mb.rigid_weights(len(lv), 5)
        hair_parts.append((lv, ln, luv, li, lj, lw))

    # =========================================================================
    # 4. MINT-GREEN HOODIE (Smooth Torso, Seamless Shoulders, Collar, Drawstrings, Sleeves)
    # =========================================================================
    hoodie_parts = []

    torso_rings = [
        (0.0, 0.955, 0.015, 0.140, 0.095),
        (0.0, 1.005, 0.016, 0.145, 0.100),
        (0.0, 1.090, 0.014, 0.132, 0.094),
        (0.0, 1.200, 0.008, 0.138, 0.098),
        (0.0, 1.320, -0.004, 0.156, 0.105),
        (0.0, 1.430, -0.024, 0.160, 0.096),
        (0.0, 1.472, -0.026, 0.108, 0.082),
    ]
    tv, tn, tuv, ti = mb.build_profile_tube(torso_rings, slices=32, cap_bottom=True, cap_top=True)
    tj, tw = mb.chain_weights(tv[:, 1], [0, 1, 2, 3, 4], [1.02, 1.14, 1.24, 1.36, 1.49])
    for k in range(len(tv)):
        vx, vy, vz = tv[k]
        if vy > 1.35 and abs(vx) > 0.09:
            arm_w = float(smoothstep(0.09, 0.165, abs(vx)) * smoothstep(1.34, 1.43, vy)) * 0.45
            sh_j = 9 if vx > 0 else 33
            tw[k, 0] *= (1.0 - arm_w)
            tw[k, 1] *= (1.0 - arm_w)
            tj[k, 2] = sh_j
            tw[k, 2] = arm_w
    hoodie_parts.append((tv, tn, tuv, ti, tj, tw))

    # Smooth Hoodie Collar Ring (open top around neck, zero spiky top!)
    collar_rings = [
        (0.0, 1.448, -0.024, 0.088, 0.084),
        (0.0, 1.482, -0.025, 0.082, 0.080),
        (0.0, 1.495, -0.026, 0.068, 0.068),
    ]
    cv, cn, cuv, ci = mb.build_profile_tube(collar_rings, slices=28, cap_bottom=True, cap_top=False)
    cj, cw = mb.chain_weights(cv[:, 1], [3, 4], [1.43, 1.50])
    hoodie_parts.append((cv, cn, cuv, ci, cj, cw))

    # Resting back hood drape
    hv_back, hn_back, huv_back, hi_back = mb.build_uv_sphere((0.0, 1.466, -0.094), (0.092, 0.066, 0.056), stacks=16, slices=22)
    hj_back, hw_back = mb.rigid_weights(len(hv_back), 3)
    hoodie_parts.append((hv_back, hn_back, huv_back, hi_back, hj_back, hw_back))

    # Smooth Front Kangaroo Pocket
    pk_v, pk_n, pk_uv, pk_i = mb.build_uv_sphere((0.0, 1.072, 0.088), (0.098, 0.064, 0.026), stacks=14, slices=22)
    pk_j, pk_w = mb.chain_weights(pk_v[:, 1], [0, 1], [1.02, 1.14])
    hoodie_parts.append((pk_v, pk_n, pk_uv, pk_i, pk_j, pk_w))

    # Hoodie Drawstrings
    for sx in (1.0, -1.0):
        ds_pts = [
            [sx * 0.026, 1.462, 0.066],
            [sx * 0.030, 1.395, 0.095],
            [sx * 0.032, 1.320, 0.098],
        ]
        dv, dn, duv, di = mb.build_curved_tube(ds_pts, [0.006, 0.006, 0.007], slices=10)
        dj, dw = mb.rigid_weights(len(dv), 3)
        hoodie_parts.append((dv, dn, duv, di, dj, dw))

    # Hoodie Sleeves (Seamless shoulder caps + tapered sleeves)
    for sx, sh_j, arm_j, fore_j, hand_j in [(1.0, 9, 10, 11, 12), (-1.0, 33, 34, 35, 36)]:
        stations = [
            (sx * 0.095, 1.425, -0.032, 0.072, 0.072),
            (sx * 0.160, 1.436, -0.046, 0.062, 0.062),
            (sx * 0.290, 1.438, -0.050, 0.052, 0.052),
            (sx * 0.430, 1.438, -0.050, 0.047, 0.047),
            (sx * 0.565, 1.438, -0.050, 0.044, 0.044),
            (sx * 0.675, 1.438, -0.050, 0.042, 0.042),
            (sx * 0.702, 1.438, -0.050, 0.038, 0.036),
        ]
        sv, sn, suv, si = mb.build_arm_tube(stations, slices=24)
        sj, sw = mb.chain_weights(
            sv[:, 0],
            [3, sh_j, arm_j, fore_j, hand_j],
            [sx * 0.08, sx * 0.14, sx * 0.26, sx * 0.45, sx * 0.70],
        )
        hoodie_parts.append((sv, sn, suv, si, sj, sw))

    # =========================================================================
    # 5. PANTS / JOGGERS
    # =========================================================================
    pants_parts = []
    pelvis_rings = [
        (0.0, 0.905, 0.008, 0.125, 0.088),
        (0.0, 0.960, 0.012, 0.134, 0.094),
        (0.0, 1.020, 0.015, 0.132, 0.092),
    ]
    pv, pn, puv, pi = mb.build_profile_tube(pelvis_rings, slices=28)
    pj, pw = mb.rigid_weights(len(pv), 0)
    pants_parts.append((pv, pn, puv, pi, pj, pw))

    for sx, upleg_j, leg_j, foot_j in [(1.0, 57, 58, 59), (-1.0, 62, 63, 64)]:
        lx = sx * 0.082
        leg_rings = [
            (lx, 0.098, -0.018, 0.044, 0.046),
            (lx, 0.240, -0.004, 0.051, 0.053),
            (lx, 0.400, 0.004, 0.057, 0.058),
            (lx, 0.530, 0.008, 0.056, 0.058),
            (lx, 0.730, 0.007, 0.068, 0.070),
            (lx, 0.920, 0.006, 0.075, 0.078),
            (lx * 0.75, 0.985, 0.008, 0.076, 0.080),
        ]
        lv, ln, luv, li = mb.build_profile_tube(leg_rings, slices=24)
        lj, lw = mb.chain_weights(lv[:, 1], [foot_j, leg_j, upleg_j, 0], [0.10, 0.52, 0.91, 1.01])
        pants_parts.append((lv, ln, luv, li, lj, lw))

    # =========================================================================
    # 6. SNEAKERS
    # =========================================================================
    shoe_upper_parts = []
    shoe_sole_parts = []

    for sx, leg_j, foot_j, toe_j in [(1.0, 58, 59, 60), (-1.0, 63, 64, 65)]:
        fx = sx * 0.082
        sole_v, sole_n, sole_uv, sole_i = mb.build_uv_sphere((fx, 0.014, 0.052), (0.052, 0.018, 0.125), stacks=14, slices=22)
        for k in range(len(sole_v)):
            if sole_v[k, 1] < 0.008:
                sole_v[k, 1] = -0.002
        sole_n = mb.compute_smooth_normals(sole_v, sole_i)
        sole_j, sole_w = mb.chain_weights(sole_v[:, 2], [foot_j, toe_j], [0.02, 0.11])
        shoe_sole_parts.append((sole_v, sole_n, sole_uv, sole_i, sole_j, sole_w))

        up_v, up_n, up_uv, up_i = mb.build_uv_sphere((fx, 0.048, 0.046), (0.049, 0.040, 0.116), stacks=16, slices=24)
        for k in range(len(up_v)):
            if up_v[k, 1] < 0.016:
                up_v[k, 1] = 0.016
        up_n = mb.compute_smooth_normals(up_v, up_i)
        up_j, up_w = mb.chain_weights(up_v[:, 2], [foot_j, toe_j], [0.02, 0.11])
        shoe_upper_parts.append((up_v, up_n, up_uv, up_i, up_j, up_w))

        ank_rings = [
            (fx, 0.062, -0.015, 0.047, 0.056),
            (fx, 0.106, -0.018, 0.045, 0.050),
        ]
        av, an, auv, ai = mb.build_profile_tube(ank_rings, slices=20)
        aj, aw = mb.chain_weights(av[:, 1], [foot_j, leg_j], [0.07, 0.12])
        shoe_upper_parts.append((av, an, auv, ai, aj, aw))

    merged_groups = [
        (0, mb.merge_parts(skin_parts)),
        (1, mb.merge_parts(hair_parts)),
        (2, mb.merge_parts(hoodie_parts)),
        (3, mb.merge_parts(pants_parts)),
        (4, mb.merge_parts(shoe_upper_parts)),
        (5, mb.merge_parts(shoe_sole_parts)),
        (6, mb.merge_parts(eye_dark_parts)),
        (7, mb.merge_parts(smile_parts)),
    ]
    white_v, white_n, white_uv, white_i, white_j, white_w = mb.merge_parts(eye_white_parts)
    s5 = merged_groups[5][1]
    merged_groups[5] = (5, mb.merge_parts([s5, (white_v, white_n, white_uv, white_i, white_j, white_w)]))

    bin_data = bytearray(orig_bin)
    while len(bin_data) % 4 != 0:
        bin_data.append(0)

    def append_bytes(raw_bytes, target=None):
        while len(bin_data) % 4 != 0:
            bin_data.append(0)
        offset = len(bin_data)
        bin_data.extend(raw_bytes)
        bv_obj = {"buffer": 0, "byteOffset": offset, "byteLength": len(raw_bytes)}
        if target is not None:
            bv_obj["target"] = target
        bv_idx = len(gltf["bufferViews"])
        gltf["bufferViews"].append(bv_obj)
        return bv_idx

    tex_hoodie_bytes = create_weave_texture((122, 224, 178), (96, 200, 154), size=128)
    tex_skin_bytes = create_weave_texture((248, 202, 172), (240, 190, 158), size=64)

    bv_tex_hoodie = append_bytes(tex_hoodie_bytes)
    bv_tex_skin = append_bytes(tex_skin_bytes)

    gltf["images"] = [
        {"bufferView": bv_tex_hoodie, "mimeType": "image/jpeg", "name": "hoodie_weave"},
        {"bufferView": bv_tex_skin, "mimeType": "image/jpeg", "name": "skin_tone"},
    ]
    gltf["textures"] = [{"source": 0}, {"source": 1}]

    gltf["materials"] = [
        {
            "name": "HumanSkin",
            "doubleSided": True,
            "pbrMetallicRoughness": {
                "baseColorFactor": [1.0, 1.0, 1.0, 1.0],
                "baseColorTexture": {"index": 1},
                "metallicFactor": 0.0,
                "roughnessFactor": 0.62,
            },
        },
        {
            "name": "HairBrown",
            "doubleSided": True,
            "pbrMetallicRoughness": {
                "baseColorFactor": [0.35, 0.21, 0.12, 1.0],
                "metallicFactor": 0.04,
                "roughnessFactor": 0.48,
            },
        },
        {
            "name": "MintHoodie",
            "doubleSided": True,
            "pbrMetallicRoughness": {
                "baseColorFactor": [1.0, 1.0, 1.0, 1.0],
                "baseColorTexture": {"index": 0},
                "metallicFactor": 0.0,
                "roughnessFactor": 0.78,
            },
        },
        {
            "name": "SlateJoggers",
            "doubleSided": True,
            "pbrMetallicRoughness": {
                "baseColorFactor": [0.16, 0.22, 0.30, 1.0],
                "metallicFactor": 0.0,
                "roughnessFactor": 0.76,
            },
        },
        {
            "name": "MintSneaker",
            "doubleSided": True,
            "pbrMetallicRoughness": {
                "baseColorFactor": [0.26, 0.72, 0.54, 1.0],
                "metallicFactor": 0.05,
                "roughnessFactor": 0.45,
            },
        },
        {
            "name": "CrispWhite",
            "doubleSided": True,
            "pbrMetallicRoughness": {
                "baseColorFactor": [0.97, 0.98, 0.99, 1.0],
                "metallicFactor": 0.0,
                "roughnessFactor": 0.20,
            },
        },
        {
            "name": "EyeDarkBrown",
            "doubleSided": True,
            "pbrMetallicRoughness": {
                "baseColorFactor": [0.15, 0.08, 0.05, 1.0],
                "metallicFactor": 0.05,
                "roughnessFactor": 0.12,
            },
        },
        {
            "name": "WarmSmile",
            "doubleSided": True,
            "pbrMetallicRoughness": {
                "baseColorFactor": [0.72, 0.32, 0.30, 1.0],
                "metallicFactor": 0.0,
                "roughnessFactor": 0.45,
            },
        },
    ]

    def add_accessor(bv_idx, comp_type, count, acc_type, min_v=None, max_v=None):
        acc = {
            "bufferView": bv_idx,
            "byteOffset": 0,
            "componentType": comp_type,
            "count": int(count),
            "type": acc_type,
        }
        if min_v is not None:
            acc["min"] = [float(x) for x in min_v]
        if max_v is not None:
            acc["max"] = [float(x) for x in max_v]
        idx = len(gltf["accessors"])
        gltf["accessors"].append(acc)
        return idx

    new_primitives = []
    for mat_idx, (v, n, uv, indices, joints, weights) in merged_groups:
        w_sum = np.sum(weights, axis=1, keepdims=True)
        weights = np.where(w_sum > 1e-6, weights / np.maximum(w_sum, 1e-6), np.array([1, 0, 0, 0], dtype=np.float32))

        bv_pos = append_bytes(v.astype("<f4").tobytes(), target=34962)
        bv_norm = append_bytes(n.astype("<f4").tobytes(), target=34962)
        bv_uv = append_bytes(uv.astype("<f4").tobytes(), target=34962)
        bv_jnt = append_bytes(joints.astype("<u2").tobytes(), target=34962)
        bv_wgt = append_bytes(weights.astype("<f4").tobytes(), target=34962)
        bv_idx = append_bytes(indices.astype("<u4").tobytes(), target=34963)

        acc_pos = add_accessor(bv_pos, 5126, len(v), "VEC3", v.min(axis=0), v.max(axis=0))
        acc_norm = add_accessor(bv_norm, 5126, len(n), "VEC3")
        acc_uv = add_accessor(bv_uv, 5126, len(uv), "VEC2")
        acc_jnt = add_accessor(bv_jnt, 5123, len(joints), "VEC4")
        acc_wgt = add_accessor(bv_wgt, 5126, len(weights), "VEC4")
        acc_idx = add_accessor(bv_idx, 5125, len(indices), "SCALAR")

        new_primitives.append({
            "attributes": {
                "POSITION": acc_pos,
                "NORMAL": acc_norm,
                "TEXCOORD_0": acc_uv,
                "JOINTS_0": acc_jnt,
                "WEIGHTS_0": acc_wgt,
            },
            "indices": acc_idx,
            "material": mat_idx,
        })

    gltf["meshes"] = [{"name": "HumanCompanion", "primitives": new_primitives}]
    gltf["nodes"][67] = {"mesh": 0, "name": "HumanCompanion", "skin": 0}
    gltf["nodes"][68] = {"name": "UnusedPlaceholder"}
    gltf["nodes"][69]["children"] = [67, 66]

    while len(bin_data) % 4 != 0:
        bin_data.append(0)
    gltf["buffers"][0]["byteLength"] = len(bin_data)

    new_json = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    while len(new_json) % 4 != 0:
        new_json += b" "

    total_len = 12 + 8 + len(new_json) + 8 + len(bin_data)
    with open(output_glb, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total_len))
        f.write(struct.pack("<II", len(new_json), 0x4E4F534A))
        f.write(new_json)
        f.write(struct.pack("<II", len(bin_data), 0x004E4942))
        f.write(bin_data)

    total_tris = sum(len(g[1][3]) // 3 for g in merged_groups)
    print(f"Rebuilt stylized human companion GLB: {total_len/1024/1024:.2f} MB, {total_tris} triangles")


if __name__ == "__main__":
    build_human_character("app/src/main/assets/models/companion.glb", "app/src/main/assets/models/companion.glb")
