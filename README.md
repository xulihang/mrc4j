# mrc4j

A Java library for building compact MRC (Mixed Raster Content) PDFs with crisp line art from page images.

Instead of flattening a scanned page into one big color image, mrc4j splits it into three layers:

- **background plate** — the page colors, with ink removed, downsampled and stored as JPEG by default;
- **foreground plate** — the inked pixels, stored as JPEG 2000 (or JPEG);
- **1-bit mask** — the ink footprint, stored as CCITT Group 4, used as the foreground's soft mask.

Binary line art keeps hard edges at every zoom level, while the color plates can be compressed
aggressively — a common technique in scan-to-PDF pipelines. The layer split, G4 stencils and JPEG
background follow the approach of [ifad/pdfbeads](https://github.com/ifad/pdfbeads).

## Requirements

- Java 11+
- OpenCV native libraries (bundled by `org.openpnp:opencv`, loaded automatically via
  `nu.pattern.OpenCV.loadLocally()`)
- No OCR required

## Library usage

```java
import com.xulihang.mrc4j.MrcPdfBuilder;

MrcPdfBuilder.Options options = new MrcPdfBuilder.Options();
options.backgroundScale = 2.0;                 // half-resolution color plate
options.backgroundJpegQuality = 0.6f;
options.foregroundBitsPerPixel = 0.05;         // foreground only shows through the mask
options.maskEncoding = MrcPdfBuilder.MaskEncoding.CCITT_G4;
options.inkMode = MrcPdfBuilder.InkMode.AUTO;  // black ink needs no foreground plate

try (MrcPdfBuilder builder = new MrcPdfBuilder(options)) {
    builder.addPage(Path.of("page-001.png"), 460.8f, 708.2f); // page size in PDF points
    builder.addPage(Path.of("page-002.png"), 460.8f, 708.2f);
    builder.save(Path.of("out.pdf"));
}
```

`addPage` returns the extracted `PageLayers` (including ink ratio and mean ink color), and
`MrcPdfBuilder.extractLayers(image, options)` exposes the three layers if you want to inspect
or re-encode them yourself.

## Command line

```bash
mvn compile exec:java -Dexec.mainClass=com.xulihang.mrc4j.MrcPdfFromImages \
  -Dexec.args="<imagesDir> <outPdf> [sourcePdf|-] [dpi=300] [bgBpp=0.4] [fgBpp=0.05] [key=value...]"
```

When `sourcePdf` is given, page sizes are copied from that PDF exactly; otherwise they are
derived from the image pixels and the DPI.

Example (defaults, suitable for scans and comics):

```bash
mvn compile exec:java -Dexec.mainClass=com.xulihang.mrc4j.MrcPdfFromImages \
  -Dexec.args="pages out.pdf - 300"
```

Maximum-compatibility variant for viewers without a JPEG 2000 decoder (larger file):

```bash
mvn compile exec:java -Dexec.mainClass=com.xulihang.mrc4j.MrcPdfFromImages \
  -Dexec.args="pages out.pdf - 300 bgFormat=jpeg fgFormat=jpeg fgQuality=0.5"
```

### Lossless mode

Add `mode=lossless` to store every page as one full-resolution lossless JPEG 2000 image,
with no downsampling and no MRC split. The result is bit-exact: decoding the page returns the
source pixels unchanged.

```bash
mvn compile exec:java -Dexec.mainClass=com.xulihang.mrc4j.MrcPdfFromImages \
  -Dexec.args="pages out.pdf - 300 mode=lossless"
```

Lossless JPX is typically 40–60% smaller than the source PNGs and needs a JPEG 2000 decoder
in the viewer. MRC mode (the default) is still much smaller and is intended for viewing.

### Text-mask mode (ImageTrans intermediates)

If you have [ImageTrans](https://www.basiccat.org/imagetrans/) intermediates for the pages, mrc4j
can use them to keep text perfectly crisp while compressing the background hard. For every
`page-001.png`, put these two files in an intermediate directory:

- `page-001.png-mask.png` — text pixel mask (opaque text pixels, transparent elsewhere);
- `page-001.png-text-removed.png` — the page with text removed/inpainted.

Then run:

```bash
mvn compile exec:java -Dexec.mainClass=com.xulihang.mrc4j.MrcPdfFromImages \
  -Dexec.args="pages out.pdf - 300 mode=textmask intermediate=path/to/intermediateResults bgBpp=0.4"
```

The text-free image becomes the background plate (full resolution, JPEG 2000 by default), the
original text pixels selected by the mask become a lossless JPEG 2000 foreground with the mask
as its G4 soft mask, and the background can be compressed much harder without ever blurring
text. `textMaskDilation` (default 1) widens the mask slightly to include antialiased edges.

## Options

Positional arguments remain `sourcePdf`, `dpi`, `bgBpp`, `fgBpp`; anything of the form
`key=value` after them overrides the corresponding option.

| Option | Default | Meaning |
|---|---|---|
| `mode` | `mrc` | `mrc` (layered, lossy), `lossless` (one full-res lossless JPX per page), or `textmask` |
| `intermediate` | — | ImageTrans intermediate directory for `mode=textmask` |
| `textMaskDilation` | 1 | Mask dilation in pixels for `mode=textmask` |
| `textFgBpp` | 0.2 | Text foreground JPX rate; the smaller of lossless and this rate is kept (0 = lossless only) |
| `thresholdBlockSize` / `blockSize` | 31 | Adaptive threshold window (odd pixels) |
| `thresholdC` | 10.0 | Adaptive threshold constant |
| `backgroundBlurKernel` / `bgBlur` | 9 | Median blur used to fill ink areas in the background plate |
| `maskOpenKernel` / `maskOpen` | 3 | Morphological opening kernel for the ink mask; 0 disables it |
| `backgroundClearDilation` / `clearDilation` | 3 | Mask dilation when clearing ink from the background |
| `maskEncoding` / `mask` | `CCITT_G4` | `g4` or `flate` encoding for the 1-bit mask |
| `backgroundFormat` / `bgFormat` | `JPEG` | `jpeg` or `jpx` |
| `backgroundScale` / `bgScale` | 2.0 | Background downsampling factor (≥ 1); mask/foreground stay full-res |
| `backgroundJpegQuality` / `bgQuality` | 0.6 | JPEG quality for the background |
| `backgroundBitsPerPixel` / `bgBpp` | 0.4 | JPEG 2000 rate when `bgFormat=jpx` |
| `foregroundFormat` / `fgFormat` | `JPX` | `jpx` or `jpeg` |
| `foregroundBitsPerPixel` / `fgBpp` | 0.05 | JPEG 2000 rate for the foreground |
| `foregroundJpegQuality` / `fgQuality` | 0.7 | JPEG quality when `fgFormat=jpeg` |
| `inkMode` / `fgMode` | `AUTO` | `auto`, `image`, or `stencil` |
| `stencilMaxSaturation` / `stencilSat` | 24 | AUTO: max mean HSV saturation of ink to use a black stencil |
| `stencilMaxValue` / `stencilVal` | 110 | AUTO: max mean HSV value of ink to use a black stencil |

Lower rates/qualities produce smaller files; higher ones preserve more color fidelity in flat
and halftone areas.

### Why the defaults look like this

- **CCITT G4 mask** — a binary text/line-art mask compresses several times smaller as G4 than
  as Flate, and every PDF reader supports it.
- **Downsampled JPEG background** — the background contains no line art (ink was removed), so
  halving its resolution is barely visible while cutting its bytes by roughly 3–4×.
- **Low-rate JPEG 2000 foreground** — the foreground plate is only ever visible through the
  binary mask, so it tolerates much lower rates than a normal photo.
- **AUTO stencil** — when the ink is dark and nearly neutral (black text), `AUTO` paints the
  G4 mask directly in black and omits the foreground plate entirely. Colored pages keep the
  foreground plate.

## Notes

- JPEG 2000 is the default for the foreground plate. Viewers without a JPX decoder
  (`jai-imageio-jpeg2000` for PDFBox-based rendering) should use `fgFormat=jpeg` or
  `fgMode=stencil`.
- Pure Java has no JBIG2 encoder. If you have `jbig2enc`, its output can be embedded the same
  way (raw stream + `/JBIG2Decode`) to shrink the mask further than G4.
- Rendering above the page's native resolution does not add detail; feed mrc4j images at their
  native scan DPI.

## License

Apache License 2.0.
