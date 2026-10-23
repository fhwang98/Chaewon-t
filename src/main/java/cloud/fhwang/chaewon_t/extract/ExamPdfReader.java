package cloud.fhwang.chaewon_t.extract;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import cloud.fhwang.chaewon_t.common.ConversionException;
import cloud.fhwang.chaewon_t.common.ErrorType;
import cloud.fhwang.chaewon_t.extract.PdfLayoutReader.PageGlyphs;
import cloud.fhwang.chaewon_t.template.PdfLayout;

/**
 * PDF 기출문제 → 시험지 구조. 머리말 시험 이름 기본값은 1쪽 머리글을 줄인 것.
 *
 * 페이지마다 PDF 글자 정보를 쓸 수 있는지 보고, 못 쓰면(스캔본·프린터로 인쇄한 PDF) 그 페이지만 OCR로 읽는다.
 * OCR 페이지는 300dpi 그림으로 바꿔 단별로 잘라 Tesseract에 넘긴다(단을 나눠 주지 않으면 두 단이 한 줄로 섞인다).
 * 그림 그리기·밑줄 찾기는 PDFBox라 이 스레드에서 차례로 하고(PDDocument는 여러 스레드에서 쓰면 안 된다),
 * OCR(외부 프로세스)만 동시에 돌린다.
 */
@Component
public class ExamPdfReader {

	/** 머리글 안의 "(26 2학기 중간)" 같은 연도·학기 */
	private static final Pattern TERM = Pattern.compile("\\((\\d{2})\\s*(\\d학기[^)]*)\\)");

	/**
	 * 읽기 진행 상황. 여러 스레드에서 불리므로 값이 순서대로 오지 않을 수 있다.
	 *
	 * @param donePages 끝난 쪽 (OCR하지 않아도 되는 쪽 포함)
	 * @param ocrPages  OCR할 쪽 (글자 모양 페이지는 정해지는 대로 늘어난다)
	 * @param ocrDone   OCR이 끝난 쪽
	 */
	@FunctionalInterface
	public interface Progress {
		void update(int donePages, int totalPages, int ocrPages, int ocrDone);
	}

	/** 읽은 결과와 OCR로 읽은 페이지 수 */
	public record Result(ExamDocument document, int pages, int ocrPages) {
	}

	private final TesseractOcr ocr;

	public ExamPdfReader(TesseractOcr ocr) {
		this.ocr = ocr;
	}

	/**
	 * @param workDir OCR용 페이지 그림을 잠깐 둘 폴더 (작업 폴더)
	 */
	public Result read(Path pdf, PdfLayout layout, Path workDir, Progress progress) {
		try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
			int total = doc.getNumberOfPages();
			PDFRenderer renderer = new PDFRenderer(doc);
			List<Future<List<PdfLine>>> pages = new ArrayList<>();
			Set<Integer> ocrPages = new HashSet<>();
			Counts counts = new Counts(total, progress);
			Future<String> title = CompletableFuture.completedFuture("");
			GlyphDecoder decoder = new GlyphDecoder();
			Map<Integer, ShapePage> shapePages = new HashMap<>(); // 글자 모양으로 다시 읽을 페이지
			counts.report(); // 쪽수는 바로 알린다 (첫 OCR 페이지가 끝나기까지 10초 넘게 걸린다)
			try {
				for (int pageNo = 1; pageNo <= total; pageNo++) {
					if (Thread.currentThread().isInterrupted()) { // 취소: 남은 페이지를 그리지 않는다
						throw new InterruptedException();
					}
					PDPage pdfPage = doc.getPage(pageNo - 1);
					PageGlyphs glyphs = PdfLayoutReader.glyphs(doc, pageNo);
					List<UnderlineDetector.Segment> segments = UnderlineDetector.horizontalSegments(pdfPage);
					PdfLayout pageLayout = PdfLayoutReader.pageLayout(pdfPage, segments, layout);
					if (!glyphs.needsOcr()) {
						pages.add(CompletableFuture.completedFuture(
								PdfLayoutReader.textLines(pageNo, glyphs, segments, pageLayout)));
						if (pageNo == 1) {
							title = CompletableFuture.completedFuture(PdfLayoutReader.headerText(glyphs, pageLayout));
						}
						counts.pageDone();
						continue;
					}
					ocrPages.add(pageNo);
					if (pageNo == 1) {
						Path header = renderHeader(renderer, pageLayout, workDir);
						int height = Math.max(1, Math.round(pageLayout.headerBottom() / (72f / ocr.dpi())));
						title = ocr.submit(() -> OcrLines.headerText(recognizeAndDelete(header, TesseractOcr.PSM_BLOCK), height));
					}
					if (GlyphDecoder.decodable(glyphs)) {
						// 글자 모양 페이지는 여기서 OCR하지 않는다: 다 모은 뒤 필요한 쪽만 OCR한다 (ocrShapePages)
						PageGlyphs all = PdfLayoutReader.glyphs(doc, pageNo, true);
						shapePages.put(pageNo, new ShapePage(decoder.shapes(all), pageLayout, segments));
						continue;
					}
					pages.add(ocrPage(renderer, pageNo, pageLayout, segments, workDir, counts));
				}
				Map<Integer, List<PdfLine>> byPage = new java.util.TreeMap<>();
				for (Future<List<PdfLine>> page : pages) {
					List<PdfLine> pageLines = page.get();
					if (!pageLines.isEmpty()) {
						byPage.computeIfAbsent(pageLines.get(0).page(), k -> new ArrayList<>()).addAll(pageLines);
					}
				}
				Set<Integer> ocrLinePages = new HashSet<>(ocrPages);
				if (!shapePages.isEmpty()) {
					Map<Integer, List<PdfLine>> shapeOcr = ocrShapePages(shapePages, renderer, workDir, counts);
					byPage.putAll(shapeOcr);
					ocrLinePages.removeIf(p -> shapePages.containsKey(p) && !shapeOcr.containsKey(p));
				}
				List<PdfLine> lines = new ArrayList<>();
				byPage.values().forEach(lines::addAll);
				if (!ocrPages.isEmpty()) {
					lines = OcrMarks.normalize(lines, ocrLinePages);
				}
				String headerTitle = title.get();
				if (!shapePages.isEmpty()) {
					lines = decodeShapes(lines, shapePages, decoder, new RareGlyphReader(ocr, renderer, workDir));
					if (shapePages.containsKey(1)) {
						// 머리글에만 나오는 글자(년·월 등)는 표가 없을 수 있다. 그러면 OCR로 읽은 머리글을 그대로 쓴다
						ShapePage first = shapePages.get(1);
						String decoded = decoder.headerText(first.glyphs(), first.layout());
						if (decoded != null) {
							headerTitle = decoded;
						}
					}
				}
				ExamDocument document = ExamParser.parse(lines, shortTitle(headerTitle));
				if (!ocrPages.isEmpty()) {
					document = KnownPrompts.fix(document);
				}
				document = KnownPrompts.fixSpacing(document);
				return new Result(document, total, ocrPages.size());
			} catch (ExecutionException e) {
				throw unwrap(e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new ConversionException(ErrorType.EXTRACTION_FAILED, "PDF 읽기가 중단되었습니다.", e);
			} finally {
				pages.forEach(f -> f.cancel(true)); // 실패했을 때 남은 OCR을 멈춘다 (끝난 것에는 영향 없음)
				title.cancel(true);
			}
		} catch (ClosedByInterruptException e) {
			// 서버 종료·재시작으로 읽던 파일이 닫혔다. PDF 문제가 아니다
			throw new ConversionException(ErrorType.EXTRACTION_FAILED, "PDF 읽기가 중단되었습니다. PDF를 다시 올려 주세요.", e);
		} catch (IOException e) {
			throw new ConversionException(ErrorType.EXTRACTION_FAILED, "PDF에서 문항을 읽지 못했습니다.", e);
		}
	}

	/** 글자 모양으로 다시 읽을 페이지의 글자들과 배치값 */
	private record ShapePage(List<GlyphDecoder.ShapeGlyph> glyphs, PdfLayout layout,
			List<UnderlineDetector.Segment> segments) {
	}

	/**
	 * 글자 모양 페이지: 모든 페이지의 OCR 결과로 모양별 글자 표를 먼저 다 모은 뒤(문서 전체 다수결),
	 * 그 페이지 줄을 OCR 줄 대신 PDF 글자 자리로 다시 짠 줄로 바꾼다. 페이지 순서는 그대로
	 */
	private static List<PdfLine> decodeShapes(List<PdfLine> lines, Map<Integer, ShapePage> shapePages,
			GlyphDecoder decoder, RareGlyphReader rare) throws IOException {
		Map<Integer, List<PdfLine>> byPage = new LinkedHashMap<>();
		for (PdfLine line : lines) {
			byPage.computeIfAbsent(line.page(), k -> new ArrayList<>()).add(line);
		}
		shapePages.forEach((page, sp) -> decoder.learn(sp.glyphs(), byPage.getOrDefault(page, List.of())));
		shapePages.forEach((page, sp) -> decoder.learnUnknown(sp.glyphs(), byPage.getOrDefault(page, List.of())));
		shapePages.forEach((page, sp) -> decoder.learnRuns(sp.glyphs()));
		decoder.settleRuns();
		Map<Integer, List<GlyphDecoder.ShapeGlyph>> glyphs = new LinkedHashMap<>();
		shapePages.forEach((page, sp) -> glyphs.put(page, sp.glyphs()));
		rare.improve(decoder, glyphs);
		List<PdfLine> result = new ArrayList<>(lines.size());
		List<Integer> order = new ArrayList<>(byPage.keySet());
		shapePages.keySet().stream().filter(p -> !byPage.containsKey(p)).forEach(order::add); // OCR 줄이 하나도 없던 페이지
		order.sort(Integer::compare);
		for (int page : order) {
			ShapePage sp = shapePages.get(page);
			List<PdfLine> ocrLines = byPage.getOrDefault(page, List.of());
			result.addAll(sp == null ? ocrLines : decoder.lines(page, sp.glyphs(), sp.layout(), sp.segments(), ocrLines));
		}
		return result;
	}

	/** OCR 한 쪽: 그림은 이 스레드에서 그리고(PDDocument는 한 스레드에서만), OCR은 풀에서 */
	private Future<List<PdfLine>> ocrPage(PDFRenderer renderer, int pageNo, PdfLayout layout,
			List<UnderlineDetector.Segment> segments, Path workDir, Counts counts) throws IOException {
		counts.planOcr(1);
		PageImages images = render(renderer, pageNo, layout, workDir);
		return ocr.submit(() -> {
			List<PdfLine> lines = ocrLines(pageNo, images, layout, segments);
			counts.ocrDone();
			return lines;
		});
	}

	/**
	 * 글자 모양 페이지 중 필요한 쪽만 통째로 OCR한다 (ShapeOcrPlan). 처음 보는 모양이 많은 쪽부터 한 묶음,
	 * 그 결과로 아직 못 정한 글자가 많은 쪽이 있으면 다음 묶음. 나머지 쪽은 OCR하지 않는다
	 * (sample.pdf 측정: 37쪽 중 18쪽만 OCR하고 정확도는 전부 OCR한 것과 같았다)
	 */
	private Map<Integer, List<PdfLine>> ocrShapePages(Map<Integer, ShapePage> shapePages, PDFRenderer renderer,
			Path workDir, Counts counts) throws IOException, InterruptedException, ExecutionException {
		Map<Integer, List<GlyphDecoder.ShapeGlyph>> glyphs = new java.util.TreeMap<>();
		shapePages.forEach((p, sp) -> glyphs.put(p, sp.glyphs()));
		Map<Integer, List<PdfLine>> result = new java.util.TreeMap<>();
		List<Integer> batch = ShapeOcrPlan.firstBatch(glyphs, Math.max(4, ocr.threads()));
		while (!batch.isEmpty()) {
			Map<Integer, Future<List<PdfLine>>> futures = new java.util.LinkedHashMap<>();
			for (int p : batch) {
				if (Thread.currentThread().isInterrupted()) {
					throw new InterruptedException();
				}
				ShapePage sp = shapePages.get(p);
				futures.put(p, ocrPage(renderer, p, sp.layout(), sp.segments(), workDir, counts));
			}
			try {
				for (Map.Entry<Integer, Future<List<PdfLine>>> f : futures.entrySet()) {
					result.put(f.getKey(), f.getValue().get());
				}
			} finally {
				futures.values().forEach(f -> f.cancel(true));
			}
			for (int p : batch) {
				counts.pageDone();
			}
			GlyphDecoder trial = ShapeOcrPlan.learn(glyphs, ShapeOcrPlan.normalized(result));
			batch = ShapeOcrPlan.needFullOcr(trial, glyphs, result.keySet());
		}
		for (int i = 0; i < shapePages.size() - result.size(); i++) {
			counts.pageDone(); // OCR하지 않아도 되는 쪽
		}
		return result;
	}

	/** 진행 수 세기 (OCR 스레드에서도 부른다) */
	private static final class Counts {
		private final int total;
		private final Progress progress;
		private final AtomicInteger done = new AtomicInteger();
		private final AtomicInteger ocrPlanned = new AtomicInteger();
		private final AtomicInteger ocrDone = new AtomicInteger();

		Counts(int total, Progress progress) {
			this.total = total;
			this.progress = progress;
		}

		void pageDone() {
			done.incrementAndGet();
			report();
		}

		void planOcr(int pages) {
			ocrPlanned.addAndGet(pages);
			report();
		}

		void ocrDone() {
			ocrDone.incrementAndGet();
			report();
		}

		void report() {
			progress.update(done.get(), total, ocrPlanned.get(), ocrDone.get());
		}
	}

	/** 1쪽 머리글 구역만 그린 그림 (시험 이름 OCR용) */
	private Path renderHeader(PDFRenderer renderer, PdfLayout layout, Path workDir) throws IOException {
		float scale = 72f / ocr.dpi();
		BufferedImage page = renderer.renderImageWithDPI(0, ocr.dpi(), ImageType.GRAY);
		int headerBottom = Math.max(1, Math.min(page.getHeight(), Math.round(layout.headerBottom() / scale)));
		return write(page.getSubimage(0, 0, page.getWidth(), headerBottom), workDir, 1, "header");
	}

	/** OCR 한 페이지 = 왼쪽 단·오른쪽 단 그림 */
	private record PageImages(Path left, Path right, float splitPx, float scale) {
	}

	/**
	 * 단 그림은 페이지 위아래를 자르지 않고 통째로 쓴다. 머리글 경계(headerBottom)에서 자르면
	 * 그 바로 아래 문항 머리 줄의 윗부분이 잘려 못 읽는다(sample2 2쪽 6번). 머리글·꼬리글 줄은 OCR 뒤에 기준선 y로 뺀다
	 */
	private PageImages render(PDFRenderer renderer, int pageNo, PdfLayout layout, Path workDir) throws IOException {
		float scale = 72f / ocr.dpi(); // px → pt
		BufferedImage page = renderer.renderImageWithDPI(pageNo - 1, ocr.dpi(), ImageType.GRAY);
		int split = Math.min(page.getWidth(), Math.round(layout.columnSplit() / scale));
		Path left = write(page.getSubimage(0, 0, split, page.getHeight()), workDir, pageNo, "left");
		Path right = write(page.getSubimage(split, 0, page.getWidth() - split, page.getHeight()), workDir, pageNo, "right");
		return new PageImages(left, right, split, scale);
	}

	private static Path write(BufferedImage image, Path dir, int pageNo, String part) throws IOException {
		Path file = dir.resolve("ocr-" + pageNo + "-" + part + ".png");
		ImageIO.write(image, "png", file.toFile());
		return file;
	}

	private List<PdfLine> ocrLines(int pageNo, PageImages images, PdfLayout layout,
			List<UnderlineDetector.Segment> segments) {
		List<PdfLine> lines = new ArrayList<>();
		lines.addAll(OcrLines.toPdfLines(pageNo, 0, recognizeAndDelete(images.left(), TesseractOcr.PSM_COLUMN),
				images.scale(), 0, layout, segments));
		lines.addAll(OcrLines.toPdfLines(pageNo, 1, recognizeAndDelete(images.right(), TesseractOcr.PSM_COLUMN),
				images.scale(), images.splitPx(), layout, segments));
		return lines;
	}

	private List<HocrParser.OcrLine> recognizeAndDelete(Path image, int psm) {
		try {
			return ocr.recognize(image, psm);
		} finally {
			try {
				Files.deleteIfExists(image);
			} catch (IOException e) {
				// 작업 폴더는 보관 시간이 지나면 통째로 지워진다
			}
		}
	}

	private static RuntimeException unwrap(ExecutionException e) {
		if (e.getCause() instanceof RuntimeException r) {
			return r;
		}
		return new ConversionException(ErrorType.EXTRACTION_FAILED, "PDF에서 문항을 읽지 못했습니다.", e.getCause());
	}

	/**
	 * 머리말은 한 줄이어야 한다 (길면 줄이 바뀌어 머리말 선이 본문을 침범한다).
	 * "가나고1 교과서 … (26 2학기 중간) 기출문제" → "26년 가나고1 (2학기 중간)". 모양이 다르면 그대로 둔다
	 */
	static String shortTitle(String pdfTitle) {
		String title = pdfTitle.strip();
		Matcher term = TERM.matcher(title);
		if (title.isEmpty() || !term.find()) {
			return title;
		}
		String school = title.split("\\s+")[0];
		return term.group(1) + "년 " + school + " (" + term.group(2).strip() + ")";
	}
}
