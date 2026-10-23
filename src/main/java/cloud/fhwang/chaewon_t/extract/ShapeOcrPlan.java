package cloud.fhwang.chaewon_t.extract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cloud.fhwang.chaewon_t.extract.GlyphDecoder.ShapeGlyph;

/**
 * 글자 모양 페이지를 몇 쪽만 OCR하기 위한 계획.
 *
 * 같은 글자는 문서 전체에서 같은 모양이라(sample2: 6쪽째부터 새 쪽 모양의 96~98%가 앞쪽에 이미 나옴),
 * 모든 쪽을 통째로 OCR할 필요가 없다. 차례:
 *   1. 처음 보는 모양이 가장 많은 쪽부터 몇 쪽(동시 실행 수만큼)을 통째로 OCR해 모양별 글자를 정한다
 *   2. 남은 쪽 중 아직 못 정한 글자가 많은 쪽은 다음 차례에 통째로 OCR한다
 *   3. 못 정한 글자가 조금뿐인 쪽은 그 단어만 잘라 다시 읽는다 (RareGlyphReader)
 * OCR이 페이지 전체 시간의 대부분이라, 통째로 읽는 쪽 수가 곧 변환 시간이다.
 */
final class ShapeOcrPlan {

	/**
	 * 못 정한 글자가 이만큼 이상인 쪽은 통째로 OCR한다. 그보다 적으면 단어만 잘라 다시 읽는다
	 * (sample.pdf 측정, glyphEvalProbe -Pprogressive: 25면 10쪽만 OCR하지만 99.93%, 15면 18쪽 OCR에 전부 OCR한 것과 같은 99.96%,
	 *  10이면 25쪽으로 정확도 이득 없음. 글자 가짓수 60으로 하면 4쪽만 OCR해 ①~⑤가 틀려 99.30%)
	 */
	static final int FULL_PAGE_UNKNOWN = 15;

	private ShapeOcrPlan() {
	}

	/** 처음 통째로 OCR할 쪽: 아직 안 나온 모양을 가장 많이 가진 쪽부터 size쪽 (쪽 번호 순서로 돌려준다) */
	static List<Integer> firstBatch(Map<Integer, List<ShapeGlyph>> pages, int size) {
		Set<String> covered = new HashSet<>();
		List<Integer> chosen = new ArrayList<>();
		Map<Integer, Set<String>> shapes = new HashMap<>();
		pages.forEach((p, glyphs) -> {
			Set<String> set = new HashSet<>();
			glyphs.stream().filter(g -> !g.blank()).forEach(g -> set.add(g.shape()));
			shapes.put(p, set);
		});
		while (chosen.size() < Math.min(size, pages.size())) {
			int best = shapes.keySet().stream()
					.filter(p -> !chosen.contains(p))
					.max(Comparator.comparingLong((Integer p) -> shapes.get(p).stream().filter(s -> !covered.contains(s)).count())
							.thenComparing(Comparator.reverseOrder()))
					.orElseThrow();
			chosen.add(best);
			covered.addAll(shapes.get(best));
		}
		chosen.sort(Integer::compare);
		return chosen;
	}

	/** 지금까지 OCR한 쪽 줄로 표를 모은 해독기. 쪽 순서대로 ①~⑤ 보정을 거친 OCR 줄을 쓴다 */
	static GlyphDecoder learn(Map<Integer, List<ShapeGlyph>> pages, Map<Integer, List<PdfLine>> ocrLines) {
		GlyphDecoder decoder = new GlyphDecoder();
		ocrLines.keySet().stream().sorted().forEach(p -> decoder.learn(pages.get(p), ocrLines.get(p)));
		ocrLines.keySet().stream().sorted().forEach(p -> decoder.learnUnknown(pages.get(p), ocrLines.get(p)));
		return decoder;
	}

	/** OCR한 쪽 줄을 쪽 순서로 ①~⑤ 보정해 쪽별로 (보정은 앞뒤 줄의 번호 순서를 보므로 모든 쪽을 함께 한다) */
	static Map<Integer, List<PdfLine>> normalized(Map<Integer, List<PdfLine>> ocrByPage) {
		List<PdfLine> all = new ArrayList<>();
		new java.util.TreeMap<>(ocrByPage).values().forEach(all::addAll);
		Map<Integer, List<PdfLine>> byPage = new java.util.TreeMap<>();
		ocrByPage.keySet().forEach(p -> byPage.put(p, new ArrayList<>()));
		OcrMarks.normalize(all, ocrByPage.keySet()).forEach(l -> byPage.get(l.page()).add(l));
		return byPage;
	}

	/** 아직 OCR하지 않은 쪽 중 못 정한 글자가 많아 통째로 OCR할 쪽 */
	static List<Integer> needFullOcr(GlyphDecoder decoder, Map<Integer, List<ShapeGlyph>> pages, Set<Integer> done) {
		List<Integer> result = new ArrayList<>();
		pages.forEach((p, glyphs) -> {
			if (done.contains(p)) {
				return;
			}
			long unknown = glyphs.stream().filter(g -> !g.blank() && decoder.decode(g.shape()) == null).count();
			if (unknown >= FULL_PAGE_UNKNOWN) {
				result.add(p);
			}
		});
		result.sort(Integer::compare);
		return result;
	}
}
