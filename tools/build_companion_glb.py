import struct
import json
import math
import io
import os
from PIL import Image, ImageDraw

def create_suit_texture(width=512, height=512):
    """Creates a sleek, modern companion suit texture with emerald & rose wellness accents."""
    # Dark modern slate background
    img = Image.new('RGB', (width, height), (30, 41, 59))
    draw = ImageDraw.Draw(img)
    
    # Subtle panel lines and high-tech contour shading
    for y in range(0, height, 16):
        c = int(32 + 10 * math.sin(y / 32.0))
        draw.line([(0, y), (width, y)], fill=(c, c + 8, c + 18), width=1)
        
    for x in range(0, width, 32):
        c = int(28 + 8 * math.cos(x / 48.0))
        draw.line([(x, 0), (x, height)], fill=(c, c + 6, c + 14), width=1)

    # Emerald wellness chest & limb stripes (matches #10B981)
    draw.rectangle([0, 0, width, 24], fill=(16, 185, 129))
    draw.rectangle([0, height - 24, width, height], fill=(16, 185, 129))
    draw.rectangle([width // 2 - 16, 0, width // 2 + 16, height], fill=(16, 185, 129))
    
    # Warm playful coral accents (matches #F43F5E)
    draw.rectangle([width // 4 - 8, 32, width // 4 + 8, height - 32], fill=(244, 63, 94))
    draw.rectangle([3 * width // 4 - 8, 32, 3 * width // 4 + 8, height - 32], fill=(244, 63, 94))
    
    # Wellness glowing beacon emblem on chest area
    cx, cy = width // 2, height // 4
    draw.ellipse([cx - 28, cy - 28, cx + 28, cy + 28], fill=(56, 189, 248), outline=(255, 255, 255), width=3)
    draw.ellipse([cx - 14, cy - 14, cx + 14, cy + 14], fill=(255, 255, 255))
    
    out = io.BytesIO()
    img.save(out, format='JPEG', quality=90, progressive=False)
    return out.getvalue()

def create_joints_texture(width=256, height=256):
    """Creates a smooth matte metallic joint texture."""
    img = Image.new('RGB', (width, height), (71, 85, 105))
    draw = ImageDraw.Draw(img)
    
    # Ring accents for articulating joints
    for r in range(16, width // 2, 24):
        draw.ellipse([width//2 - r, height//2 - r, width//2 + r, height//2 + r], outline=(148, 163, 184), width=2)
        
    out = io.BytesIO()
    img.save(out, format='JPEG', quality=88, progressive=False)
    return out.getvalue()

def euler_to_quat(roll, pitch, yaw):
    cr = math.cos(roll * 0.5)
    sr = math.sin(roll * 0.5)
    cp = math.cos(pitch * 0.5)
    sp = math.sin(pitch * 0.5)
    cy = math.cos(yaw * 0.5)
    sy = math.sin(yaw * 0.5)

    w = cr * cp * cy + sr * sp * sy
    x = sr * cp * cy - cr * sp * sy
    y = cr * sp * cy + sr * cp * sy
    z = cr * cp * sy - sr * sp * cy
    mag = math.sqrt(x*x + y*y + z*z + w*w)
    return [x/mag, y/mag, z/mag, w/mag]

def quat_mult(q1, q2):
    x1, y1, z1, w1 = q1
    x2, y2, z2, w2 = q2
    return [
        w1*x2 + x1*w2 + y1*z2 - z1*y2,
        w1*y2 - x1*z2 + y1*w2 + z1*x2,
        w1*z2 + x1*y2 - y1*x2 + z1*w2,
        w1*w2 - x1*x2 - y1*y2 - z1*z2
    ]

def build_enhanced_companion(input_glb, output_glb):
    with open(input_glb, 'rb') as f:
        magic, version, total_len = struct.unpack('<III', f.read(12))
        chunk_len, chunk_type = struct.unpack('<II', f.read(8))
        json_bytes = f.read(chunk_len)
        gltf = json.loads(json_bytes.decode('utf-8'))
        
        bin_chunk_len, bin_chunk_type = struct.unpack('<II', f.read(8))
        bin_data = bytearray(f.read(bin_chunk_len))

    # 1. Rename joints
    node_name_map = {
        'mixamorig:Head': 'Head',
        'mixamorig:Spine2': 'Chest',
        'mixamorig:Spine': 'Spine',
        'mixamorig:Hips': 'Hips',
        'mixamorig:LeftHand': 'LeftHand',
        'mixamorig:RightHand': 'RightHand',
    }
    
    right_arm_node = None
    right_forearm_node = None
    right_hand_node = None
    
    for idx, node in enumerate(gltf.get('nodes', [])):
        name = node.get('name', '')
        if name in node_name_map:
            node['name'] = node_name_map[name]
        if 'RightArm' in name:
            right_arm_node = idx
        elif 'RightForeArm' in name:
            right_forearm_node = idx
        elif 'RightHand' in name or name == 'RightHand':
            right_hand_node = idx

    print(f"Renamed joints to clean humanoid hierarchy. RightArm node: {right_arm_node}, ForeArm: {right_forearm_node}, Hand: {right_hand_node}")

    # 2. Rename animations to match CompanionMove aliases:
    # agree -> nod_agree (matches 'nod', 'agree')
    # headShake -> look_headshake (matches 'look', 'shake')
    for anim in gltf.get('animations', []):
        name = anim.get('name', '')
        if name == 'agree':
            anim['name'] = 'nod_agree'
        elif name == 'headShake':
            anim['name'] = 'look_headshake'

    # 3. Synthesize realistic wave animation
    # Find idle animation (duration 2.5s)
    idle_anim = None
    for a in gltf['animations']:
        if a['name'] == 'idle':
            idle_anim = a
            break
    if not idle_anim:
        raise ValueError("Could not find idle animation in source GLB")

    wave_anim = json.loads(json.dumps(idle_anim))
    wave_anim['name'] = 'wave'
    
    input_time_acc_idx = idle_anim['samplers'][0]['input']
    input_time_acc = gltf['accessors'][input_time_acc_idx]
    frame_count = input_time_acc['count']
    
    def get_acc_values(acc_idx, num_floats_per_item):
        acc = gltf['accessors'][acc_idx]
        bv = gltf['bufferViews'][acc['bufferView']]
        offset = bv.get('byteOffset', 0) + acc.get('byteOffset', 0)
        count = acc['count']
        format_char = '<' + 'f' * num_floats_per_item
        item_size = struct.calcsize(format_char)
        items = []
        for i in range(count):
            item = struct.unpack_from(format_char, bin_data, offset + i * item_size)
            items.append(list(item))
        return items

    arm_rot_sampler_idx = None
    forearm_rot_sampler_idx = None
    hand_rot_sampler_idx = None
    
    for ch in wave_anim['channels']:
        if ch['target']['node'] == right_arm_node and ch['target']['path'] == 'rotation':
            arm_rot_sampler_idx = ch['sampler']
        elif ch['target']['node'] == right_forearm_node and ch['target']['path'] == 'rotation':
            forearm_rot_sampler_idx = ch['sampler']
        elif ch['target']['node'] == right_hand_node and ch['target']['path'] == 'rotation':
            hand_rot_sampler_idx = ch['sampler']

    arm_rest_quats = get_acc_values(idle_anim['samplers'][arm_rot_sampler_idx]['output'], 4)
    forearm_rest_quats = get_acc_values(idle_anim['samplers'][forearm_rot_sampler_idx]['output'], 4)
    hand_rest_quats = get_acc_values(idle_anim['samplers'][hand_rot_sampler_idx]['output'], 4)

    arm_wave_quats = []
    forearm_wave_quats = []
    hand_wave_quats = []
    
    for i in range(frame_count):
        t = (i / (frame_count - 1.0)) * 2.5
        # Lift smoothly between 0 and 0.4s, wave between 0.4s and 2.0s, lower between 2.0s and 2.45s
        if t < 0.40:
            env = 0.5 * (1 - math.cos(math.pi * t / 0.40))
        elif t < 2.05:
            env = 1.0
        else:
            env = 0.5 * (1 + math.cos(math.pi * (t - 2.05) / 0.40))
            
        lift_quat = euler_to_quat(math.radians(-25 * env), 0, math.radians(75 * env))
        arm_q = quat_mult(arm_rest_quats[i], lift_quat)
        arm_wave_quats.append(arm_q)
        
        wave_angle = math.sin(t * 2 * math.pi * 2.8) * 22 * env
        forearm_wave = euler_to_quat(0, math.radians(wave_angle * 0.4), math.radians((85 + wave_angle) * env))
        forearm_q = quat_mult(forearm_rest_quats[i], forearm_wave)
        forearm_wave_quats.append(forearm_q)
        
        hand_wave = euler_to_quat(0, math.radians(wave_angle * 0.6), math.radians(wave_angle * 0.8))
        hand_q = quat_mult(hand_rest_quats[i], hand_wave)
        hand_wave_quats.append(hand_q)

    def append_vec4_accessor(quat_list):
        nonlocal bin_data
        while len(bin_data) % 4 != 0:
            bin_data.append(0)
        byte_offset = len(bin_data)
        for q in quat_list:
            bin_data.extend(struct.pack('<4f', q[0], q[1], q[2], q[3]))
        byte_length = len(quat_list) * 16
        
        bv_idx = len(gltf['bufferViews'])
        gltf['bufferViews'].append({
            'buffer': 0,
            'byteOffset': byte_offset,
            'byteLength': byte_length
        })
        
        acc_idx = len(gltf['accessors'])
        gltf['accessors'].append({
            'bufferView': bv_idx,
            'byteOffset': 0,
            'componentType': 5126, # FLOAT
            'count': len(quat_list),
            'type': 'VEC4'
        })
        return acc_idx

    arm_acc_idx = append_vec4_accessor(arm_wave_quats)
    forearm_acc_idx = append_vec4_accessor(forearm_wave_quats)
    hand_acc_idx = append_vec4_accessor(hand_wave_quats)

    wave_samplers = json.loads(json.dumps(idle_anim['samplers']))
    wave_samplers[arm_rot_sampler_idx]['output'] = arm_acc_idx
    wave_samplers[forearm_rot_sampler_idx]['output'] = forearm_acc_idx
    wave_samplers[hand_rot_sampler_idx]['output'] = hand_acc_idx
    wave_anim['samplers'] = wave_samplers

    gltf['animations'].append(wave_anim)
    print("Added synthesized wave clip. Animations list:", [a['name'] for a in gltf['animations']])

    # 4. Add textures
    tex_suit = create_suit_texture(512, 512)
    tex_joints = create_joints_texture(256, 256)

    def append_image(image_bytes, name):
        nonlocal bin_data
        while len(bin_data) % 4 != 0:
            bin_data.append(0)
        byte_offset = len(bin_data)
        bin_data.extend(image_bytes)
        byte_length = len(image_bytes)
        
        bv_idx = len(gltf['bufferViews'])
        gltf['bufferViews'].append({
            'buffer': 0,
            'byteOffset': byte_offset,
            'byteLength': byte_length
        })
        
        if 'images' not in gltf:
            gltf['images'] = []
        img_idx = len(gltf['images'])
        gltf['images'].append({
            'bufferView': bv_idx,
            'mimeType': 'image/jpeg',
            'name': name
        })
        
        if 'textures' not in gltf:
            gltf['textures'] = []
        tex_idx = len(gltf['textures'])
        gltf['textures'].append({
            'source': img_idx
        })
        return tex_idx

    tex_idx1 = append_image(tex_joints, 'companion_joints_tex')
    tex_idx2 = append_image(tex_suit, 'companion_suit_tex')

    if 'materials' in gltf and len(gltf['materials']) >= 2:
        mat0 = gltf['materials'][0]
        mat1 = gltf['materials'][1]
        mat0['pbrMetallicRoughness']['baseColorTexture'] = {'index': tex_idx1}
        mat0['pbrMetallicRoughness']['metallicFactor'] = 0.3
        mat0['pbrMetallicRoughness']['roughnessFactor'] = 0.6
        mat0['name'] = 'CompanionJoints'
        
        mat1['pbrMetallicRoughness']['baseColorTexture'] = {'index': tex_idx2}
        mat1['pbrMetallicRoughness']['metallicFactor'] = 0.1
        mat1['pbrMetallicRoughness']['roughnessFactor'] = 0.5
        mat1['name'] = 'CompanionSuit'

    while len(bin_data) % 4 != 0:
        bin_data.append(0)

    gltf['buffers'][0]['byteLength'] = len(bin_data)

    new_json_bytes = json.dumps(gltf, separators=(',', ':')).encode('utf-8')
    while len(new_json_bytes) % 4 != 0:
        new_json_bytes += b' '

    total_file_len = 12 + 8 + len(new_json_bytes) + 8 + len(bin_data)

    os.makedirs(os.path.dirname(os.path.abspath(output_glb)), exist_ok=True)
    with open(output_glb, 'wb') as f:
        f.write(struct.pack('<III', 0x46546C67, 2, total_file_len))
        f.write(struct.pack('<II', len(new_json_bytes), 0x4E4F534A))
        f.write(new_json_bytes)
        f.write(struct.pack('<II', len(bin_data), 0x004E4942))
        f.write(bin_data)

    print(f"Generated {output_glb}: {total_file_len} bytes ({total_file_len / 1024 / 1024:.2f} MB)")

if __name__ == '__main__':
    target = 'app/src/main/assets/models/companion.glb'
    build_enhanced_companion('tools/Xbot.glb', target)
