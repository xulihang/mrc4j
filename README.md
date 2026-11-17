# mrc4j

A Java library for building compact MRC (Mixed Raster Content) PDFs with crisp line art from page images.

Instead of flattening a scanned page into one big color image, mrc4j splits it into three layers:

- **background plate** — the page colors, with ink removed, saved as JPEG 2000;
- **foreground plate** — the inked pixels, saved as JPEG 2000;
- **1-bit mask** — the ink footprint, stored losslessly, used as the foreground's soft mask.

Binary line art keeps hard edges at every zoom level, while the color plates can be compressed
aggressively — a common technique in scan-to-PDF pipelines.

## Requirements

- Java 11+
- OpenCV native libraries (bundled by `org.openpnp:opencv`, loaded automatically via
  `nu.pattern.OpenCV.loadLocally()`)
- No OCR required

## Library usage

```java
import com.xulihang.mrc4j.MrcPdfBuilder;

MrcPdfBuilder.Options options = new MrcPdfBuilder.Options();
options.backgroundBitsPerPixel = 0.4; // color plate rate
options.foregroundBitsPerPixel = 0.3;

try (MrcPdfBuilder builder = new MrcPdfBuilder(options)) {
    builder.addPage(Path.of("page-001.png"), 460.8f, 708.2f); // page size in PDF points
    builder.addPage(Path.of("page-002.png"), 460.8f, 708.2f);
    builder.save(Path.of("out.pdf"));
}
```

`MrcPdfBuilder.extractLayers(image, options)` exposes the three layers if you want to inspect
or re-encode them yourself.

## Command line

```bash
mvn compile exec:java -Dexec.mainClass=com.xulihang.mrc4j.MrcPdfFromImages \
  -Dexec.args="<imagesDir> <outPdf> [sourcePdf|-] [dpi=300] [bgBpp=0.4] [fgBpp=0.3]"
```

When `sourcePdf` is given, page sizes are copied from that PDF exactly; otherwise they are
derived from the image pixels and the DPI.

Example:

```bash
mvn compile exec:java -Dexec.mainClass=com.xulihang.mrc4j.MrcPdfFromImages \
  -Dexec.args="pages out.pdf source.pdf 300 0.4 0.3"
```

## Options

| Option | Default | Meaning |
|---|---|---|
| `thresholdBlockSize` | 31 | Adaptive threshold window (odd pixels) |
| `thresholdC` | 10.0 | Adaptive threshold constant |
| `backgroundBlurKernel` | 9 | Median blur used to fill ink areas in the background plate |
| `backgroundClearDilation` | 3 | Mask dilation when clearing ink from the background |
| `backgroundBitsPerPixel` | 0.4 | JPEG 2000 rate for the background plate |
| `foregroundBitsPerPixel` | 0.3 | JPEG 2000 rate for the foreground plate |

Lower rates produce smaller files; higher rates preserve more color fidelity in flat and
halftone areas.

## Notes

- The mask is currently stored as 1-bit Flate. JPEG 2000 (JPX) is used for both color plates;
  PDFBox needs the `jai-imageio-jpeg2000` ImageIO plugin to decode those when rendering.
- Pure Java has no JBIG2 encoder. If you have `jbig2enc`, its output can be embedded the same
  way (raw stream + `/JBIG2Decode`) to shrink the mask further.
- Rendering above the page's native resolution does not add detail; feed mrc4j images at their
  native scan DPI.

## License

Apache License 2.0.
