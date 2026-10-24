# Synthetic AVIF fixture

`white-32.avif` is an original, uniformly white 32 × 32 RGB image, generated for
the AVIF translation regression. It contains no text, artwork, metadata from a
downloaded comic, or external source material.

Generated with Pillow 12.3.0:

```python
from PIL import Image

Image.new("RGB", (32, 32), (255, 255, 255)).save(
    "white-32.avif", format="AVIF", quality=100, subsampling="4:4:4", speed=8
)
```

The committed fixture is 300 bytes. Its SHA-256 is
`c03ca656b1ca74938cc7b270a34caa9e0147f2038ed1225464a4a2ed62c5594d`.
The Android regression checks the exact encoded bytes, `image/avif` header
recognition, actual decoding, dimensions, and every decoded white pixel before
calling the public translation entry points with a scripted engine.
