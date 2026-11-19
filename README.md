# mrc4j

Java (11+) port of the image compression part of
[internetarchive/archive-pdf-tools](https://github.com/internetarchive/archive-pdf-tools)
(`bin/compress-pdf-images` / `internetarchivepdf/mrc.py`), package
`com.xulihang.mrc4j`.

Each image on each page of a PDF is split into three MRC layers, compressed,
and put back where the original image was:

* **mask** – 1-bit text/line-art mask (Sauvola binarisation + speckle removal)
* **foreground** – masked pixels kept at full resolution, everything else
  replaced by averaged surrounding content (so it compresses well)
* **background** – inverted mask, downsampled (`--bg-downsample 3` by default)

## Build

```
mvn -DskipTests package
```

Produces `target/mrc4j-1.0-SNAPSHOT.jar` (self-contained; PDFBox, JAI
JPEG2000 and jbig2 decoders are shaded in). Maven 3.6+ and JDK 11+ are
required.

### Thin jar for hosts with existing dependencies

If the host application already provides PDFBox 2.0.31, fontbox and
jai-imageio 1.4.0, build a thin jar that contains only the
`com.xulihang.mrc4j` classes:

```
mvn -Pthin package
```

The resulting `target/mrc4j-1.0-SNAPSHOT.jar` (~67 KB) relies on the host's
existing PDFBox/JAI jars. Do not add the shaded jar at the same time.

For page-by-page processing, `Mrc4j.addPageImageFromFile` compresses a single
image and draws the background and foreground (with its mask) into an already
open `PDPageContentStream`:

```java
long layerBytes = Mrc4j.addPageImageFromFile(doc, contentStream, imagePath,
        0, 0, width, height);
```

It returns the encoded layer bytes so the caller can update progress after
each image. The overload with explicit rates takes
`fgBpp, bgBpp, bgDownsample`. It uses the built-in JAI encoder and a G4 mask
and needs no external `kdu_compress` / `jbig2` executable. Text drawn into the
content stream afterwards stays on top.

For an already loaded PDF, `Mrc4j.compressPage(doc, pageIndex)` compresses the
page-level images in place. Each image draw is replaced at its original
content-stream position, so text and vector content keep their drawing order;
iterate the pages to update progress and save the document afterwards:

```java
for (int i = 0; i < doc.getNumberOfPages(); i++) {
    long bytes = Mrc4j.compressPage(doc, i);
    // update progress here
}
doc.save(out);
```

Images inside form XObjects are left untouched. `compressInPlace` / `compress`
use the same in-place replacement for whole-document callers.

## Run

```
java -cp target/mrc4j-1.0-SNAPSHOT.jar com.xulihang.mrc4j.PdfCompressor in.pdf out.pdf [options]
```

The quickest self-contained run (no external encoders):

```
java -cp target/mrc4j-1.0-SNAPSHOT.jar com.xulihang.mrc4j.PdfCompressor \
    in.pdf out.pdf --encoder java --no-jbig2
```

`--encoder java` uses the JAI JPEG2000 writer bundled in the jar; `--no-jbig2`
uses a CCITT Group 4 mask (pass `--mask-compression flate` for Flate). With
Kakadu and jbig2enc installed, omit both flags to use the same defaults as
archive-pdf-tools.

Options:

| option | meaning |
| --- | --- |
| `--encoder kakadu\|openjpeg\|grok\|java\|none` | JPEG2000 encoder; `java` is built in |
| `--kdu PATH` | `kdu_compress` binary (Kakadu); default: `kdu_compress` from PATH |
| `--jbig2 PATH` | `jbig2` (jbig2enc) binary; default: `jbig2` from PATH |
| `--no-jbig2` | don't use jbig2enc; use the mask fallback |
| `--mask-compression g4\|flate\|jbig2` | mask codec when JBIG2 is unavailable (default `g4`) |
| `--fg-slope N` / `--bg-slope N` | Kakadu slope values (44500 / 44250) |
| `--fg-rates R` / `--bg-rates R` | OpenJPEG/Grok compression ratios (750 / 500) |
| `--fg-bpp N` / `--bg-bpp N` | Java (JAI) encoder rates in bits/pixel (0.4 / 0.2) |
| `--bg-downsample N` | background downsample factor (3) |
| `--dpi N` | fallback DPI for the Sauvola window (300) |
| `--dump DIR` | write the MRC layers as PNG files |
| `--debug` | per-image timings |

With `--encoder none` the foreground/background are stored as Flate-compressed
8-bit images (usage fallback) instead of JPEG2000.

Other entry points:

* `com.xulihang.mrc4j.ComponentDump <image> <outDir> [dpi]` – dump the
  mask/foreground/background of a single image.

## Status

Working: PDF parsing, image extraction/replacement, MRC decomposition,
Sauvola thresholding, layer optimising, background downsampling, JBIG2 /
CCITT G4 / Kakadu / OpenJPEG / Grok / Java-JAI encoders, Flate fallbacks.

Fixed (2026-11-19):

* Replacement images are placed with the original image's CTM instead of a
  reconstructed flipped rectangle, so pages are no longer upside down.
* `ImageCodec.fromBufferedImage` now returns RGB for BGR/ABGR
  `BufferedImage`s (it previously swapped red and blue); the PDF layers
  therefore keep the original colours.
* The shade plugin merges `META-INF/services` ImageIO registrations, so the
  JPEG2000 reader/writer is available from the shaded jar as well.
* Rewritten page content streams drop the original `/FlateDecode` filter
  before the uncompressed tokens are written; `qpdf --check` and PDFBox now
  read the result without stream errors.
* Added a built-in `--encoder java` (JAI JPEG2000) so the tool runs without
  Kakadu/OpenJPEG/Grok.
* The mask now falls back to CCITT Group 4 (pure Java) instead of Flate when
  jbig2enc is unavailable.

In the page-6 test fixture used during the port (8.4 MB), the default
self-contained result is about **0.41 MB** with `--encoder java --no-jbig2`;
`--fg-bpp 0.25 --bg-bpp 0.1` brings it to about **0.29 MB** with slightly
softer artwork. The text/line art stays crisp and the page keeps its original
colours and orientation.
