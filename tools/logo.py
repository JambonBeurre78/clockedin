# Renders the CurseForge logo: vanilla workstations in isometric view, the Magic Wand and a clock.
#
#   python tools/logo.py            -> docs/curseforge/logo.png (400x400) and logo_800.png
#
# Vanilla models and textures are read from the Minecraft client resources jar of a local NeoForge
# 1.21.1 build (path below); nothing from it is copied into the mod. ASCII only.
import io
import json
import os
import zipfile

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "curseforge")
WAND = os.path.join(ROOT, "src", "main", "resources", "assets", "clockedin", "textures", "item", "magic_wand.png")
MC = zipfile.ZipFile(os.path.join(os.environ.get("LOCALAPPDATA", ""), "twogethercore-build", "moddev", "artifacts",
                                  "neoforge-21.1.235-minecraft-resources-aka-client-extra.jar"))

SIZE = 800
SCALE = 6.0
ACCENT = (64, 196, 110)
_tex = {}


def texture(ref):
    ref = ref if ":" in ref else "minecraft:" + ref
    if ref not in _tex:
        ns, path = ref.split(":")
        img = Image.open(io.BytesIO(MC.read("assets/%s/textures/%s.png" % (ns, path)))).convert("RGBA")
        if img.height > img.width:
            img = img.crop((0, 0, img.width, img.width))
        _tex[ref] = img
    return _tex[ref]


def model(ref):
    """Resolves the parent chain: textures merge child-first, elements come from the nearest model having them."""
    ref = ref if ":" in ref else "minecraft:" + ref
    ns, path = ref.split(":")
    m = json.loads(MC.read("assets/%s/models/%s.json" % (ns, path)))
    textures = dict(m.get("textures", {}))
    elements = m.get("elements")
    if "parent" in m and not m["parent"].endswith("block/block"):
        parent = model(m["parent"])
        textures = dict(parent["textures"], **textures)
        elements = elements or parent["elements"]
    return {"textures": textures, "elements": elements or []}


def resolve(ref, textures):
    seen = 0
    while ref.startswith("#") and seen < 10:
        ref = textures.get(ref[1:], "minecraft:block/stone")
        seen += 1
    return ref


def project(p, origin):
    x, y, z = p
    return (origin[0] + (x - z) * 0.866 * SCALE, origin[1] + (x + z) * 0.5 * SCALE - y * SCALE)


def draw_face(canvas, tex, uv, corner, u_vec, v_vec, shade, origin):
    u0, v0, u1, v1 = uv
    tw, th = tex.size
    box = (int(min(u0, u1) * tw / 16), int(min(v0, v1) * th / 16),
           max(int(min(u0, u1) * tw / 16) + 1, int(round(max(u0, u1) * tw / 16))),
           max(int(min(v0, v1) * th / 16) + 1, int(round(max(v0, v1) * th / 16))))
    patch = tex.crop(box)
    if u1 < u0:
        patch = patch.transpose(Image.FLIP_LEFT_RIGHT)
    if v1 < v0:
        patch = patch.transpose(Image.FLIP_TOP_BOTTOM)
    if shade < 1:
        r, g, b, a = patch.split()
        patch = Image.merge("RGBA", [c.point(lambda v: int(v * shade)) for c in (r, g, b)] + [a])
    o = project(corner, origin)
    pu = project([corner[i] + u_vec[i] for i in range(3)], origin)
    pv = project([corner[i] + v_vec[i] for i in range(3)], origin)
    ux, uy, vx, vy = pu[0] - o[0], pu[1] - o[1], pv[0] - o[0], pv[1] - o[1]
    det = ux * vy - uy * vx
    if abs(det) < 1e-6:
        return
    pw, ph = patch.size
    a, b, d, e = vy / det, -vx / det, -uy / det, ux / det
    data = (a * pw, b * pw, (-a * o[0] - b * o[1]) * pw, d * ph, e * ph, (-d * o[0] - e * o[1]) * ph)
    canvas.alpha_composite(patch.transform(canvas.size, Image.AFFINE, data, resample=Image.NEAREST))


def render(scene, origin):
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    boxes = []
    for ref, (bx, by, bz) in scene:
        m = model(ref)
        for e in m["elements"]:
            if e.get("rotation", {}).get("angle", 0):
                continue  # rotated details are skipped; the logo only uses boxy workstations
            f = [e["from"][0] + bx * 16, e["from"][1] + by * 16, e["from"][2] + bz * 16]
            t = [e["to"][0] + bx * 16, e["to"][1] + by * 16, e["to"][2] + bz * 16]
            boxes.append((f, t, e, m["textures"]))
    boxes.sort(key=lambda b: sum((b[0][i] + b[1][i]) / 2 for i in range(3)))
    for f, t, e, textures in boxes:
        faces, lf, lt = e["faces"], e["from"], e["to"]
        if "up" in faces:
            draw_face(canvas, texture(resolve(faces["up"]["texture"], textures)),
                      faces["up"].get("uv", [lf[0], lf[2], lt[0], lt[2]]),
                      [f[0], t[1], f[2]], [t[0] - f[0], 0, 0], [0, 0, t[2] - f[2]], 1.0, origin)
        if "south" in faces:
            draw_face(canvas, texture(resolve(faces["south"]["texture"], textures)),
                      faces["south"].get("uv", [lf[0], 16 - lt[1], lt[0], 16 - lf[1]]),
                      [f[0], t[1], t[2]], [t[0] - f[0], 0, 0], [0, -(t[1] - f[1]), 0], 0.82, origin)
        if "east" in faces:
            draw_face(canvas, texture(resolve(faces["east"]["texture"], textures)),
                      faces["east"].get("uv", [16 - lt[2], 16 - lt[1], 16 - lf[2], 16 - lf[1]]),
                      [t[0], t[1], t[2]], [0, 0, -(t[2] - f[2])], [0, -(t[1] - f[1]), 0], 0.64, origin)
    return canvas


def sprite(img, size, angle=0):
    big = img.resize((size, size), Image.NEAREST)
    return big.rotate(angle, resample=Image.NEAREST, expand=True) if angle else big


def font(size):
    for name in ("segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


SCENE = [
    ("minecraft:block/barrel", (0, 0, 0)),
    ("minecraft:block/smithing_table", (1, 0, 0)),
    ("minecraft:block/fletching_table", (2, 0, 0)),
    ("minecraft:block/cartography_table", (0, 0, 1)),
    ("minecraft:block/composter", (0, 0, 2)),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    img = Image.new("RGBA", (SIZE, SIZE), (22, 30, 26, 255))
    d = ImageDraw.Draw(img)
    for i in range(0, SIZE, 40):
        d.line([(i, 0), (i, SIZE)], fill=(31, 42, 36, 255))
        d.line([(0, i), (SIZE, i)], fill=(31, 42, 36, 255))
    d.rectangle([10, 10, SIZE - 11, SIZE - 11], outline=ACCENT + (255,), width=6)

    scene = render(SCENE, (SIZE * 0.5, SIZE * 0.3))
    scene = scene.crop(scene.getbbox())
    fit = min(1.0, 520 / scene.width, 400 / scene.height)
    scene = scene.resize((int(scene.width * fit), int(scene.height * fit)), Image.LANCZOS)
    img.alpha_composite(scene, ((SIZE - scene.width) // 2 - 40, 60))

    clock = sprite(texture("minecraft:item/clock_00"), 176)
    img.alpha_composite(clock, (70, 330))
    wand = sprite(Image.open(WAND).convert("RGBA"), 256)
    img.alpha_composite(wand, (SIZE - 300, 270))

    d = ImageDraw.Draw(img)
    for text, size, color, y in (("CLOCKED IN", 104, (240, 244, 238), 590), ("villagers that keep their jobs", 40, ACCENT, 712)):
        f = font(size)
        w = d.textlength(text, font=f)
        while w > SIZE - 70:
            size -= 2
            f = font(size)
            w = d.textlength(text, font=f)
        d.text(((SIZE - w) / 2 + 3, y + 3), text, font=f, fill=(0, 0, 0, 160))
        d.text(((SIZE - w) / 2, y), text, font=f, fill=color)

    img = img.convert("RGB")
    img.save(os.path.join(OUT, "logo_800.png"))
    img.resize((400, 400), Image.LANCZOS).save(os.path.join(OUT, "logo.png"))
    print("written", os.path.join(OUT, "logo.png"))


if __name__ == "__main__":
    main()
