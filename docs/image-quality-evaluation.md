# 图像质量评估与优化记录

本文说明 `feat/opencv` 分支的 MRC 分解是如何评估、定位问题和优化的。重点是**方法**：
怎样从"看起来不好"走到可复现的结论，以及为什么单看 PSNR 会误判。

测试样本：

| 样本 | 源文件 | 尺寸 | 源体积 |
| --- | --- | --- | --- |
| 表单 | `04-before-fill-in_form.jpg` | 2480×3508（300 dpi A4） | 703,182 B |
| 漫画 | `Fables.017.2003.Digital.Nahga-Empire.pdf-006.jpg` | 1919×2950 | 4,187,164 B |

对照用的 PDF（两个分支、两种质量档）：

- `--preset high`：fg 0.40 bpp、bg 0.20 bpp，两层全分辨率（1:1）。
- `--preset small`：fg 0.10 bpp、bg 0.08 bpp，两层都降采样 3×（低质量档）。
- 两个分支统一使用内置编码器：`--encoder java --no-jbig2`（JAI JPEG2000 + G4 mask），
  避免外部编码器差异干扰算法对比。

---

## 一、评估方法

只看最终 PDF 的 PSNR/SSIM 不够，因为它把"分解误差"、"编码误差"和"主观锐度"混在
一起。实际采用分层评估：

### 1. 体积与每层字节

- PDF 总大小；
- 用 `pypdf` 读出每个图像 XObject 和 SMask 的压缩后字节，分别对应背景 JPX、前景 JPX、
  G4 mask。这样可以判断体积差异来自哪一层（例如漫画 high 优化前后差 26 KB，几乎全部
  来自 mask：80,392 → 108,608 B，fg/bg 只是小幅变化）。

### 2. 渲染还原 + 全页 PSNR / SSIM

- Ghostscript 按目标分辨率渲染（本项目的 PDF 页面单位等于源像素，所以 72 dpi 渲染就是
  2480×3508 原生分辨率；GdPicture 样本是真实 A4 页面，需用 300 dpi 渲染到同尺寸）；
- 与原图比较 RGB PSNR（MSE）和灰度 SSIM（`skimage.metrics.structural_similarity`）。

### 3. 分解层重建（把算法误差和编码误差分开）

用 `--dump` 导出 mask / fg / bg，按掩膜重建合成图（`bg` 为底，`mask=1` 处取 `fg`），
不经过 JPEG2000/G4 编码，再与原图算 PSNR：

- 参考算法（主分支）的合成图与原图 **逐像素一致**（PSNR = 99 dB）。这是"无损分解"的
  定义：前景保留掩膜内原像素，背景保留掩膜外原像素。
- 优化前的 OpenCV 分支只有 35.3 dB（表单）/ 25.3 dB（漫画）——说明问题在分解本身，
  而不是编码。

### 4. 掩膜一致性与形态统计

- 与主分支 mask 的 IoU、各自多出的像素数；
- 连通域数量、面积分布（区分噪点、笔画、大面积墨区）；
- 掩膜边缘外侧一圈的原始灰度均值（判断掩膜是偏紧还是偏松）。

### 5. 掩膜内 / 掩膜外 PSNR

把渲染结果与原图的误差拆成"掩膜内（前景层负责）"和"掩膜外（背景层负责）"两部分。
优化前漫画 high 为 31.6 / 34.5 dB，主分支为 35.8 / 35.4 dB——立刻看出差距集中在
**掩膜内，也就是前景/墨迹层**，而不是背景。

### 6. 多尺度渲染 + 局部放大

- 72 dpi（1:1）、36 dpi（模拟适合屏幕）、25 dpi（缩略图）三种尺度；
- 同一区域 3×/8× 最近邻放大后人工检查；
- 用 `mean(|Laplacian|)`、梯度幅值作为锐度代理，但**只作参考**——它们解释不了光晕这种
  主观感受（见第五节）。

### 7. 结构级对照（GdPicture 样本）

用 `pypdf` 检查页面、XObject、Filter、宽高、SMask 尺寸和 Decode 参数；用 PDFBox 解码
各层，统计"背景层在文字处的像素值""前景层在文字区的填充"和掩膜分辨率。这比猜算法更直接。

---

## 二、优化前的问题定位

优化前（原始 OpenCV 分支）与主分支同设置对比：

| 样本 / 质量 | 分支 | 体积 | PSNR | SSIM |
| --- | --- | --- | --- | --- |
| 表单 high | main | 687.4 KB | 39.39 | 0.9888 |
| 表单 high | opencv | 684.8 KB | **33.25** | 0.9874 |
| 表单 small | main | 71.4 KB | 25.63 | 0.9617 |
| 表单 small | opencv | 68.8 KB | **23.65** | 0.9552 |
| 漫画 high | main | 525.4 KB | 35.46 | 0.9868 |
| 漫画 high | opencv | 499.0 KB | **24.06** | 0.9209 |
| 漫画 small | main | 121.1 KB | 25.05 | 0.9191 |
| 漫画 small | opencv | 93.6 KB | **19.39** | 0.7920 |

逐层数据定位出三个根因：

1. **噪声估计不同**。OpenCV 分支用 Laplacian 估计，两张图得到 σ≈9.4 / 13.4，于是每页都
   被预先模糊 σ≈1；主分支的小波（db2 DWT）估计得到 σ≈0，不模糊。掩膜边缘因此整体偏移，
   与主分支 mask 的 IoU 只有 0.92 / 0.87。
2. **背景层把掩膜膨胀了 1px**。原始抗锯齿边缘被替换成填充色，合成图从主分支的"无损"
   掉到 35.3 / 25.3 dB；渲染时黑线/文字周围出现亮晕。
3. **填充与去噪算法不同**。OpenCV 分支用归一化高斯近似 + 回退到带纹理的背景，噪声抢占
   墨迹码率；去噪用连通域面积 >2，而参考实现是 5×5 邻域规则。

## 三、采取的优化

1. **恢复参考的小波噪声估计**（[NoiseEstimator.java](../src/main/java/com/xulihang/mrc4j/NoiseEstimator.java)）。
2. **背景层在全分辨率下不再膨胀掩膜**，非墨迹像素（包括抗锯齿边）全部保留原值
   （低质量档的受控膨胀见第四节；[Optimiser.java](../src/main/java/com/xulihang/mrc4j/Optimiser.java)）。
3. **用参考的"辐射式"填充替换高斯近似**：掩膜窗口内被标记像素的均值 + 已计算的左上象限，
   整数除法语义与参考一致；用滑动列和 + 前缀和实现为 O(n)，比主分支朴素的
   O(n·window²) 更快。
4. **去噪改为参考的 5×5 邻域计数规则**（周围 24 个像素中至少 4 个被置位才保留），
   用 OpenCV `boxFilter` 计算邻域和。

优化后与主分支对比（同设置、同编码器）：

| 样本 / 质量 | main | 优化后 | 优化后体积 |
| --- | --- | --- | --- |
| 表单 high | 39.39 dB / 0.9888 | **39.39 dB / 0.9888** | 687.4 KB |
| 表单 small | 25.63 dB / 0.9617 | 25.67 dB / 0.9618 | 71.2 KB |
| 漫画 high | 35.46 dB / 0.9868 | **35.45 dB / 0.9868** | 525.3 KB |
| 漫画 small | 25.05 dB / 0.9191 | 25.06 dB / 0.9192 | 121.1 KB |

其他验证：

- 分解合成图恢复为逐像素一致（PSNR 99 dB，掩膜内/外都是 exact），与主分支相同；
- mask 与主分支 IoU 提升到 0.9999（表单）/ 0.9996（漫画）；
- 每层字节基本一致（漫画 high：fg 283,078 vs 283,080 B，mask 108,608 vs 108,520 B）；
- 速度更快：漫画 high 约 6–9 s（主分支 10.8 s），表单 high 约 8–10 s（主分支 13.4 s）；
- `high` 档在加入第四节的光晕处理前后哈希级一致（该选项只影响降采样档）。

> 注：上表中 small 档的数据是"与主分支对齐"这一步的结果，此时尚未处理第四节描述的
> 低质量档光晕；处理后的数字见第四节。

## 四、低质量档的光晕与 GdPicture 对照

优化后 small 档与主分支一致，但仍有一圈浅色光晕。对
`gdpicture-04-after-preserved-smoothing-fill-in_form.pdf` 的结构分析给出了原因：

| 项目 | GdPicture 样本 |
| --- | --- |
| 颜色层 | 827×1169（约 100 dpi），bg 10.6 KB + fg 5.7 KB |
| 掩膜 | 4960×7016（600 dpi）1-bit JBIG2，`/Decode [1,0]` |
| 背景在文字处 | RGB 均值 [254.1, 254.4, 254.7]，原图灰边处 100% > 240，即**纯纸白** |
| 前景在文字区 | 均值 [56.1, 90.1, 85.0]，几乎没有白色，即**平坦墨色填充** |
| 同一分辨率对比 | 68.0 KB，PSNR 23.37 dB，SSIM 0.9528 |

即它们**把文字和抗锯齿过渡完全从背景层抹掉**，可见边缘就是 1-bit 掩膜的硬边；颜色层
只有约 100 dpi，而掩膜是 600 dpi，所以光晕没有产生的土壤。代价是放弃抗锯齿：它的 PSNR
比我们保留灰边时更低（23.4 vs 25.7），但观感更"清晰"。

照此在**降采样档位**（`bgDownsample > 1`）对背景填充使用膨胀 `bg-downsample - 1` 像素的
掩膜，让背景直到文字边缘都是纸白；SMask 和前景保持不变。全分辨率 `high` 档半径仍为 0，
输出与之前哈希级一致（分解仍逐像素无损）。

| 表单 small | 体积 | PSNR | SSIM |
| --- | --- | --- | --- |
| 优化前（有光晕） | 71.2 KB | 25.67 | 0.9618 |
| 现在（无光晕） | 71.4 KB | 25.41 | 0.9627 |
| GdPicture 样本 | 68.0 KB | 23.37 | 0.9528 |

漫画 small 同样变清晰，PSNR 由 25.06 降到 23.12（去掉抗锯齿的必然代价）。可用
`--bg-dilate 0` 恢复旧行为，`--bg-dilate N` 指定半径。

## 五、`high` 为什么看起来不如 `small` 锐利

这是"保真"与"硬边"的冲突，不是 high 丢了细节。在 36 dpi（适合屏幕）渲染下：

| 版本 | 与原图 SSIM | 锐度 `mean(|Laplacian|)` |
| --- | --- | --- |
| 原始 JPG | 1.0000 | 33.20 |
| high | 0.9657 | 32.22 |
| small | 0.9471 | 30.38 |

- `high` 完整保留原图的 1–2px 抗锯齿灰边（PSNR 39.4 dB，几乎无损），人眼把这种宽而缓的
  灰度过渡读作"不够锐利"；同时背景层只有 0.2 bpp，灰边的高频细节本身也会被 JPEG2000
  柔和化。
- `small` 删掉了灰边，边缘由二值掩膜直接决定，对比度突变，主观上更锐利，但真实边缘灰度
  和细节更少。
- 若希望 high 也呈现这种硬边观感，可用 `--preset high --bg-dilate 1`：体积不变（约 687 KB），
  观感接近 small，代价是 PSNR 降到约 23.4 dB（与 GdPicture 同一水平）。默认保持忠实原图。

## 六、经验总结

1. **先分离误差来源**：分解层重建（不编码）能立刻判断问题在算法还是编码。
2. **掩膜内/外 PSNR** 是定位 MRC 问题的利器，比整页指标精确。
3. **PSNR/SSIM 不是锐度指标**。硬边会提升主观锐度但降低保真度；反之忠实保留抗锯齿会被
   看成"模糊"。文档里同时给出保真指标和视觉/锐度代理，避免单一指标误导。
4. **掩膜的一致性（IoU）和形态统计**能快速暴露阈值/去噪/模糊参数的偏差。
5. **结构对照**（GdPicture 的层尺寸、掩膜分辨率、背景像素统计）比猜测算法更可靠。

## 七、复现

```bash
# 构建
mvn -DskipTests package

# 生成 high / small
java -cp target/mrc4j-1.0-SNAPSHOT.jar com.xulihang.mrc4j.PdfCompressor \
    gdp-base.pdf out/form-high.pdf --encoder java --no-jbig2 --preset high
java -cp target/mrc4j-1.0-SNAPSHOT.jar com.xulihang.mrc4j.PdfCompressor \
    gdp-base.pdf out/form-small.pdf --encoder java --no-jbig2 --preset small

# 渲染（本项目页面单位=源像素，72 dpi 即原生分辨率）
gswin64c -q -dNOPAUSE -dBATCH -sDEVICE=png16m -r72 \
    -dTextAlphaBits=4 -dGraphicsAlphaBits=4 -o out/form-high.png out/form-high.pdf

# 导出各层（mask / fg / bg，另存 bg.jp2 / fg.jp2）
java -cp target/mrc4j-1.0-SNAPSHOT.jar com.xulihang.mrc4j.PdfCompressor \
    gdp-base.pdf out/form-high.pdf --encoder java --no-jbig2 --preset high --dump out/dump
```

指标计算（Python）：

```python
import numpy as np
from PIL import Image
from skimage.metrics import structural_similarity as ssim

orig = np.asarray(Image.open("04-before-fill-in_form.jpg").convert("RGB"))
rend = np.asarray(Image.open("out/form-high.png").convert("RGB"))
mse = np.mean((orig.astype(float) - rend.astype(float)) ** 2)
psnr = 10 * np.log10(255.0 ** 2 / mse)
s = ssim(np.asarray(Image.open("04-before-fill-in_form.jpg").convert("L")),
         np.asarray(Image.fromarray(rend).convert("L")), data_range=255)

# 分解层重建（不经编码）
m = np.asarray(Image.open("out/dump/mrc0-mask.png").convert("L")) > 127
fg = np.asarray(Image.open("out/dump/mrc0-fg.png").convert("RGB"))
bg = np.asarray(Image.open("out/dump/mrc0-bg.png").convert("RGB"))
comp = bg.copy()
comp[m] = fg[m]          # 无损分解时应与原图逐像素一致
```

说明：以上指标使用内置 JAI JPEG2000 + G4 mask；换成 Kakadu/OpenJPEG/JBIG2 时绝对数值会
变化，但同一编码器下的相对比较和优化结论不变。

## 五、内置 JBIG2 mask（2026-11-25）

`com.xulihang.mrc4j.jbig2` 是 jbig2enc generic-region 编码器的纯 Java 移植
（无损），现为默认掩膜编码器，不再需要外部 `jbig2`。对 `--dump` 导出的两个
真实掩膜，用同一 G4 参数（CCITTFaxDecode，K=-1）作对照：

| 掩膜 | 尺寸 | G4 | 内置 JBIG2 | 比例 |
| --- | --- | --- | --- | --- |
| `out/tmpdump-comic-base/mrc0-mask.png` | 1919×2950 | 108,500 B | 86,832 B | 80% |
| `out/tmpdump-gdp-base/mrc0-mask.png` | 2480×3508 | 49,907 B | 37,833 B | 76% |

往返解码（jbig2-imageio）逐像素一致；`Jbig2RoundTripTest` 覆盖 1×1 到
320×240 的随机/实心/棋盘图以及上述真实掩膜，`Jbig2GoldenTest` 对照
jbig2enc `tests/byte_regression.cc` 的逐字节参考向量。

注意：JBIG2 的前景位 1 在 PDF DeviceGray 中解码为 0（黑），而 SMask 需要
墨迹为白色（不透明），因此 JBIG2 SMask 必须带 `/Decode [1 0]`
（`MrcImages` 已设置）。缺少它时 jbig2 掩膜会反相；GdPicture 样本同样使用
该 Decode 数组。`Jbig2MaskTest` 通过实际渲染页面验证这一点。

