"""Generate AegisPhone launcher icons (pure Python, no dependencies)"""

import struct
import zlib
import os


def make_png(width, height, r, g, b):
    def chunk(chunk_type, data):
        c = chunk_type + data
        return struct.pack(">I", len(data)) + c + struct.pack(
            ">I", zlib.crc32(c) & 0xFFFFFFFF
        )

    header = b"\x89PNG\r\n\x1a\n"
    ihdr = chunk(
        b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    )

    raw = b""
    cx, cy = width // 2, height // 2
    for y in range(height):
        raw += b"\x00"
        for x in range(width):
            dist = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 / cx
            if dist < 0.85:
                raw += struct.pack("BBB", 255, 200, 50)
            elif dist < 0.95:
                t = (dist - 0.85) / 0.1
                rr = int(255 - (255 - 180) * t)
                gg = int(200 - (200 - 80) * t)
                raw += struct.pack("BBB", max(0, rr), max(0, gg), 50)
            else:
                raw += struct.pack("BBB", r, g, b)

    idat = chunk(b"IDAT", zlib.compress(raw))
    iend = chunk(b"IEND", b"")
    return header + ihdr + idat + iend


def main():
    base = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "hermes-android",
        "app",
        "src",
        "main",
        "res",
    )

    icons = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    for folder, size in icons.items():
        dir_path = os.path.join(base, folder)
        os.makedirs(dir_path, exist_ok=True)
        filepath = os.path.join(dir_path, "ic_launcher.png")
        with open(filepath, "wb") as f:
            f.write(make_png(size, size, 20, 20, 50))
        print(f"  {folder}/ic_launcher.png ({size}x{size})")

    # Drawable
    drawable_dir = os.path.join(base, "drawable")
    os.makedirs(drawable_dir, exist_ok=True)

    with open(os.path.join(drawable_dir, "ic_launcher_background.xml"), "w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            '    <path android:fillColor="#141432"\n'
            '        android:pathData="M0,0h108v108h-108z"/>\n'
            "</vector>\n"
        )

    with open(os.path.join(drawable_dir, "ic_launcher_foreground.xml"), "w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            '    <path android:fillColor="#FFC832"\n'
            '        android:pathData="M54,18A36,36 0,1 1,54,90A36,36 0,1 1,54,18Z"/>\n'
            '    <path android:fillColor="#141432"\n'
            '        android:pathData="M54,32L40,68h28L54,32ZM50,58l4,-14l4,14h-8Z"/>\n'
            '    <path android:fillColor="#141432"\n'
            '        android:pathData="M50,68h8v4h-8z"/>\n'
            "</vector>\n"
        )

    # Adaptive icon
    anydpi_dir = os.path.join(base, "mipmap-anydpi-v26")
    os.makedirs(anydpi_dir, exist_ok=True)

    with open(os.path.join(anydpi_dir, "ic_launcher.xml"), "w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
            "</adaptive-icon>\n"
        )

    print("  AegisPhone icons generated successfully!")


if __name__ == "__main__":
    main()
