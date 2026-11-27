"""Render the mobile software fallback from the same textured GLB used by Filament."""
import io
import json
import math
from pathlib import Path
import struct

import numpy as np
from numba import njit
from PIL import Image
from scipy.spatial.transform import Rotation

ASSETS = Path("app/src/main")
TILE_WIDTH, TILE_HEIGHT = 192, 256
SUPERSAMPLE = 2


@njit
def raster(vertices, triangles, uv, texture, width, height):
    pixels = np.zeros((height,width,4),np.uint8)
    depth = np.full((height,width),-np.inf)
    for a,b,c in triangles:
        ax,ay,az = vertices[a]
        bx,by,bz = vertices[b]
        cx,cy,cz = vertices[c]
        det = (by-cy)*(ax-cx)+(cx-bx)*(ay-cy)
        if abs(det)<.00001:
            continue
        for y in range(max(0,math.floor(min(ay,by,cy))),min(height,math.ceil(max(ay,by,cy)))):
            for x in range(max(0,math.floor(min(ax,bx,cx))),min(width,math.ceil(max(ax,bx,cx)))):
                u=((by-cy)*(x+.5-cx)+(cx-bx)*(y+.5-cy))/det
                v=((cy-ay)*(x+.5-cx)+(ax-cx)*(y+.5-cy))/det
                w=1-u-v
                z=u*az+v*bz+w*cz
                if min(u,v,w)<0 or z<=depth[y,x]:
                    continue
                depth[y,x]=z
                tx=int(((u*uv[a,0]+v*uv[b,0]+w*uv[c,0])%1)*texture.shape[1])
                ty=int(((u*uv[a,1]+v*uv[b,1]+w*uv[c,1])%1)*texture.shape[0])
                pixels[y,x,:3]=texture[ty,tx]
                pixels[y,x,3]=255
    return pixels


class Scan:
    def __init__(self, path):
        raw = path.read_bytes()
        n = struct.unpack_from("<I",raw,12)[0]
        self.model = json.loads(raw[20:20+n])
        self.data = raw[28+n:]
        mesh = self.model["meshes"][0]["primitives"][0]
        attrs = mesh["attributes"]
        positions = self.read(attrs["POSITION"])
        self.vertices = np.column_stack([positions,np.ones(len(positions))])
        self.faces = self.read(mesh["indices"]).reshape(-1,3)
        self.uv = self.read(attrs["TEXCOORD_0"])
        self.joints = self.read(attrs["JOINTS_0"])
        self.weights = self.read(attrs["WEIGHTS_0"])
        skin = self.model["skins"][0]
        self.bind = self.read(skin["inverseBindMatrices"]).reshape(-1,4,4).transpose(0,2,1)
        material = self.model["materials"][mesh["material"]]["pbrMetallicRoughness"]
        source = self.model["textures"][material["baseColorTexture"]["index"]]["source"]
        view = self.model["bufferViews"][self.model["images"][source]["bufferView"]]
        offset = view["byteOffset"]
        self.texture = np.asarray(Image.open(io.BytesIO(self.data[offset:offset+view["byteLength"]])).convert("RGB"))

    def read(self, index):
        accessor = self.model["accessors"][index]
        view = self.model["bufferViews"][accessor["bufferView"]]
        count = {"SCALAR":1,"VEC2":2,"VEC3":3,"VEC4":4,"MAT4":16}[accessor["type"]]
        dtype = np.dtype({5121:"u1",5123:"<u2",5126:"<f4"}[accessor["componentType"]])
        return np.ndarray((accessor["count"],count),dtype,buffer=self.data,
                          offset=view.get("byteOffset",0)+accessor.get("byteOffset",0),
                          strides=(view.get("byteStride",count*dtype.itemsize),dtype.itemsize))

    def pose(self, name, fraction, strength=1.):
        clip = next(a for a in self.model["animations"] if a["name"]==name)
        nodes = self.model["nodes"][:len(self.bind)]
        matrices = np.tile(np.eye(4),(len(nodes),1,1))
        matrices[:,:3,3] = [n["translation"] for n in nodes]
        for channel in clip["channels"]:
            sampler = clip["samplers"][channel["sampler"]]
            values = self.read(sampler["output"])
            times = self.read(sampler["input"]).ravel()
            time = fraction*times[-1]
            i = int(np.clip(np.searchsorted(times,time)-1,0,len(times)-2))
            t = (time-times[i])/(times[i+1]-times[i])
            target = channel["target"]
            a,b = values[i],values[i+1]
            node = target["node"]
            if target["path"]=="rotation":
                if a@b<0:
                    b=-b
                turn = Rotation.from_quat((1-t)*a+t*b)
                matrices[node,:3,:3] = Rotation.from_rotvec(turn.as_rotvec()*strength).as_matrix()
            else:
                matrices[node,:3,3] += strength * ((1-t)*a+t*b - matrices[node,:3,3])
        for i,node in enumerate(nodes):
            for child in node.get("children",[]):
                matrices[child] = matrices[i]@matrices[child]
        bones = matrices@self.bind
        skin = (bones[self.joints]*self.weights[:,:,None,None]).sum(axis=1)
        return (skin@self.vertices[:,:,None])[:,:3,0]

    def frame(self, clip, fraction, yaw, strength=1.):
        x,y,z = Rotation.from_euler("y",yaw).apply(self.pose(clip,fraction,strength)).T
        projected = np.column_stack([(TILE_WIDTH/2+x*126)*SUPERSAMPLE,
                                     (TILE_HEIGHT-5-y*126)*SUPERSAMPLE,z])
        pixels = raster(projected,self.faces,self.uv,self.texture,TILE_WIDTH*SUPERSAMPLE,TILE_HEIGHT*SUPERSAMPLE)
        return Image.fromarray(pixels).resize((TILE_WIDTH,TILE_HEIGHT),Image.Resampling.LANCZOS)


if __name__=="__main__":
    scan = Scan(ASSETS/"assets/models/companion.glb")
    atlas = Image.new("RGBA",(8*TILE_WIDTH,6*TILE_HEIGHT))
    for i in range(48):
        frame = scan.frame("walk",i/32,math.pi/2) if i<32 else scan.frame("wave",(i-32)/16,.06)
        atlas.paste(frame,((i%8)*TILE_WIDTH,(i//8)*TILE_HEIGHT))
    images = ASSETS/"res/drawable-nodpi"
    atlas.save(images/"companion_actions.webp",quality=94,method=6)
    scan.frame("idle",0,.06).save(images/"companion_standing.webp",quality=94,method=6)

    # Two planted-foot poses, turning from a neutral front view into the recorded stride.
    turns = Image.new("RGBA",(8*TILE_WIDTH,2*TILE_HEIGHT))
    for foot in range(2):
        for i in range(8):
            strength = i/7
            frame = scan.frame("walk",foot/2,math.pi/2 * strength,strength)
            turns.paste(frame,(i*TILE_WIDTH,foot*TILE_HEIGHT))
    turns.save(images/"companion_turn.webp",quality=94,method=5)
