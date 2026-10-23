package cloud.fhwang.chaewon_t.extract;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import cloud.fhwang.chaewon_t.extract.GlyphDecoder.ShapeGlyph;

/**
 * 인쇄 PDF의 글자 모양이 쪽마다 얼마나 겹치는지 본다 (OCR 없이 빠르게). 몇 쪽만 OCR하는 계획(ShapeOcrPlan)이
 * 쪽을 얼마나 고르는지 어림한다: OCR한 쪽에 나온 모양은 정해진다고 치고, 남은 쪽의 "처음 보는 모양 글자 수"를 센다.
 *
 * 실행: ./gradlew shapeCoverageProbe -Pfile=samples/sample2.pdf
 */
public class ShapeCoverageProbe {

	public static void main(String[] args) throws Exception {
		try (PDDocument doc = Loader.loadPDF(Path.of(args[0]).toFile())) {
			GlyphDecoder decoder = new GlyphDecoder();
			Map<Integer, List<ShapeGlyph>> pages = new TreeMap<>();
			for (int p = 1; p <= doc.getNumberOfPages(); p++) {
				pages.put(p, decoder.shapes(PdfLayoutReader.glyphs(doc, p, true)));
			}
			Map<String, Integer> count = new HashMap<>();
			pages.values().forEach(gs -> gs.forEach(g -> count.merge(g.shape(), 1, Integer::sum)));
			System.out.println("모양 " + count.size() + "개, 한 번만 나오는 모양 " + count.values().stream().filter(c -> c == 1).count()
					+ "개, 두 번 " + count.values().stream().filter(c -> c == 2).count() + "개");
			Set<Integer> chosen = new HashSet<>(ShapeOcrPlan.firstBatch(pages, 4));
			for (int round = 1; round < 10; round++) {
				Set<String> known = new HashSet<>();
				chosen.forEach(p -> pages.get(p).forEach(g -> known.add(g.shape())));
				Map<Integer, Long> unknown = new TreeMap<>();
				pages.forEach((p, gs) -> {
					if (!chosen.contains(p)) {
						unknown.put(p, gs.stream().filter(g -> !g.blank() && !known.contains(g.shape())).count());
					}
				});
				System.out.println(round + "차: OCR " + chosen.size() + "쪽, 남은 쪽의 처음 보는 모양 글자 수 " + unknown);
				List<Integer> more = unknown.entrySet().stream().filter(e -> e.getValue() >= ShapeOcrPlan.FULL_PAGE_UNKNOWN)
						.map(Map.Entry::getKey).toList();
				if (more.isEmpty()) {
					break;
				}
				chosen.addAll(more);
			}
		}
	}
}
