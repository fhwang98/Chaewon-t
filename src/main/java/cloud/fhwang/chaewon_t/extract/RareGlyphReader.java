package cloud.fhwang.chaewon_t.extract;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.imageio.ImageIO;

import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import cloud.fhwang.chaewon_t.extract.GlyphDecoder.ShapeGlyph;
import cloud.fhwang.chaewon_t.extract.HocrParser.OcrLine;
import cloud.fhwang.chaewon_t.extract.HocrParser.OcrWord;

/**
 * 네 번째 표 모으기: 자신 없는 드문 모양만 다시 읽는다.
 *
 * 다수결은 자주 나오는 글자에 강하지만, 한두 번 나오는 한글 음절은 OCR이 한 번 틀리면 그대로 틀린다
 * (sample.pdf 측정: 남은 오류 39자 중 34자가 한글. "좋"→"£", "싸"→"M"처럼 한글 단어 안에서 영문·기호로 읽힘).
 * 그런 모양이 든 단어만 600dpi로 잘라 한 장에 모아 다시 OCR한다. 앞뒤가 한글인 단어는 한국어 모델만 써서
 * 영문·기호로 읽힐 여지를 없앤다. 다시 읽은 표는 2표로 센다 (더 크고 깨끗한 그림이라).
 * 두 번 다시 읽어 둘 다 같은 글자면 그 글자로 확정한다: "(B)"·"(C)"처럼 300dpi 표가 반반 갈린 모양
 * (sample.pdf: B 49 / 8 51표, 몇 쪽만 OCR하면 C 15 / 0 16표)
 */
final class RareGlyphReader {

	private static final float DPI = 600;
	private static final int MAX_SHAPES = 1500;
	private static final int PER_SHAPE = 2;
	/** 자주 나오는(20번 이상) 모양은 단어를 더 많이 다시 읽어 확정할 근거를 모은다 */
	private static final int PER_FREQUENT_SHAPE = 4;
	private static final int FREQUENT = 20;
	private static final int MAX_WORD = 14;
	private static final int WEIGHT = 2;
	/** 한글 옆에 붙어도 이상하지 않은 문장 부호 */
	private static final String PUNCTUATION = ".,?!:;'\"’‘“”)(][}{-~…·";
	private static final int GAP = 20;
	private static final int MAX_SHEET_HEIGHT = 25_000;

	private final TesseractOcr ocr;
	private final PDFRenderer renderer;
	private final Path workDir;

	RareGlyphReader(TesseractOcr ocr, PDFRenderer renderer, Path workDir) {
		this.ocr = ocr;
		this.renderer = renderer;
		this.workDir = workDir;
	}

	/** 한 단어 자리: 쪽, 그 줄의 글자들, 단어 범위 [from, to) */
	private record Word(int page, List<ShapeGlyph> row, int from, int to, boolean hangul) {
	}

	/** 잘라 붙인 단어 그림 하나: 합친 그림 안의 세로 범위 */
	private record Placed(Word word, int top, int bottom) {
	}

	void improve(GlyphDecoder decoder, Map<Integer, List<ShapeGlyph>> pages) throws IOException {
		// 미심쩍은 모양마다 단어 몇 개. 많이 나오는 모양부터 고친다 (몇 쪽만 OCR하면 미심쩍은 모양이 수백 개라,
		// 쪽 순서로 자르면 "(C)"처럼 자주 나오는 모양이 빠졌다)
		Map<String, List<Word>> candidates = new HashMap<>();
		Map<String, Integer> occurrences = new HashMap<>();
		Map<String, Integer> shapeCount = new HashMap<>();
		pages.values().forEach(gs -> gs.forEach(g -> shapeCount.merge(g.shape(), 1, Integer::sum)));
		for (Map.Entry<Integer, List<ShapeGlyph>> e : pages.entrySet()) {
			for (List<ShapeGlyph> row : GlyphDecoder.rows(e.getValue())) {
				for (int i = 0; i < row.size(); i++) {
					ShapeGlyph g = row.get(i);
					if (g.blank() || !suspicious(decoder, row, i)) {
						continue;
					}
					occurrences.merge(g.shape(), 1, Integer::sum);
					List<Word> words = candidates.computeIfAbsent(g.shape(), k -> new ArrayList<>());
					int limit = shapeCount.get(g.shape()) >= FREQUENT ? PER_FREQUENT_SHAPE : PER_SHAPE;
					if (words.size() < limit) {
						words.add(word(decoder, e.getKey(), row, i));
					}
				}
			}
		}
		Map<String, List<Word>> byShape = new HashMap<>();
		candidates.keySet().stream()
				.sorted(Comparator.comparingInt((String shape) -> occurrences.get(shape)).reversed())
				.limit(MAX_SHAPES)
				.forEach(shape -> byShape.put(shape, candidates.get(shape)));
		if (byShape.isEmpty()) {
			return;
		}
		Set<String> unsettled = new HashSet<>();
		byShape.keySet().forEach(shape -> {
			GlyphDecoder.Decoded d = decoder.decode(shape);
			if (d == null || d.uncertain()) {
				unsettled.add(shape);
			}
		});
		List<Word> hangul = new ArrayList<>();
		List<Word> other = new ArrayList<>();
		byShape.values().forEach(ws -> ws.forEach(w -> (w.hangul() ? hangul : other).add(w)));
		// 묶음 그림을 여러 스레드에서 읽어 함께 모으므로 동시 접근에 안전한 지도 (해독기는 이 동안 읽기만 한다)
		Map<String, Map<String, Integer>> found = new ConcurrentHashMap<>();
		read(hangul, "kor", decoder, found);
		read(other, null, decoder, found);
		found.forEach((shape, counts) -> {
			// 확정은 다시 읽기 전에 표가 갈렸거나 못 정한 모양만. 이미 자신 있게 정한 모양을 덮으면 안 된다
			// (한글 뒤 마침표가 다시 읽기에서 "’"로 읽혀 마침표 1,272자가 바뀐 적이 있다)
			if (counts.size() == 1 && counts.values().iterator().next() >= 2 && unsettled.contains(shape)) {
				decoder.confirm(shape, counts.keySet().iterator().next());
			} else {
				counts.forEach((text, n) -> decoder.addVotes(shape, text, n * WEIGHT));
			}
		});
	}

	/**
	 * 다시 읽을 글자: 정해지지 않은 모양, 한글이 아닌 글자로 정해졌는데 자신 없거나 앞뒤·표에 한글이 있는 모양.
	 * 한글로 정해진 모양은 자신 없어도 다시 읽지 않는다: 다시 읽기가 "예"→"네", "취"→"수"처럼 비슷한 음절로 바꿔
	 * 오히려 틀렸다 (sample.pdf 측정)
	 */
	private static boolean suspicious(GlyphDecoder decoder, List<ShapeGlyph> row, int i) {
		String shape = row.get(i).shape();
		GlyphDecoder.Decoded d = decoder.decode(shape);
		if (d == null) {
			return true;
		}
		if (isHangul(d.text())) {
			return false;
		}
		if (!d.uncertain() && PUNCTUATION.indexOf(d.text()) >= 0) {
			return false; // 한글 뒤 마침표·쉼표 등은 흔하다
		}
		return d.uncertain() || neighborHangul(decoder, row, i, -1) || neighborHangul(decoder, row, i, 1)
				|| decoder.votesOf(shape).keySet().stream().anyMatch(RareGlyphReader::isHangul);
	}

	private static boolean neighborHangul(GlyphDecoder decoder, List<ShapeGlyph> row, int i, int step) {
		int j = i + step;
		if (j < 0 || j >= row.size() || row.get(j).blank()) {
			return false;
		}
		GlyphDecoder.Decoded d = decoder.decode(row.get(j).shape());
		return d != null && isHangul(d.text());
	}

	private static boolean isHangul(String text) {
		return !text.isEmpty() && Character.UnicodeScript.of(text.codePointAt(0)) == Character.UnicodeScript.HANGUL;
	}

	/** i가 든 단어: 공백 모양이나 넓은 틈 전까지 (너무 길면 i 둘레만) */
	private static Word word(GlyphDecoder decoder, int page, List<ShapeGlyph> row, int i) {
		int from = i;
		while (from > 0 && !row.get(from - 1).blank() && i - from < MAX_WORD / 2
				&& row.get(from).x() - row.get(from - 1).x() < row.get(from).size() * 1.2f) {
			from--;
		}
		int to = i + 1;
		while (to < row.size() && !row.get(to).blank() && to - i < MAX_WORD / 2
				&& row.get(to).x() - row.get(to - 1).x() < row.get(to).size() * 1.2f) {
			to++;
		}
		int hangulNeighbors = 0;
		for (int k = from; k < to; k++) {
			GlyphDecoder.Decoded d = k == i ? null : decoder.decode(row.get(k).shape());
			if (d != null && isHangul(d.text())) {
				hangulNeighbors++;
			}
		}
		return new Word(page, row, from, to, hangulNeighbors > 0 && hangulNeighbors * 2 >= to - from - 1);
	}

	/** 단어들을 잘라 한 장(많으면 여러 장)에 세로로 붙여 OCR하고, 단어 글자와 맞춰 표를 모은다 */
	private void read(List<Word> words, String language, GlyphDecoder decoder,
			Map<String, Map<String, Integer>> found) throws IOException {
		if (words.isEmpty()) {
			return;
		}
		Map<Integer, List<Word>> byPage = new TreeMap<>();
		words.forEach(w -> byPage.computeIfAbsent(w.page(), k -> new ArrayList<>()).add(w));
		List<BufferedImage> crops = new ArrayList<>();
		List<Word> order = new ArrayList<>();
		float scale = DPI / 72f;
		for (Map.Entry<Integer, List<Word>> e : byPage.entrySet()) {
			BufferedImage page = renderer.renderImageWithDPI(e.getKey() - 1, DPI, ImageType.GRAY);
			for (Word w : e.getValue()) {
				ShapeGlyph first = w.row().get(w.from());
				ShapeGlyph last = w.row().get(w.to() - 1);
				float size = first.size();
				int x0 = clamp(Math.round((first.x() - size * 0.3f) * scale), page.getWidth());
				int x1 = clamp(Math.round((last.x() + size * 1.3f) * scale), page.getWidth());
				// 글자 위 1.3, 아래 0.35 (글자 크기 배수). 0.95로는 숫자·한글 윗부분이 잘려 엉뚱하게 읽혔다.
				// 줄 간격 160%라 이 정도면 위아래 줄은 섞이지 않는다
				int y0 = clamp(Math.round((first.y() - size * 1.3f) * scale), page.getHeight());
				int y1 = clamp(Math.round((first.y() + size * 0.35f) * scale), page.getHeight());
				if (x1 > x0 && y1 > y0) {
					crops.add(page.getSubimage(x0, y0, x1 - x0, y1 - y0));
					order.add(w);
				}
			}
		}
		// Tesseract는 높이 32,767px이 넘는 그림을 못 읽는다 ("Image too large"). 여러 장으로 나눠 동시에 읽는다
		List<Future<?>> sheets = new ArrayList<>();
		int start = 0;
		int height = 0;
		for (int i = 0; i <= crops.size(); i++) {
			int h = i < crops.size() ? crops.get(i).getHeight() * 3 / 2 + GAP : 0;
			if (i == crops.size() || (height + h > MAX_SHEET_HEIGHT && i > start)) {
				if (i > start) {
					List<BufferedImage> part = crops.subList(start, i);
					List<Word> partWords = order.subList(start, i);
					sheets.add(ocr.submit(() -> {
						readSheet(part, partWords, language, decoder, found);
						return null;
					}));
				}
				start = i;
				height = 0;
			}
			height += h;
		}
		for (Future<?> sheet : sheets) {
			try {
				sheet.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("다시 읽기가 중단되었습니다.", e);
			} catch (ExecutionException e) {
				if (e.getCause() instanceof RuntimeException r) {
					throw r;
				}
				throw new IOException("다시 읽기에 실패했습니다.", e.getCause());
			}
		}
	}

	private void readSheet(List<BufferedImage> crops, List<Word> words, String language, GlyphDecoder decoder,
			Map<String, Map<String, Integer>> found) throws IOException {
		int width = crops.stream().mapToInt(BufferedImage::getWidth).max().orElse(1) + 80;
		int height = crops.stream().mapToInt(c -> c.getHeight() * 3 / 2 + GAP).sum() + GAP;
		BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D g = sheet.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, width, height);
		List<Placed> placed = new ArrayList<>();
		int y = GAP;
		for (int i = 0; i < crops.size(); i++) {
			g.drawImage(crops.get(i), 40, y, null);
			placed.add(new Placed(words.get(i), y, y + crops.get(i).getHeight()));
			y += crops.get(i).getHeight() * 3 / 2 + GAP; // 단어 사이 빈 줄 (다른 단어와 한 줄로 읽히지 않게)
		}
		g.dispose();
		Path file = workDir.resolve("rare-" + System.nanoTime() + ".png");
		ImageIO.write(sheet, "png", file.toFile());
		List<OcrLine> lines;
		try {
			lines = ocr.recognize(file, TesseractOcr.PSM_COLUMN, (int) DPI, language);
		} finally {
			Files.deleteIfExists(file);
		}
		for (Placed p : placed) {
			List<OcrWord> inside = lines.stream()
					.filter(l -> l.baseline() >= p.top() && l.baseline() <= p.bottom() + 4)
					.flatMap(l -> l.words().stream())
					.sorted(Comparator.comparingDouble(OcrWord::x0))
					.toList();
			collect(p.word(), inside, decoder, found);
		}
	}

	/** 다시 읽은 글자와 단어 글자를 짝지어, 자신 없던 모양에만 표를 준다 */
	private static void collect(Word word, List<OcrWord> read, GlyphDecoder decoder,
			Map<String, Map<String, Integer>> found) {
		List<String> chars = new ArrayList<>();
		read.forEach(w -> w.text().codePoints().forEach(cp -> chars.add(Character.toString(cp))));
		List<ShapeGlyph> glyphs = word.row().subList(word.from(), word.to());
		List<String> known = new ArrayList<>();
		for (int k = 0; k < glyphs.size(); k++) {
			GlyphDecoder.Decoded d = decoder.decode(glyphs.get(k).shape());
			boolean target = d == null || d.uncertain() || !suspiciousFree(decoder, word.row(), word.from() + k);
			known.add(target ? null : d.text());
		}
		// 글자 수가 단어와 꼭 같을 때만 믿는다. 다르면 어느 글자가 어느 자리인지 알 수 없다
		// (차례로 어림해 짝지었더니 틀린 짝이 2표씩 들어가 오히려 한글 오류가 34자 → 236자로 늘었다)
		if (chars.size() != glyphs.size()) {
			return;
		}
		for (int k = 0; k < glyphs.size(); k++) {
			// 한글 단어에서는 한글로 다시 읽힌 것만 받는다. 한국어 모델도 음절을 "=", "™"로 읽곤 했다
			// (sample.pdf 측정: 이 조건이 없으면 한글 오류가 35자 → 85자로 늘었다)
			// 앞뒤가 한글이 아니어도, 원래 표의 절반 이상이 한글인 모양은 한글로 읽힌 것만 받는다 ("충"이 "="로 읽힘).
			// "하나라도"로 하면 "(B)" 모양에 섞인 표 "르" 2개 때문에 맞게 다시 읽은 "B"를 버렸다
			boolean hangulShape = word.hangul() || mostlyHangul(decoder.votesOf(glyphs.get(k).shape()));
			if (hangulShape && !isHangul(chars.get(k))) {
				continue;
			}
			if (known.get(k) == null) {
				found.computeIfAbsent(glyphs.get(k).shape(), s -> new ConcurrentHashMap<>()).merge(chars.get(k), 1, Integer::sum);
			}
		}
	}

	private static boolean mostlyHangul(Map<String, Integer> votes) {
		int total = votes.values().stream().mapToInt(Integer::intValue).sum();
		int hangul = votes.entrySet().stream().filter(e -> isHangul(e.getKey())).mapToInt(Map.Entry::getValue).sum();
		return total > 0 && hangul * 2 >= total;
	}

	private static boolean suspiciousFree(GlyphDecoder decoder, List<ShapeGlyph> row, int i) {
		return !suspicious(decoder, row, i);
	}

	private static int clamp(int v, int max) {
		return Math.max(0, Math.min(max, v));
	}
}
