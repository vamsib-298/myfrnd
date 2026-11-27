"""Rebuild the walking scan from the project's original CC BY 4.0 Monochrome Moment asset.

Requires numpy and scipy. Optional argument: path to the original, four-joint GLB.
"""
import io
import json
import math
from pathlib import Path
import struct
import subprocess
import sys

import numpy as np
from PIL import Image
from scipy.sparse import csr_matrix
from scipy.sparse.csgraph import connected_components, dijkstra
from scipy.spatial.transform import Rotation

MODEL = Path("app/src/main/assets/models/companion.glb")
SCAN_REV = "2e3b3bd"
source = (Path(sys.argv[1]).read_bytes() if len(sys.argv) > 1 else
          subprocess.check_output(["git", "show", f"{SCAN_REV}:{MODEL.as_posix()}"]))
size = struct.unpack_from("<I", source, 12)[0]
g = json.loads(source[20:20+size])
data = bytearray(source[28+size:])
views = g["bufferViews"]
accessors = g["accessors"]
prim = g["meshes"][0]["primitives"][0]


def array(i):
    a = accessors[i]
    v = views[a["bufferView"]]
    n = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[a["type"]]
    dtype = np.dtype({5121: "u1", 5123: "<u2", 5126: "<f4"}[a["componentType"]])
    return np.ndarray((a["count"], n), dtype, buffer=data,
                      offset=v.get("byteOffset", 0) + a.get("byteOffset", 0),
                      strides=(v.get("byteStride", n*dtype.itemsize), dtype.itemsize))


def append(values, kind):
    values = np.asarray(values, dtype="<f4")
    data.extend(bytes(-len(data) % 4))
    views.append(dict(buffer=0, byteOffset=len(data), byteLength=values.nbytes))
    data.extend(values.tobytes())
    a = dict(bufferView=len(views)-1, componentType=5126, type=kind, count=len(values))
    if kind == "SCALAR":
        a.update(min=[float(values.min())], max=[float(values.max())])
    accessors.append(a)
    return len(accessors)-1


# Joint centres in the scan's asymmetric standing pose, in metres (+Z faces the viewer).
bones = [
    ("Hips", None, (-.035,.942,-.027)), ("Spine","Hips",(-.045,1.085,-.018)),
    ("Chest","Spine",(-.055,1.31,-.018)), ("Neck","Chest",(-.066,1.566,.008)),
    ("Head","Neck",(-.071,1.626,.015)),
]
for side, shoulder, elbow, wrist, tip, hip, knee, ankle in [
    ("Left", (.140,1.477,-.022),(.183,1.173,.010),(.145,.920,.082),(.120,.815,.085),
     (.073,.922,-.024),(.143,.502,.002),(.237,.107,-.018)),
    ("Right",(-.235,1.473,-.039),(-.260,1.18,-.055),(-.293,.915,-.015),(-.294,.812,.012),
     (-.133,.925,-.036),(-.124,.502,.005),(-.093,.107,-.060))
]:
    for name,parent,point in [
        ("Arm","Chest",shoulder),("ForeArm",side+"Arm",elbow),("Hand",side+"ForeArm",wrist),
        ("Fingers",side+"Hand",tip),("Thigh","Hips",hip),("Shin",side+"Thigh",knee),
        ("Foot",side+"Shin",ankle),("Toe",side+"Foot",(ankle[0],.044,ankle[2]+.128))
    ]:
        bones.append((side+name,parent,point))
names = [b[0] for b in bones]
parents = [names.index(p) if p else -1 for _,p,_ in bones]
pos = np.array([b[2] for b in bones])
children = [[i for i,p in enumerate(parents) if p == j] for j in range(len(bones))]


def skin_weights():
    # Work on welded vertices, so UV seams do not split the forearm or a trouser leg.
    vertices, inverse = np.unique(np.round(array(prim["attributes"]["POSITION"]), 5),
                                  axis=0, return_inverse=True)
    faces = inverse[array(prim["indices"]).reshape(-1,3)]
    edges = np.unique(np.sort(np.concatenate([faces[:,[0,1]],faces[:,[1,2]],faces[:,[2,0]]]),axis=1),axis=0)
    edges = np.vstack((edges,edges[:,::-1]))
    length = np.linalg.norm(vertices[edges[:,0]]-vertices[edges[:,1]],axis=1)
    graph = csr_matrix((length,(edges[:,0],edges[:,1])),shape=(len(vertices),len(vertices)))
    # Broad torso, narrow arm. Strongly identified surface patches seed distances along the
    # mesh so a hand beside a pocket follows the wrist, even where the surfaces almost touch.
    costs = []
    for i,name in enumerate(names):
        end = pos[children[i][0]] if children[i] else pos[i]+[0,-.050,.008]
        if name == "Chest": end = pos[names.index("Neck")]
        if name == "Head": end = np.array([-.070,1.904,.006])
        vector = end-pos[i]
        t = np.clip(((vertices-pos[i])@vector)/(vector@vector),.12,.88)
        delta = vertices-(pos[i]+t[:,None]*vector)
        if name in ("Hips","Spine","Chest"):
            delta[:,0] *= .65
            delta[:,2] *= .72
        costs.append(np.linalg.norm(delta,axis=1))
    costs=np.stack(costs,axis=1)
    closest=np.argmin(costs,axis=1)
    geodesic=[]
    for i in range(len(bones)):
        seeds=np.flatnonzero(closest==i)
        dist=dijkstra(graph,indices=seeds,min_only=True)
        geodesic.append(np.minimum(dist, .24+costs[:,i]))
    distance=costs*.55 + np.array(geodesic).T*1.6
    # The scanned left hand touches the trousers. Colour and surface connectivity distinguish
    # skin from the neighbouring fabric where Euclidean bone distances alone are ambiguous.
    uv=array(prim["attributes"]["TEXCOORD_0"]) % 1
    mat=g["materials"][0]["pbrMetallicRoughness"]
    source_image=g["textures"][mat["baseColorTexture"]["index"]]["source"]
    view=views[g["images"][source_image]["bufferView"]]
    start=view["byteOffset"]
    texture=np.asarray(Image.open(io.BytesIO(data[start:start+view["byteLength"]])).convert("RGB"))
    rgb=texture[(uv[:,1]*(texture.shape[0]-1)).astype(int),(uv[:,0]*(texture.shape[1]-1)).astype(int)].astype(float)
    warm=(rgb[:,0]>65)&(rgb[:,0]>1.16*rgb[:,2]+13)
    warm=np.bincount(inverse,weights=warm)/np.bincount(inverse)>.45
    x,y,z=vertices.T
    arm_names=("Arm","ForeArm","Hand","Fingers")
    arm_indices=[k for k,n in enumerate(names) if n.endswith(arm_names)]
    non_arm=[k for k in range(len(names)) if k not in arm_indices]
    sub=faces[np.all(y[faces]<1.23,axis=1)]
    sub_edges=np.concatenate([sub[:,[0,1]],sub[:,[1,2]],sub[:,[2,0]]])
    sub_graph=csr_matrix((np.ones(len(sub_edges)),(sub_edges[:,0],sub_edges[:,1])),shape=graph.shape)
    _,island=connected_components(sub_graph,directed=False)
    right_seed=np.argmin(np.linalg.norm(vertices-[-.295,.818,.010],axis=1))
    right_island=island==island[right_seed]
    for side,side_mask in [("Left",x>.035),("Right",x<-.14)]:
        group=[names.index(side+n) for n in arm_names]
        seeds=warm & side_mask & (y>.763) & (y<1.34)
        other=(~warm) & (y<1.22)
        hand_dist=dijkstra(graph,indices=np.flatnonzero(seeds),min_only=True)
        cloth_dist=dijkstra(graph,indices=np.flatnonzero(other),min_only=True)
        arm=side_mask & (y<1.28) & (hand_dist<cloth_dist)
        if side=="Right":
            arm=np.where(y<1.23,right_island,arm)
        distance[np.ix_(arm,non_arm)] += 2
        distance[np.ix_((~arm)&(y<1.28),group)] += 2
    below=y<.755
    split=np.interp(y,[0,.49,.75],[.052,.014,-.003])
    for side,wrong in [("Left",x<split),("Right",x>=split)]:
        group=[names.index(side+n) for n in ("Thigh","Shin","Foot","Toe")]
        distance[np.ix_(below & wrong,group)] += 2
    selected=np.argsort(distance,axis=1)[:,:4]
    d=np.take_along_axis(distance,selected,axis=1)
    w=np.exp(-(d-d[:,:1])/.034)
    w/=w.sum(axis=1)[:,None]
    weights=np.zeros_like(distance)
    np.put_along_axis(weights,selected,w,axis=1)
    # Smooth over the actual scan surface (including around sleeves), not across air.
    # Separate the triangles at the small contact patch between left fingers and trousers.
    left_ids=[names.index("Left"+n) for n in arm_names]
    on_hand=(weights[:,left_ids].sum(axis=1)>.5)&(y<1.06)&(x>.02)
    raw_faces=array(prim["indices"]).reshape(-1,3)
    contact=np.any(on_hand[faces],axis=1)&~np.all(on_hand[faces],axis=1)&(y[faces].max(axis=1)<.96)
    raw_faces[contact]=raw_faces[contact,:1]
    seam=(on_hand[edges[:,0]]!=on_hand[edges[:,1]])&(y[edges].max(axis=1)<.96)
    adj=csr_matrix((1/np.maximum(length[~seam],.003),(edges[~seam,0],edges[~seam,1])),shape=graph.shape)
    total=np.asarray(adj.sum(axis=1)).ravel()
    free=total>0
    for _ in range(35):
        mean=(adj@weights)[free]/total[free,None]
        weights[free]=.55*weights[free]+.45*mean
    selected=np.argsort(weights,axis=1)[:,-4:]
    w=np.take_along_axis(weights,selected,axis=1)
    w/=w.sum(axis=1)[:,None]
    array(prim["attributes"]["JOINTS_0"])[:] = selected[inverse]
    array(prim["attributes"]["WEIGHTS_0"])[:] = w[inverse]


skin_weights()
g["asset"]["copyright"]="Monochrome Moment by restore50, CC BY 4.0. Optimized, re-rigged and animated for MyFrnd."
nodes = []
for i,name in enumerate(names):
    parent = parents[i]
    node = dict(name=name,translation=(pos[i]-(pos[parent] if parent>=0 else 0)).tolist())
    if children[i]: node["children"]=children[i]
    nodes.append(node)
nodes.append(dict(name="Scanned companion",mesh=0,skin=0))
g["nodes"]=nodes
g["scenes"]=[dict(nodes=[0,len(bones)])]
inverse_bind=np.repeat(np.eye(4)[None,:,:],len(bones),axis=0)
inverse_bind[:,:3,3]=-pos
g["skins"]=[dict(joints=list(range(len(bones))),skeleton=0,
                  inverseBindMatrices=append(inverse_bind.transpose(0,2,1),"MAT4"))]


def aim(a,b):
    a=a/np.linalg.norm(a)
    b=b/np.linalg.norm(b)
    cross=np.cross(a,b)
    return Rotation.from_rotvec(cross/max(1e-9,np.linalg.norm(cross))*math.acos(np.clip(a@b,-1,1)))


# Retarget CMU subject 07, walk 01, in the scan's coordinate system (+Z forward).
with np.load(Path(__file__).with_name("cmu_walk_07_01.npz")) as capture:
    walk_duration = float(capture["duration"])
    stride_per_leg = float(capture["stride"])
    tracks = {k: capture[k] for k in capture if capture[k].ndim == 2}


def captured_walk(fraction, local):
    sample = fraction * (len(tracks["Hips"]) - 1)
    i = min(int(sample), len(tracks["Hips"]) - 2)
    marks = {k: v[i] + (v[i+1]-v[i])*(sample-i) for k,v in tracks.items()}
    joint = dict(zip(names,pos))

    def line_turn(line):
        # Use the measured hip and shoulder lines independently, allowing counterrotation.
        yaw = -math.atan2(line[2],line[0])
        roll = math.atan2(line[1],math.hypot(line[0],line[2]))
        return Rotation.from_euler("yz",[yaw,roll])

    pelvis = line_turn(marks["LeftUpLeg"]-marks["RightUpLeg"])
    shoulders = line_turn(marks["LeftArm"]-marks["RightArm"])
    local["Hips"] = pelvis
    local["Chest"] = pelvis.inv()*shoulders
    local["Neck"] = shoulders.inv()
    offset = marks["Hips"] * .833
    soles = []
    for side in ("Left","Right"):
        def direction(start,end):
            return marks[side+end]-marks[side+start]

        for chain, source, parent in [
            (("Thigh","Shin","Foot"),("UpLeg","Leg","Foot"),pelvis),
            (("Arm","ForeArm","Hand"),("Arm","ForeArm","Hand"),shoulders)
        ]:
            first,middle,last = [side+n for n in chain]
            start,joint2,tip = source
            upper = aim(joint[middle]-joint[first],direction(start,joint2))
            lower = aim(joint[last]-joint[middle],direction(joint2,tip))
            local[first] = parent.inv()*upper
            local[middle] = upper.inv()*lower
            if chain[0] == "Thigh":
                ankle = (joint["Hips"] + pelvis.apply(joint[first]-joint["Hips"])
                         + upper.apply(joint[middle]-joint[first]) + lower.apply(joint[last]-joint[middle]))
                toe = direction("Foot","ToeBase")
                pitch = np.clip(-math.degrees(math.atan2(toe[1],toe[2]))-13, -25, 48)
                shoe = Rotation.from_euler("x",pitch,degrees=True)
                local[last] = lower.inv()*shoe
                local[side+"Toe"] = Rotation.from_euler("x",-.68*max(0,pitch),degrees=True)
                # Place the lowest supporting heel or ball of the shoe on the floor.
                soles.extend(ankle[1]+shoe.apply([0,-joint[last][1],z])[1] for z in (-.058,.13))
    offset[1] = -min(soles)
    return offset


def clip(name,duration):
    times=np.linspace(0,duration,(48 if name == "walk" else round(duration*30))+1)
    rotations={n:[] for n in names}
    shifts=[]
    for seconds in times:
        phase=seconds/duration*2*math.pi
        local={n:Rotation.identity() for n in names}
        offset=np.zeros(3)
        if name=="walk":
            offset=captured_walk(seconds/duration,local)
        elif name=="wave":
            up=math.sin(math.pi*seconds/duration)**2
            local["RightArm"]=Rotation.from_euler("xz",[-19*up,-47*up],degrees=True)
            local["RightForeArm"]=Rotation.from_euler("xz",[-18*up,-92*up],degrees=True)
            local["RightHand"]=Rotation.from_euler("z",up*18*math.sin(phase*4),degrees=True)
            local["Head"]=Rotation.from_euler("z",-4*up,degrees=True)
        elif name=="nod":
            local["Head"]=Rotation.from_euler("x",7*math.sin(phase)*math.sin(phase/2)**2,degrees=True)
        else:
            local["Chest"]=Rotation.from_euler("x",.48*math.sin(phase),degrees=True)
            local["Neck"]=local["Chest"].inv()
        shifts.append(pos[0]+offset)
        for n in names: rotations[n].append(local[n].as_quat())
    time=append(times,"SCALAR")
    samplers,channels=[],[]
    def channel(n,path,values,kind):
        channels.append(dict(sampler=len(samplers),target=dict(node=names.index(n),path=path)))
        samplers.append(dict(input=time,output=append(values,kind),interpolation="LINEAR"))
    # Key each joint, including stationary ones: gestures can interrupt any other clip.
    for n,values in rotations.items(): channel(n,"rotation",values,"VEC4")
    channel("Hips","translation",shifts,"VEC3")
    return dict(name=name,channels=channels,samplers=samplers)


g["animations"]=[clip("idle",3.2),clip("walk",walk_duration),clip("wave",2.2),clip("nod",1.6)]
data.extend(bytes(-len(data)%4))
g["buffers"]=[dict(byteLength=len(data))]
raw=json.dumps(g,separators=(",",":")).encode()
raw+=b" "*(-len(raw)%4)
MODEL.write_bytes(struct.pack("<4sIIII",b"glTF",2,28+len(raw)+len(data),len(raw),0x4e4f534a)
                  +raw+struct.pack("<II",len(data),0x004e4942)+data)
print(f"Built {MODEL.stat().st_size:,} bytes with {len(names)} joints")
