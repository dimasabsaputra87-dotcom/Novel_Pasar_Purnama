#!/usr/bin/env python3
"""Embed scene illustrations into a NOVEL book JSON.

    python tools/add_illustrations.py app/src/main/assets/books/pasar-purnama-jilid-1.json illustrations/jilid-1

Images in the folder are named  babNN-adeganM.<webp|jpg|jpeg|png>  (bab01-adegan1.webp).
The picture for scene M goes right before the M-th scene break (<hr class="scene">, the
"* * *"); the last scene of a chapter has no break after it, so its picture goes at the
end of the chapter. Images are embedded as data: URIs so the book stays one file.

Non-WebP images are converted to WebP (max 1000 px wide) when Pillow is installed
(pip install pillow); otherwise they are embedded as they are. Re-running is safe:
illustrations added earlier are removed first, so the folder is the source of truth.
"""
import base64, io, json, re, sys
from pathlib import Path

MAX_W, QUALITY = 1000, 70
NAME = re.compile(r"bab(\d+)-adegan(\d+)\.(webp|jpe?g|png)$", re.I)
OLD = re.compile(r'<figure class="illus"[^>]*>.*?</figure>\n?', re.S)
BREAK = '<hr class="scene">'


def encode(path):
    """Return (data URI, width, height)."""
    try:
        from PIL import Image
    except ImportError:
        Image = None
    raw = path.read_bytes()
    ext = path.suffix.lower().lstrip(".")
    if Image is None:
        return f"data:image/{'jpeg' if ext == 'jpg' else ext};base64,{base64.b64encode(raw).decode()}", None, None
    im = Image.open(io.BytesIO(raw))
    if ext != "webp":
        im = im.convert("RGB")
        if im.width > MAX_W:
            im = im.resize((MAX_W, round(im.height * MAX_W / im.width)), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "WEBP", quality=QUALITY, method=6)
        raw = buf.getvalue()
    return f"data:image/webp;base64,{base64.b64encode(raw).decode()}", im.width, im.height


def main(book_path, images_dir):
    book_path, images_dir = Path(book_path), Path(images_dir)
    novel = json.loads(book_path.read_text(encoding="utf-8"))
    chapters = novel["chapters"]
    for ch in chapters:
        ch["html"] = OLD.sub("", ch["html"])

    found = sorted((int(m[1]), int(m[2]), p) for p in images_dir.iterdir() if (m := NAME.match(p.name)))
    by_chapter = {}
    for bab, scene, path in found:
        if not 1 <= bab <= len(chapters):
            sys.exit(f"{path.name}: buku ini hanya punya {len(chapters)} bab")
        by_chapter.setdefault(bab, {})[scene] = path

    for bab, scenes in by_chapter.items():
        parts = chapters[bab - 1]["html"].split(BREAK)  # scene M = parts[M-1]
        for scene, path in scenes.items():
            if not 1 <= scene <= len(parts):
                sys.exit(f"{path.name}: bab {bab} hanya punya {len(parts)} adegan")
            src, w, h = encode(path)
            size = f' width="{w}" height="{h}"' if w else ""
            fig = f'<figure class="illus" data-scene="{bab}-{scene}"><img src="{src}"{size} alt=""></figure>\n'
            # End of the scene: after its last paragraph, right before the break.
            part = parts[scene - 1]
            body = part.rstrip("\n")
            parts[scene - 1] = body + "\n" + (fig if scene < len(parts) else fig.rstrip("\n") + part[len(body):])
            print(f"bab {bab} adegan {scene}: {path.name} ({len(src) // 1024} KB)")
        chapters[bab - 1]["html"] = BREAK.join(parts)

    book_path.write_text(json.dumps(novel, ensure_ascii=False), encoding="utf-8")
    print(f"{book_path}: {book_path.stat().st_size // 1024} KB")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
