"""Bake 24 side-profile walking poses from the tabby's transparent standing plate.

Four independent paw contacts, flexing hocks, shifting shoulders and a following tail.
The six-by-four WebP atlas keeps drawing on Android to a single filtered bitmap.
"""
from pathlib import Path
import sys

import numpy as np
from PIL import Image
from scipy.ndimage import map_coordinates

W, H = 336, 224
source = Image.open(sys.argv[1]).convert("RGBA").resize((W,H),Image.Resampling.LANCZOS)
rgba = np.asarray(source).astype(float)
rgba[:,:,3] = np.where(rgba[:,:,3]<10, 0, rgba[:,:,3])
y, x = np.mgrid[:H,:W]
u,v = x/W,y/H
hind_split = np.interp(v,[.53,.66,.77,.97],[.374,.330,.270,.277])
fore_split = np.interp(v,[.55,.70,.84,.99],[.784,.761,.753,.785])
# Follow the real transparent gap between the two forepaws at each scanline.
for row in range(int(.71*H), H):
    guess = fore_split[row,0]*W
    gap = np.flatnonzero((rgba[row,:,3]<10)&(np.abs(x[row]-guess)<.037*W))
    if gap.size:
        fore_split[row,:] = gap[np.argmin(np.abs(gap-guess))]/W
legs = np.select([u<hind_split, u<.55, u<fore_split],[0,1,2],default=3)
atlas = Image.new("RGBA",(W*6,H*4))


def smooth(a,b,value):
    t=np.clip((value-a)/(b-a),0,1)
    return t*t*(3-2*t)


def sample(layer, sx, sy):
    pixels=np.stack([map_coordinates(layer[:,:,c],[sy,sx],order=1,mode="constant")
                     for c in range(4)],axis=2)
    return Image.fromarray(np.uint8(np.clip(pixels,0,255)))


for frame in range(24):
    phase=frame/24
    picture=Image.new("RGBA",(W,H))
    for leg in (1,3,0,2):
        # Four beats: each hind paw lifts just before the forepaw on the same side.
        t=(phase+[0,.5,-.17,.33][leg])%1
        stance=.62
        if t<stance:
            foot=.5*stance-t
            swing=0
        else:
            s=(t-stance)/(1-stance)
            foot=-stance*.5-(1-stance)*s+s*s*(3-2*s)
            swing=np.sin(np.pi*s)**2*(1.28-.56*s)
        front=leg>=2
        # Far and near paws converge under the body in a side-on walk.
        bias=[.094,-.074,.022,-.045][leg]
        flex=smooth(.48,.98,v)
        shin=np.sin(np.pi*np.clip((v-.54)/.44,0,1))
        dx=W*((bias+.40*foot)*flex + (.035 if front else -.055)*shin*swing)
        dy=-H*(.085 if front else .10)*swing*flex
        layer=rgba.copy()
        layer[:,:,3]*=(legs==leg)&(v>.47)
        # Invert the monotonic vertical warp before sampling the horizontal bend.
        sy=np.interp(np.arange(H),np.arange(H)+dy[:,0],np.arange(H))
        flank = smooth(.55,.71,u) if front else 1-smooth(.37,.55,u)
        sx=x-np.interp(sy,np.arange(H),dx[:,0])[:,None]*flank
        sy=y+(sy[:,None]-y)*flank
        picture.alpha_composite(sample(layer,sx,sy))
    # All layers have zero deformation at the upper-leg join. Blend the shoulder overlap.
    body=rgba.copy()
    belly=smooth(.36,.47,u)*(1-smooth(.65,.735,u))
    body[:,:,3]*=1-smooth(.51+.12*belly,.58+.09*belly,v)
    # Transfer weight from hip to shoulder as each pair passes. Keep eyes level.
    hip=np.cos(phase*4*np.pi)
    shoulder=np.cos((phase-.17)*4*np.pi)
    forward=smooth(.35,.72,u)
    bob=.009*H*((1-forward)*hip+forward*shoulder)*(1-.85*smooth(.75,.9,u))
    tail=(1-smooth(.08,.30,u))*(1-smooth(.36,.53,v))
    tip=tail*np.sin(2*np.pi*phase+2*u)
    picture.alpha_composite(sample(body,x-5*tip,y-bob*(1-smooth(.42,.56,v))-6*tip))
    atlas.paste(picture,(frame%6*W,frame//6*H))

atlas.save("app/src/main/res/drawable-nodpi/focus_tabby_walk.webp",quality=90,method=6)
