package cloud.fhwang.chaewon_t.extract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.text.TextPosition;

import cloud.fhwang.chaewon_t.extract.PdfLayoutReader.PageGlyphs;
import cloud.fhwang.chaewon_t.extract.PdfLine.Glyph;
import cloud.fhwang.chaewon_t.template.PdfLayout;

/**
 * 글자 모양으로 읽기: 프린터로 인쇄한 PDF(sample2~4)는 글자를 문자 정보 없는 Type3 글꼴의 "모양"으로 그린다.
 * 같은 글자는 문서 전체에서 같은 모양(같은 그리기 명령)이다 — sample2는 글자 151,395개가 모양 919개뿐이었다.
 *
 * 그래서 OCR이 읽은 글자를 PDF의 글자 자리에 맞춰 놓고, 모양마다 OCR이 무엇으로 읽었는지 표를 모아
 * 가장 많이 나온 글자로 정한다(다수결). OCR이 어쩌다 틀린 자리는 다른 자리들의 표에 묻힌다.
 * 그다음 글은 OCR 결과가 아니라 PDF의 글자 자리(정확한 좌표, 굵게, 밑줄)로 다시 짠다.
 *
 * 모양 = Type3 글자 그리기 명령(CharProc)의 SHA-1. 아무것도 그리지 않는 모양은 공백이다.
 * PDF가 알려 주는 글자 폭·높이는 이 글꼴에서 엉터리(모든 글자 8.1pt 폭, 81pt 높이)라 쓰지 않고,
 * 폭은 다음 글자까지의 거리, 높이는 글꼴 크기(pt)로 정한다.
 */
final class GlyphDecoder {

	/** 2차 짝짓기에서 OCR 글자와 PDF 글자 중심이 글자 크기의 이 배수 안이어야 짝으로 본다 */
	private static final float NEAR = 0.7f;
	/** 표가 없는 모양이 한 줄에 이만큼 이상 연달아 있으면 빈칸 밑줄 "_"로 본다 */
	private static final int RUN = 3;
	/**
	 * 영문·숫자·기호는 이보다 표가 적거나 1등 비율이 낮으면 "확인 필요"로 표시한다.
	 * 한글은 표가 1개씩 갈렸을 때만 표시한다: 표 하나로 정한 한글은 156자 중 153자가 맞았다 (sample.pdf 측정)
	 */
	private static final int MIN_VOTES = 2;
	private static final double MIN_SHARE = 0.6;
	private static final double LATIN_SHARE = 0.75;
	/** 같은 모양이 이만큼(pt) 안에 또 있으면 겹쳐 그린 것(가짜 굵게)으로 보고 하나만 쓴다 */
	private static final float DUPLICATE = 0.3f;
	/** 같은 줄로 보는 기준선 차이 (pt). PDF 좌표는 정확하지만 첨자 등이 있어 조금 둔다 */
	private static final float SAME_LINE = 1.5f;
	/** OCR 줄과 PDF 글자를 맞출 때 기준선 차이 허용 (pt). OCR 기준선은 1pt 안팎 어긋난다 */
	private static final float OCR_LINE = 3f;
	/** 글자 사이가 글자 크기의 이 배수보다 벌어지면 공백 3칸 (한 줄의 선택지 둘) */
	private static final float WIDE_GAP = 1.5f;

	/** Type3 글자 하나. shape는 모양 식별값, blank는 아무것도 그리지 않는 모양(공백) */
	record ShapeGlyph(String shape, boolean blank, float x, float y, float size, boolean bold) {
	}

	/** 다시 읽기에서 두 번 이상 같은 글자로 읽혀 확정한 모양 (표가 갈려 있어도 확인 필요로 표시하지 않는다) */
	private final java.util.Set<String> confirmed = new java.util.HashSet<>();
	/** 모양별 표: 모양 → (OCR 글자 → 표 수) */
	private final Map<String, Map<String, Integer>> votes = new HashMap<>();
	private final Map<COSStream, String> shapeCache = new IdentityHashMap<>();
	/** 모양별 [나온 수, 3개 이상 연달아 나온 수] (learnRuns) */
	private final Map<String, int[]> runCounts = new HashMap<>();
	private final Map<COSStream, Boolean> blankCache = new IdentityHashMap<>();

	/** 글자 모양으로 읽을 수 있는 페이지: 글자가 모두 그리기 명령이 있는 Type3 글꼴 */
	static boolean decodable(PageGlyphs page) {
		return !page.glyphs().isEmpty() && page.glyphs().stream().allMatch(GlyphDecoder::hasCharProc);
	}

	private static boolean hasCharProc(TextPosition t) {
		return t.getFont() instanceof PDType3Font f && t.getCharacterCodes().length == 1
				&& f.getCharProc(t.getCharacterCodes()[0]) != null;
	}

	/**
	 * @param page PDFTextStripper 뒤처리 전에 모은 글자 (PdfLayoutReader.glyphs(doc, n, true)).
	 *             같은 모양이 같은 자리(0.3pt 안)에 또 있으면 하나만 남긴다
	 */
	List<ShapeGlyph> shapes(PageGlyphs page) {
		List<ShapeGlyph> result = new ArrayList<>(page.glyphs().size());
		for (TextPosition t : page.glyphs()) {
			PDType3CharProc proc = ((PDType3Font) t.getFont()).getCharProc(t.getCharacterCodes()[0]);
			COSStream stream = proc.getCOSObject();
			ShapeGlyph g = new ShapeGlyph(shape(stream), blank(stream), t.getX(), t.getY(), t.getFontSizeInPt(),
					page.bold().contains(t));
			boolean duplicate = result.stream().skip(Math.max(0, result.size() - 4))
					.anyMatch(o -> o.shape().equals(g.shape()) && Math.abs(o.x() - g.x()) < DUPLICATE
							&& Math.abs(o.y() - g.y()) < DUPLICATE);
			if (!duplicate) {
				result.add(g);
			}
		}
		return result;
	}

	/**
	 * OCR이 읽은 줄(①~⑤ 보정까지 마친 것)을 PDF 글자에 맞춰 표를 모은다.
	 * 줄의 공백 아닌 글자 수가 같으면 차례로 짝짓고, 다르면 단어 단위로 맞춰 본다. 그래도 수가 다르면 그 단어는 건너뛴다
	 */
	void learn(List<ShapeGlyph> glyphs, List<PdfLine> ocrLines) {
		for (PdfLine line : ocrLines) {
			List<ShapeGlyph> candidates = candidates(glyphs, line);
			List<Glyph> chars = line.glyphs().stream().filter(g -> !g.text().isBlank()).toList();
			// 줄 전체 글자 수가 같아도 자리가 어긋나 있으면(OCR이 빈칸 밑줄을 빼고 다른 데서 글자를 더 읽은 경우) 믿지 않는다
			if (chars.size() == candidates.size() && aligned(chars, candidates)) {
				for (int i = 0; i < chars.size(); i++) {
					vote(candidates.get(i).shape(), chars.get(i).text());
				}
				continue;
			}
			for (List<Glyph> word : words(line)) {
				float x0 = word.get(0).x();
				Glyph last = word.get(word.size() - 1);
				float x1 = last.x() + last.width();
				List<ShapeGlyph> inWord = candidates.stream()
						.filter(g -> g.x() + g.size() * 0.25f >= x0 && g.x() + g.size() * 0.25f <= x1)
						.toList();
				if (inWord.size() == word.size()) {
					for (int i = 0; i < word.size(); i++) {
						vote(inWord.get(i).shape(), word.get(i).text());
					}
				}
			}
		}
	}

	/**
	 * 두 번째 표 모으기: 첫 번째에서 글자 수가 안 맞아 건너뛴 줄의 드문 모양(굵은 문항 번호 "31"의 1 등)을 채운다.
	 * 이미 정해진 모양으로 줄을 글자로 바꿔 OCR 글자와 편집 거리로 줄 맞춤한 뒤, 아직 표가 없는 모양만 짝지은 OCR 글자로 표를 준다
	 */
	void learnUnknown(List<ShapeGlyph> glyphs, List<PdfLine> ocrLines) {
		Map<String, Map<String, Integer>> found = new HashMap<>();
		for (PdfLine line : ocrLines) {
			List<ShapeGlyph> candidates = candidates(glyphs, line);
			if (candidates.stream().noneMatch(g -> decode(g.shape()) == null)) {
				continue;
			}
			List<Glyph> ocrGlyphs = line.glyphs().stream().filter(g -> !g.text().isBlank()).toList();
			List<String> ocrChars = ocrGlyphs.stream().map(Glyph::text).toList();
			float[] ocrX = new float[ocrGlyphs.size()];
			for (int i = 0; i < ocrX.length; i++) {
				ocrX[i] = ocrGlyphs.get(i).x() + ocrGlyphs.get(i).width() / 2;
			}
			List<String> decoded = candidates.stream()
					.map(g -> { Decoded d = decode(g.shape()); return d == null ? null : d.text(); })
					.toList();
			float[] pdfX = new float[candidates.size()];
			for (int j = 0; j < pdfX.length; j++) {
				pdfX[j] = candidates.get(j).x() + candidates.get(j).size() * 0.25f;
			}
			int[] pairs = align(ocrChars, ocrX, decoded, pdfX, line.height());
			for (int j = 0; j < pairs.length; j++) {
				// 자리가 가까운 짝만 믿는다. OCR이 아예 안 읽은 글자(빈칸 밑줄 "____")가 멀리 있는 글자와 짝지어지던 문제
				// (sample.pdf 측정: "_"가 "a"로 정해져 235자 틀림)
				if (pairs[j] >= 0 && decoded.get(j) == null
						&& Math.abs(ocrX[pairs[j]] - pdfX[j]) <= candidates.get(j).size() * NEAR) {
					found.computeIfAbsent(candidates.get(j).shape(), k -> new HashMap<>())
							.merge(ocrChars.get(pairs[j]), 1, Integer::sum);
				}
			}
		}
		// 이번 표는 모두 모은 뒤 넣는다 (같은 줄 안에서 방금 정한 모양이 다른 짝짓기에 섞이지 않게)
		found.forEach((shape, counts) -> counts.forEach((text, n) -> {
			for (int i = 0; i < n; i++) {
				vote(shape, text);
			}
		}));
	}

	/**
	 * OCR 글자열과 PDF 글자열(모르는 글자는 null)을 편집 거리로 맞춘다.
	 * 비용: 같은 글자 0, 다른 글자 1, 빼거나 더하기 1, 모르는 글자와 짝짓기 0.5 + 두 글자 사이 거리(글자 크기 단위) × 0.4.
	 * 거리 비용이 없으면 모르는 글자를 양옆 어느 글자와 짝지어도 비용이 같아 엉뚱한 짝이 생긴다
	 *
	 * @return PDF 글자마다 짝인 OCR 글자 위치 (짝이 없으면 -1)
	 */
	static int[] align(List<String> ocr, float[] ocrX, List<String> pdf, float[] pdfX, float size) {
		int n = ocr.size();
		int m = pdf.size();
		double[][] cost = new double[n + 1][m + 1];
		for (int i = 0; i <= n; i++) {
			cost[i][0] = i;
		}
		for (int j = 0; j <= m; j++) {
			cost[0][j] = j;
		}
		for (int i = 1; i <= n; i++) {
			for (int j = 1; j <= m; j++) {
				cost[i][j] = Math.min(cost[i - 1][j - 1] + pairCost(ocr, ocrX, pdf, pdfX, size, i - 1, j - 1),
						Math.min(cost[i - 1][j], cost[i][j - 1]) + 1);
			}
		}
		int[] pairs = new int[m];
		java.util.Arrays.fill(pairs, -1);
		int i = n;
		int j = m;
		while (i > 0 && j > 0) {
			if (cost[i][j] == cost[i - 1][j - 1] + pairCost(ocr, ocrX, pdf, pdfX, size, i - 1, j - 1)) {
				pairs[j - 1] = i - 1;
				i--;
				j--;
			} else if (cost[i][j] == cost[i - 1][j] + 1) {
				i--;
			} else {
				j--;
			}
		}
		return pairs;
	}

	private static double pairCost(List<String> ocr, float[] ocrX, List<String> pdf, float[] pdfX, float size,
			int i, int j) {
		String p = pdf.get(j);
		if (p == null) {
			return 0.5 + Math.abs(ocrX[i] - pdfX[j]) / Math.max(1f, size) * 0.4;
		}
		return p.equals(ocr.get(i)) ? 0 : 1;
	}

	/**
	 * 한 페이지를 PDF 글자 자리로 다시 짠 본문 줄. 표가 없는 모양은 같은 자리의 OCR 글자를 쓰고 "확인 필요"로 둔다
	 *
	 * @param layout 이 페이지에 맞춘 배치값
	 */
	List<PdfLine> lines(int page, List<ShapeGlyph> glyphs, PdfLayout layout,
			List<UnderlineDetector.Segment> segments, List<PdfLine> ocrLines) {
		List<PdfLine> lines = new ArrayList<>();
		for (int column = 0; column < 2; column++) {
			int col = column;
			List<ShapeGlyph> inColumn = glyphs.stream()
					.filter(g -> g.y() > layout.headerBottom() && g.y() < layout.footerTop())
					.filter(g -> (g.x() < layout.columnSplit()) == (col == 0))
					.toList();
			float textLeft = (float) inColumn.stream().filter(g -> !g.blank()).mapToDouble(ShapeGlyph::x).min().orElse(0);
			List<UnderlineDetector.Segment> underlines = PdfLayoutReader.underlines(segments, textLeft, layout);
			for (List<ShapeGlyph> row : rows(inColumn)) {
				PdfLine line = line(page, col, row, underlines, ocrLines);
				if (line != null) {
					lines.add(line);
				}
			}
		}
		return lines;
	}

	/** 1쪽 머리글 구역의 글자 (시험 이름 기본값). 표가 없는 모양이 하나라도 있으면 null */
	String headerText(List<ShapeGlyph> glyphs, PdfLayout layout) {
		List<ShapeGlyph> header = glyphs.stream().filter(g -> g.y() <= layout.headerBottom()).toList();
		if (header.stream().anyMatch(g -> !g.blank() && decode(g.shape()) == null)) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (List<ShapeGlyph> row : rows(header)) {
			PdfLine line = line(1, 0, row, List.of(), List.of());
			if (line != null) {
				sb.append(sb.isEmpty() ? "" : " ").append(line.text().strip());
			}
		}
		return sb.toString();
	}

	/**
	 * 세 번째: 한 줄에 3개 이상 연달아 나오는 일이 대부분인 모양 중 표가 갈리는(1등이 60% 미만) 것은 빈칸 밑줄 "_"로 정한다.
	 * OCR은 "______" 같은 빈칸을 글자로 읽지 않거나 "~", "." 등 제각각으로 읽어 표가 흩어진다
	 * (sample.pdf 측정: "_" 235자가 "a", "~"로 정해졌다). 같은 글자가 셋 넘게 붙는 "..." 같은 것은 OCR이 일정하게 읽어 해당하지 않는다
	 */
	void learnRuns(List<ShapeGlyph> glyphs) {
		Map<String, int[]> counts = new HashMap<>(); // [나온 수, 연달아 나온 수]
		for (List<ShapeGlyph> row : rows(glyphs)) {
			int i = 0;
			while (i < row.size()) {
				int j = i;
				while (j < row.size() && row.get(j).shape().equals(row.get(i).shape())) {
					j++;
				}
				if (!row.get(i).blank()) {
					int[] c = counts.computeIfAbsent(row.get(i).shape(), k -> new int[2]);
					c[0] += j - i;
					if (j - i >= RUN) {
						c[1] += j - i;
					}
				}
				i = j;
			}
		}
		runCounts.forEach((shape, c) -> counts.merge(shape, c, (a, b) -> new int[] { a[0] + b[0], a[1] + b[1] }));
		runCounts.clear();
		runCounts.putAll(counts);
	}

	/** learnRuns로 모든 쪽을 센 뒤 부른다: 빈칸 밑줄로 보이는 모양을 "_"로 정한다 */
	void settleRuns() {
		runCounts.forEach((shape, c) -> {
			Decoded d = decode(shape);
			int voteCount = votes.getOrDefault(shape, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
			// OCR이 글자로 거의 안 읽는 모양(표 수가 나온 수의 20% 미만)이거나 표가 갈린 모양
			// (sample.pdf: "_" 235자에 표 5개 {~=3, .=1, _=1}. 1등 비율 60%라 비율만으로는 걸리지 않았다)
			boolean unseen = voteCount < c[0] * 0.2;
			if (c[1] * 2 >= c[0] && c[1] >= RUN && (d == null || unseen || shareOf(shape) < MIN_SHARE)) {
				Map<String, Integer> v = new HashMap<>();
				v.put("_", MIN_VOTES); // 확실한 것으로 (노란 표시를 빈칸마다 붙이지 않게)
				votes.put(shape, v);
			}
		});
	}

	private double shareOf(String shape) {
		Map<String, Integer> counts = votes.getOrDefault(shape, Map.of());
		int total = counts.values().stream().mapToInt(Integer::intValue).sum();
		int best = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
		return total == 0 ? 0 : (double) best / total;
	}

	/** 모양의 글자와 확실한지 */
	record Decoded(String text, boolean uncertain) {
	}

	Decoded decode(String shape) {
		Map<String, Integer> counts = votes.get(shape);
		if (counts == null || counts.isEmpty()) {
			return null;
		}
		Map.Entry<String, Integer> best = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
		int total = counts.values().stream().mapToInt(Integer::intValue).sum();
		if (best.getValue() == 1 && total >= 3) {
			return null; // 표가 모두 다르다: 짝짓기가 어긋난 것이라 아무 글자로도 정하지 않는다
		}
		boolean uncertain;
		if (confirmed.contains(shape)) {
			uncertain = false;
		} else if (isHangul(best.getKey()) || isCircled(best.getKey())) {
			// 한글·동그라미 숫자는 1등이 있으면 거의 맞다. 표가 1개씩 갈렸을 때만 표시한다
			uncertain = best.getValue() == 1 && total >= 2;
		} else {
			// 영문·숫자·기호는 1등이 75% 안 되면 의심한다. "(B)"가 8 36표 / B 23표(60%)로 갈렸는데 60% 기준에선 확실로 봐
			// 다시 읽기 대상에서 빠졌다. 보통 글자는 OCR이 몇 %만 다르게 읽는다
			uncertain = total < MIN_VOTES || best.getValue() < total * LATIN_SHARE;
		}
		return new Decoded(best.getKey(), uncertain);
	}

	private PdfLine line(int page, int column, List<ShapeGlyph> row, List<UnderlineDetector.Segment> underlines,
			List<PdfLine> ocrLines) {
		List<Glyph> glyphs = new ArrayList<>();
		ShapeGlyph prev = null;
		float prevEnd = 0;
		for (int i = 0; i < row.size(); i++) {
			ShapeGlyph g = row.get(i);
			ShapeGlyph next = i + 1 < row.size() ? row.get(i + 1) : null;
			float width = next == null ? g.size() * 0.5f : Math.min(next.x() - g.x(), g.size());
			if (g.blank()) {
				// 공백에도 밑줄을 본다: 밑줄 친 공백이 이어지면 빈칸이다 (PdfLayoutReader.underlinedBlanks)
				boolean underline = PdfLayoutReader.isUnderlined(g.x() + width / 2, g.y(), underlines);
				glyphs.add(new Glyph(" ", g.x(), width, underline, false));
				prev = g;
				prevEnd = g.x() + width;
				continue;
			}
			if (prev != null && !prev.blank() && g.x() - prevEnd > g.size() * WIDE_GAP) {
				for (int k = 0; k < 3; k++) {
					glyphs.add(new Glyph(" ", prevEnd, 0, false, false));
				}
			} else if (prev != null && prev.blank() && g.x() - prevEnd > g.size() * WIDE_GAP) {
				glyphs.add(new Glyph(" ", prevEnd, 0, false, false));
				glyphs.add(new Glyph(" ", prevEnd, 0, false, false));
			}
			Decoded d = decode(g.shape());
			String text;
			boolean uncertain;
			if (d != null) {
				text = d.text();
				uncertain = d.uncertain();
			} else {
				text = ocrCharAt(ocrLines, g);
				uncertain = true;
			}
			if (!text.isEmpty()) {
				boolean underline = PdfLayoutReader.isUnderlined(g.x() + width / 2, g.y(), underlines);
				glyphs.add(new Glyph(text, g.x(), width, underline, g.bold(), uncertain));
			}
			prev = g;
			prevEnd = g.x() + width;
		}
		// 앞뒤 공백만 있는 줄은 버린다
		if (glyphs.stream().allMatch(gl -> gl.text().isBlank())) {
			return null;
		}
		float x = glyphs.stream().filter(gl -> !gl.text().isBlank()).findFirst().orElseThrow().x();
		float size = (float) row.stream().mapToDouble(ShapeGlyph::size).max().orElse(10);
		return new PdfLine(page, column, x, row.get(0).y(), size, PdfLayoutReader.fixSpaceUnderlines(glyphs));
	}

	/** OCR 글자와 PDF 글자를 차례로 짝지었을 때 모두 자리가 가까운지 */
	private static boolean aligned(List<Glyph> ocr, List<ShapeGlyph> pdf) {
		for (int i = 0; i < ocr.size(); i++) {
			float ocrCenter = ocr.get(i).x() + ocr.get(i).width() / 2;
			float pdfCenter = pdf.get(i).x() + pdf.get(i).size() * 0.25f;
			if (Math.abs(ocrCenter - pdfCenter) > pdf.get(i).size() * NEAR) {
				return false;
			}
		}
		return true;
	}

	/** OCR 줄과 같은 줄·같은 x 범위에 있는 PDF 글자 (공백 모양 빼고, x 순서) */
	private static List<ShapeGlyph> candidates(List<ShapeGlyph> glyphs, PdfLine line) {
		return glyphs.stream()
				.filter(g -> !g.blank() && Math.abs(g.y() - line.y()) <= OCR_LINE)
				.filter(g -> g.x() >= line.x() - line.height() && g.x() <= line.endX() + 1)
				.sorted(Comparator.comparingDouble(ShapeGlyph::x))
				.toList();
	}

	/** 표가 없는 모양: 같은 자리에 OCR이 읽은 글자 (없으면 "?") */
	private static String ocrCharAt(List<PdfLine> ocrLines, ShapeGlyph g) {
		for (PdfLine line : ocrLines) {
			if (Math.abs(line.y() - g.y()) > OCR_LINE) {
				continue;
			}
			for (Glyph o : line.glyphs()) {
				if (!o.text().isBlank() && g.x() + g.size() * 0.25f >= o.x() && g.x() + g.size() * 0.25f <= o.x() + o.width()) {
					return o.text();
				}
			}
		}
		return "?"; // 글자를 빼 버리면 "31."이 "3."이 되는 식으로 번호가 틀어진다. 노란 표시와 함께 남긴다
	}

	/** 기준선이 같은 글자끼리 한 줄로, x 순서 */
	static List<List<ShapeGlyph>> rows(List<ShapeGlyph> glyphs) {
		List<ShapeGlyph> sorted = new ArrayList<>(glyphs);
		sorted.sort(Comparator.comparingDouble(ShapeGlyph::y).thenComparingDouble(ShapeGlyph::x));
		List<List<ShapeGlyph>> rows = new ArrayList<>();
		List<ShapeGlyph> current = new ArrayList<>();
		for (ShapeGlyph g : sorted) {
			if (!current.isEmpty() && Math.abs(g.y() - current.get(0).y()) > SAME_LINE) {
				rows.add(current);
				current = new ArrayList<>();
			}
			current.add(g);
		}
		if (!current.isEmpty()) {
			rows.add(current);
		}
		for (List<ShapeGlyph> row : rows) {
			row.sort(Comparator.comparingDouble(ShapeGlyph::x));
		}
		return rows;
	}

	/** OCR 줄을 공백으로 나눈 단어들 */
	private static List<List<Glyph>> words(PdfLine line) {
		List<List<Glyph>> words = new ArrayList<>();
		List<Glyph> current = new ArrayList<>();
		for (Glyph g : line.glyphs()) {
			if (g.text().isBlank()) {
				if (!current.isEmpty()) {
					words.add(current);
					current = new ArrayList<>();
				}
			} else {
				current.add(g);
			}
		}
		if (!current.isEmpty()) {
			words.add(current);
		}
		return words;
	}

	/** 다시 읽기에서 같은 글자로 여러 번 읽힌 모양을 그 글자로 확정한다 (300dpi 표가 갈려 있어도 600dpi로 다시 본 쪽을 믿는다) */
	void confirm(String shape, String text) {
		int total = votes.getOrDefault(shape, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
		Map<String, Integer> v = new HashMap<>();
		v.put(text, Math.max(total, MIN_VOTES));
		votes.put(shape, v);
		confirmed.add(shape);
	}

	private static boolean isCircled(String text) {
		return !text.isEmpty() && text.charAt(0) >= '①' && text.charAt(0) <= '⑳';
	}

	private static boolean isHangul(String text) {
		return !text.isEmpty() && Character.UnicodeScript.of(text.codePointAt(0)) == Character.UnicodeScript.HANGUL;
	}

	/** 다른 곳(RareGlyphReader)에서 모은 표를 더한다 */
	void addVotes(String shape, String text, int count) {
		votes.computeIfAbsent(shape, k -> new HashMap<>()).merge(text, count, Integer::sum);
	}

	/** 모양의 표 (측정·디버깅용) */
	Map<String, Integer> votesOf(String shape) {
		return votes.getOrDefault(shape, Map.of());
	}

	private void vote(String shape, String text) {
		votes.computeIfAbsent(shape, k -> new HashMap<>()).merge(text, 1, Integer::sum);
	}

	private String shape(COSStream stream) {
		return shapeCache.computeIfAbsent(stream, s -> {
			try {
				return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes(s)));
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	/** "wx 0 llx lly urx ury d1" 뒤에 그리기 명령이 없으면 공백 */
	private boolean blank(COSStream stream) {
		return blankCache.computeIfAbsent(stream, s -> {
			String content = new String(bytes(s), StandardCharsets.ISO_8859_1);
			int d1 = content.indexOf("d1");
			int d0 = content.indexOf("d0");
			int start = d1 >= 0 ? d1 + 2 : d0 >= 0 ? d0 + 2 : 0;
			return content.substring(start).isBlank();
		});
	}

	private static byte[] bytes(COSStream stream) {
		try (var in = stream.createInputStream()) {
			return in.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
