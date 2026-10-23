package cloud.fhwang.chaewon_t.extract;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.TextPosition;

import cloud.fhwang.chaewon_t.common.AppProperties;
import cloud.fhwang.chaewon_t.extract.GlyphDecoder.ShapeGlyph;
import cloud.fhwang.chaewon_t.extract.PdfLayoutReader.PageGlyphs;
import cloud.fhwang.chaewon_t.template.PdfLayout;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 글자 모양 다수결(GlyphDecoder)의 정확도를 잰다. 정답이 있는 텍스트 PDF(samples/sample.pdf)를
 * "글자 정보가 없는 인쇄 PDF"처럼 다룬다: 모양 = 글꼴 + 글자 코드 + 크기, 글자는 모른다고 치고
 * 페이지 그림 OCR → 다수결로 정한 글자를 원래 글자와 한 글자씩 비교한다.
 *
 * 실행: ./gradlew glyphEvalProbe -Pfile=samples/sample.pdf[,쪽수]
 * OCR 결과는 probe-out/eval-cache/에 저장해 두고 다시 쓴다 (OcrLines·OCR 설정을 바꾸면 그 폴더를 지운다)
 */
public class GlyphEvalProbe {

	static final PdfLayout LAYOUT = new PdfLayout(297.5f, 60f, 800f, 240f);

	public static void main(String[] args) throws Exception {
		String[] a = args[0].split(",");
		Path pdf = Path.of(a[0]);
		Path cache = Path.of("probe-out/eval-cache");
		Files.createDirectories(cache);
		// 대량 OCR 설정 실험: -Ddpi=200 -Dtessdata=폴더 (기본: 300dpi, data/tessdata)
		AppProperties.Ocr d = AppProperties.Ocr.defaults();
		int dpi = Integer.getInteger("evalDpi", d.dpi());
		String td = System.getProperty("evalTessdata", "");
		AppProperties.Ocr settings = new AppProperties.Ocr(d.command(), td.isEmpty() ? d.tessdataDir() : Path.of(td),
				d.languages(), dpi, 4, d.pageTimeout());
		TesseractOcr ocr = new TesseractOcr(settings);
		TesseractOcr reread = new TesseractOcr(AppProperties.Ocr.defaults()); // 다시 읽기는 늘 정확한 설정
		String config = dpi + (td.isEmpty() ? "" : "-" + Path.of(td).getFileName());
		long ocrMillis = 0;
		JsonMapper json = JsonMapper.shared();
		try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
			int pages = a.length > 1 ? Integer.parseInt(a[1]) : doc.getNumberOfPages();
			Map<Integer, List<PdfLine>> ocrByPage = new LinkedHashMap<>();
			Map<Integer, List<ShapeGlyph>> shapesByPage = new LinkedHashMap<>();
			Map<Integer, List<String>> truthByPage = new LinkedHashMap<>();
			PDFRenderer renderer = new PDFRenderer(doc);
			for (int p = 1; p <= pages; p++) {
				PDPage page = doc.getPage(p - 1);
				List<UnderlineDetector.Segment> segments = UnderlineDetector.horizontalSegments(page);
				PdfLayout layout = PdfLayoutReader.pageLayout(page, segments, LAYOUT);
				File cached = cache.resolve(pdf.getFileName() + (config.equals("300") ? "" : "-" + config) + "-p" + p + ".json").toFile();
				List<PdfLine> lines;
				if (cached.exists()) {
					lines = json.readValue(cached, new TypeReference<List<PdfLine>>() { });
				} else {
					long t0 = System.nanoTime();
					lines = ocrPage(ocr, renderer, p, layout, segments, cache);
					ocrMillis += (System.nanoTime() - t0) / 1_000_000;
					json.writeValue(cached, lines);
				}
				ocrByPage.put(p, lines);
				PageGlyphs glyphs = PdfLayoutReader.glyphs(doc, p);
				List<ShapeGlyph> shapes = new ArrayList<>();
				List<String> truth = new ArrayList<>();
				for (TextPosition t : glyphs.glyphs()) {
					if (t.getY() <= layout.headerBottom() || t.getY() >= layout.footerTop()) {
						continue; // 머리글·꼬리글은 본문이 아니라 OCR 줄도 없다
					}
					// 글자 크기: 이 PDF는 getFontSizeInPt가 117처럼 엉뚱해(글자 행렬 배율) 글자 높이를 쓴다.
					// 실제 대상인 인쇄 PDF(Type3)는 getFontSizeInPt가 맞고(8pt) 높이가 엉터리다
					float size = t.getHeightDir();
					String key = t.getFont().getName() + "#" + t.getCharacterCodes()[0] + "#" + Math.round(size * 2);
					shapes.add(new ShapeGlyph(key, t.getUnicode().isBlank(), t.getX(), t.getY(), size,
							glyphs.bold().contains(t)));
					truth.add(t.getUnicode());
				}
				shapesByPage.put(p, shapes);
				truthByPage.put(p, truth);
				System.err.printf("\r페이지 %d/%d", p, pages);
			}
			System.err.println();

			// 실제 흐름과 같이: OCR한 쪽 줄을 ①~⑤ 보정한 뒤 표 모으기. -Pprogressive면 몇 쪽만 OCR한 것처럼(ShapeOcrPlan)
			Set<Integer> ocrPages = new java.util.TreeSet<>(ocrByPage.keySet());
			if (Boolean.getBoolean("progressive")) {
				ocrPages = new java.util.TreeSet<>(ShapeOcrPlan.firstBatch(shapesByPage, 4));
				while (true) {
					GlyphDecoder trial = ShapeOcrPlan.learn(shapesByPage, normalized(ocrByPage, ocrPages));
					List<Integer> more = ShapeOcrPlan.needFullOcr(trial, shapesByPage, ocrPages);
					if (more.isEmpty()) {
						break;
					}
					ocrPages.addAll(more);
				}
			}
			if (ocrMillis > 0) {
				System.out.printf("OCR 시간(설정 %s, 단 2개 동시): %d초%n", config, ocrMillis / 1000);
			}
			System.out.println("통째로 OCR한 쪽: " + ocrPages.size() + "/" + pages + " " + ocrPages);
			GlyphDecoder decoder = ShapeOcrPlan.learn(shapesByPage, normalized(ocrByPage, ocrPages));
			shapesByPage.forEach((p, s) -> decoder.learnRuns(s));
			decoder.settleRuns();
			long rareStart = System.nanoTime();
			if (!Boolean.getBoolean("noRare")) {
				new RareGlyphReader(reread, renderer, cache).improve(decoder, shapesByPage);
			}
			System.out.printf("다시 읽기 %d초%n", (System.nanoTime() - rareStart) / 1_000_000_000);
			report(decoder, shapesByPage, truthByPage);
		} finally {
			ocr.shutdown();
			reread.shutdown();
		}
	}

	/** 고른 쪽의 OCR 줄을 쪽 순서로 ①~⑤ 보정해 쪽별로 (실제 흐름과 같은 ShapeOcrPlan.normalized) */
	private static Map<Integer, List<PdfLine>> normalized(Map<Integer, List<PdfLine>> ocrByPage, Set<Integer> pages) {
		Map<Integer, List<PdfLine>> chosen = new java.util.TreeMap<>();
		pages.forEach(p -> chosen.put(p, ocrByPage.get(p)));
		return ShapeOcrPlan.normalized(chosen);
	}

	private static List<PdfLine> ocrPage(TesseractOcr ocr, PDFRenderer renderer, int p, PdfLayout layout,
			List<UnderlineDetector.Segment> segments, Path dir) throws Exception {
		float scale = 72f / ocr.dpi();
		BufferedImage page = renderer.renderImageWithDPI(p - 1, ocr.dpi(), ImageType.GRAY);
		int split = Math.round(layout.columnSplit() / scale);
		List<java.util.concurrent.Future<List<PdfLine>>> cols = new ArrayList<>();
		for (int col = 0; col < 2; col++) {
			BufferedImage img = col == 0 ? page.getSubimage(0, 0, split, page.getHeight())
					: page.getSubimage(split, 0, page.getWidth() - split, page.getHeight());
			Path file = dir.resolve("eval-" + p + "-" + col + ".png");
			ImageIO.write(img, "png", file.toFile());
			int c = col;
			cols.add(ocr.submit(() -> {
				List<PdfLine> l = OcrLines.toPdfLines(p, c, ocr.recognize(file, TesseractOcr.PSM_COLUMN), scale,
						c == 0 ? 0 : split, layout, segments);
				Files.deleteIfExists(file);
				return l;
			}));
		}
		List<PdfLine> lines = new ArrayList<>();
		for (var f : cols) {
			lines.addAll(f.get());
		}
		return lines;
	}

	private static void report(GlyphDecoder decoder, Map<Integer, List<ShapeGlyph>> shapes, Map<Integer, List<String>> truth) {
		Map<String, int[]> byKind = new TreeMap<>(); // [맞음, 틀림, 못 정함]
		Map<String, Integer> confusion = new HashMap<>();
		Map<String, Integer> shapeCount = new HashMap<>();
		shapes.values().forEach(list -> list.forEach(g -> shapeCount.merge(g.shape(), 1, Integer::sum)));
		int[] rare = new int[3];
		int[] flags = new int[3];
		Map<String, int[]> flagBuckets = new TreeMap<>(); // 표시한 글자: 표 모양별 [맞음, 틀림] // 확인 필요 표시: [표시했는데 맞음, 표시했고 틀림, 표시 안 했는데 틀림]
		for (int p : shapes.keySet()) {
			List<ShapeGlyph> s = shapes.get(p);
			List<String> t = truth.get(p);
			for (int i = 0; i < s.size(); i++) {
				if (s.get(i).blank()) {
					continue;
				}
				String want = t.get(i);
				GlyphDecoder.Decoded d = decoder.decode(s.get(i).shape());
				int r = d == null ? 2 : d.text().equals(want) ? 0 : 1;
				boolean flagged = d == null || d.uncertain();
				if (flagged) {
					flags[r == 0 ? 0 : 1]++;
					Map<String, Integer> v = decoder.votesOf(s.get(i).shape());
					int votes = v.values().stream().mapToInt(Integer::intValue).sum();
					int best = v.values().stream().mapToInt(Integer::intValue).max().orElse(0);
					String bucket = (d == null ? "못정함" : "표" + Math.min(votes, 5) + (best * 10 >= votes * 6 ? " 1등≥60%" : " 1등<60%"))
							+ " " + (d == null ? "" : kind(d.text()));
					flagBuckets.computeIfAbsent(bucket, k -> new int[2])[r == 0 ? 0 : 1]++;
				} else if (r != 0) {
					flags[2]++;
				}
				byKind.computeIfAbsent(kind(want), k -> new int[3])[r]++;
				if (shapeCount.get(s.get(i).shape()) <= 3) {
					rare[r]++;
				}
				if (r != 0) {
					confusion.merge(want + " → " + (d == null ? "(못 정함)" : d.text()) + "  표:" + decoder.votesOf(s.get(i).shape()),
							1, Integer::sum);
				}
			}
		}
		int[] total = new int[3];
		byKind.forEach((k, v) -> {
			int n = v[0] + v[1] + v[2];
			System.out.printf(Locale.ROOT, "%-8s 글자 %6d  맞음 %6.2f%%  틀림 %5d  못 정함 %5d%n", k, n, v[0] * 100.0 / n, v[1], v[2]);
			for (int j = 0; j < 3; j++) {
				total[j] += v[j];
			}
		});
		int n = total[0] + total[1] + total[2];
		System.out.printf(Locale.ROOT, "전체     글자 %6d  맞음 %6.2f%%  틀림 %5d  못 정함 %5d%n", n, total[0] * 100.0 / n, total[1], total[2]);
		System.out.printf(Locale.ROOT, "드문 모양(3번 이하) 글자: 맞음 %d 틀림 %d 못 정함 %d%n", rare[0], rare[1], rare[2]);
		System.out.printf(Locale.ROOT, "확인 필요 표시: %d자 (그중 실제로 틀린 것 %d자, 맞는데 표시 %d자), 표시 안 됐는데 틀린 것 %d자%n",
				flags[0] + flags[1], flags[1], flags[0], flags[2]);
		flagBuckets.forEach((k, v) -> System.out.printf("  표시 %-18s 맞음 %4d 틀림 %4d%n", k, v[0], v[1]));
		System.out.println("자주 틀린 것:");
		confusion.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(40)
				.forEach(e -> System.out.println("  " + e.getValue() + "  " + e.getKey()));
	}

	private static String kind(String c) {
		int cp = c.codePointAt(0);
		if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL) {
			return "한글";
		}
		if (cp < 128 && Character.isLetter(cp)) {
			return "영문";
		}
		if (cp < 128 && Character.isDigit(cp)) {
			return "숫자";
		}
		if (cp >= 0x2460 && cp <= 0x24FF) {
			return "동그라미";
		}
		return "기호";
	}
}
