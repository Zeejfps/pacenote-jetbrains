import base64, io, sys
from PIL import Image

dino_path, app_path, out = sys.argv[1], sys.argv[2], sys.argv[3]

dino = Image.open(dino_path).convert("RGBA")
alpha = dino.getchannel("A").point(lambda a: 255 if a > 96 else 0)
box = alpha.getbbox()
alpha = alpha.crop(box)
side = max(alpha.size)
square = Image.new("L", (side, side), 0)
square.paste(alpha, ((side - alpha.width) // 2, (side - alpha.height) // 2))

def silhouette(size, rgb, name):
    margin = max(1, size // 16)
    mask = square.resize((size - 2 * margin, size - 2 * margin), Image.LANCZOS)
    canvas = Image.new("L", (size, size), 0)
    canvas.paste(mask, (margin, margin))
    icon = Image.new("RGBA", (size, size), rgb + (0,))
    icon.putalpha(canvas)
    icon.save(f"{out}/{name}.png")

for suffix, rgb in (("", (0x6C, 0x70, 0x7E)), ("_dark", (0xCE, 0xD0, 0xD6))):
    silhouette(16, rgb, f"dino{suffix}")
    silhouette(32, rgb, f"dino@2x{suffix}")

app = Image.open(app_path).convert("RGBA").resize((160, 160), Image.LANCZOS)
buf = io.BytesIO()
app.save(buf, "PNG", optimize=True)
data = base64.b64encode(buf.getvalue()).decode()
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="40" height="40" viewBox="0 0 40 40">
  <defs><clipPath id="r"><rect width="40" height="40" rx="8" ry="8"/></clipPath></defs>
  <image width="40" height="40" clip-path="url(#r)" xlink:href="data:image/png;base64,{data}"/>
</svg>
'''
open(f"{out}/../META-INF/pluginIcon.svg", "w").write(svg)
print("ok", box)
