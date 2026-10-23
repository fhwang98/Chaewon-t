package cloud.fhwang.chaewon_t.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Span;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Question;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Paragraph;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Block;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import cloud.fhwang.chaewon_t.common.ConversionException;
import cloud.fhwang.chaewon_t.extract.ExamDocument;

class ExamHwpxRendererTest {

	@TempDir
	Path tmp;

	Path template;
	Path out;
	ExamHwpxRenderer renderer = new ExamHwpxRenderer();

	@BeforeEach
	void setUp() throws Exception {
		template = tmp.resolve("template.hwpx");
		try (var in = getClass().getResourceAsStream("/exam-template.hwpx")) {
			Files.copy(in, template);
		}
		out = tmp.resolve("result.hwpx");
	}

	@Test
	void 테스트_템플릿_자체는_검사를_통과한다() throws Exception {
		assertThat(HwpxVerifier.verify(template, List.of(ExamHwpxRenderer.SECTION))).isEmpty();
	}

	@Test
	void 항목_순서와_압축_방식을_유지한다() throws Exception {
		renderer.render(template, out, SampleExam.document(), SampleExam.STYLES);

		assertThat(entries(out)).isEqualTo(entries(template));
		assertThat(entries(out).get(0)).isEqualTo("mimetype STORED");
		assertThat(HwpxPackage.read(out, "settings.xml")).isEqualTo(HwpxPackage.read(template, "settings.xml"));
	}

	@Test
	void 본문_문단_스타일의_문단_보호만_끈다() throws Exception {
		renderer.render(template, out, SampleExam.document(), SampleExam.STYLES);

		Element header = XmlSupport.parse(HwpxPackage.read(out, HwpxVerifier.HEADER)).getDocumentElement();
		java.util.Map<String, String> keepLines = new java.util.HashMap<>();
		for (Element paraPr : XmlSupport.descendants(header, "paraPr")) {
			keepLines.put(paraPr.getAttribute("id"),
					XmlSupport.children(paraPr, "breakSetting").get(0).getAttribute("keepLines"));
		}
		assertThat(keepLines).containsEntry("12", "0").containsEntry("15", "0")
				.containsEntry("14", "1"); // 선택지는 그대로
		assertThat(keepLines).hasSize(17); // id를 새로 만들지 않는다
	}

	@Test
	void 에디터의_글자_문단_모양은_템플릿_서식을_복제해_이어지는_id로_더한다() throws Exception {
		Span italic = new Span("italic", false, false, false, true, false, null, null, null);
		Span bigRed = new Span("red", false, true, false, false, false, null, 12f, "#ff0000");
		Span sup = new Span("2", false, false, false, false, true, "sup", null, null);
		Paragraph styled = new Paragraph(List.of(italic, bigRed, sup), "center", 10f, -10f);
		ExamDocument doc = new ExamDocument("t", List.of(new Question(1, Paragraph.of("Q?"), "",
				List.of(new Block(List.of(styled))), List.of())), List.of());

		renderer.render(template, out, doc, SampleExam.STYLES);

		Element header = XmlSupport.parse(HwpxPackage.read(out, HwpxVerifier.HEADER)).getDocumentElement();
		Element chars = XmlSupport.descendants(header, "charProperties").get(0);
		java.util.Map<String, Element> charPr = new java.util.HashMap<>();
		XmlSupport.children(chars, "charPr").forEach(e -> charPr.put(e.getAttribute("id"), e));
		assertThat(chars.getAttribute("itemCnt")).isEqualTo(String.valueOf(charPr.size()));
		assertThat(charPr).containsKeys("20", "21", "22").doesNotContainKey("23"); // 기존 0~19 다음 번호부터

		Element section = XmlSupport.parse(HwpxPackage.read(out, ExamHwpxRenderer.SECTION)).getDocumentElement();
		Element body = topParagraphs(out).get(1);
		List<Element> runs = XmlSupport.children(body, "run");
		Element italicPr = charPr.get(runs.get(0).getAttribute("charPrIDRef"));
		assertThat(XmlSupport.children(italicPr, "italic")).hasSize(1);
		Element redPr = charPr.get(runs.get(1).getAttribute("charPrIDRef"));
		assertThat(redPr.getAttribute("height")).isEqualTo("1200");
		assertThat(redPr.getAttribute("textColor")).isEqualTo("#FF0000");
		assertThat(XmlSupport.children(redPr, "bold")).hasSize(1); // 굵은 글자 서식(14)을 복제했다
		Element supPr = charPr.get(runs.get(2).getAttribute("charPrIDRef"));
		assertThat(XmlSupport.children(supPr, "supscript")).hasSize(1);
		assertThat(XmlSupport.children(supPr, "strikeout").get(0).getAttribute("shape")).isEqualTo("SOLID");

		Element paras = XmlSupport.descendants(header, "paraProperties").get(0);
		assertThat(paras.getAttribute("itemCnt")).isEqualTo("18"); // 쓰는 것 하나만 더한다
		assertThat(body.getAttribute("paraPrIDRef")).isEqualTo("17"); // 선택지 없는 문항의 마지막 지문(15)을 복제
		Element paraPr = XmlSupport.children(paras, "paraPr").stream()
				.filter(e -> e.getAttribute("id").equals("17")).findFirst().orElseThrow();
		assertThat(XmlSupport.children(paraPr, "align").get(0).getAttribute("horizontal")).isEqualTo("CENTER");
		List<Element> margins = XmlSupport.descendants(paraPr, "margin"); // 새 한글용(case), 옛 한글용(default: 두 배)
		assertThat(XmlSupport.children(margins.get(0), "left").get(0).getAttribute("value")).isEqualTo("1400");
		assertThat(XmlSupport.children(margins.get(0), "intent").get(0).getAttribute("value")).isEqualTo("-1000");
		assertThat(XmlSupport.children(margins.get(1), "left").get(0).getAttribute("value")).isEqualTo("2800");
		assertThat(section).isNotNull();
	}

	@Test
	void 역할별_문단을_순서대로_만든다() throws Exception {
		renderer.render(template, out, SampleExam.document(), SampleExam.STYLES);

		List<String> styles = topParagraphs(out).stream()
				.map(p -> p.getAttribute("paraPrIDRef") + "/" + p.getAttribute("styleIDRef")).toList();
		assertThat(styles).containsExactly(
				// 1번: 머리, 지문 2문단, 빈 줄, 선택지 5개, 문항 사이 빈 줄
				"11/1", "12/2", "12/2", "13/0", "14/4", "14/4", "14/4", "14/4", "16/4", "1/0",
				// 2번: 머리, 주어진 문장, 빈 줄, 지문(마지막이라 15), 빈 줄
				"11/1", "12/2", "13/0", "15/2", "1/0",
				// 3번: 머리, 빈칸 문장, 빈 줄, [조건] 3문단(마지막 15), 빈 줄
				"11/1", "12/2", "13/0", "12/2", "12/2", "15/2", "1/0",
				// 정답: 빈 줄, 제목, 빈 줄, 정답 3개
				"1/0", "1/0", "1/0", "1/0", "1/0", "1/0");
	}

	@Test
	void 첫_문단은_구역_설정을_남기고_글자만_바꾼다() throws Exception {
		renderer.render(template, out, SampleExam.document(), SampleExam.STYLES);

		Element first = topParagraphs(out).get(0);
		assertThat(XmlSupport.descendants(first, "secPr")).hasSize(1);
		assertThat(XmlSupport.descendants(first, "colPr")).hasSize(1);
		assertThat(directRuns(first)).contains("14:다음 글의 내용과 일치하지 ", "19:않는", "14: 것은? [1-2]");
		assertThat(XmlSupport.children(first, "linesegarray")).isEmpty();
		// 머리말: 첫 글자 칸에 시험 이름, 나머지는 비움
		List<String> header = XmlSupport.descendants(XmlSupport.descendants(first, "header").get(0), "t").stream()
				.map(Element::getTextContent).toList();
		assertThat(header.get(0)).isEqualTo("가나고1 (2학기 중간) 테스트");
		assertThat(header.subList(1, header.size())).isNotEmpty().allMatch(String::isEmpty);
	}

	@Test
	void 머리말이_길면_글자_크기를_줄인_서식을_더해_한_줄에_넣는다() throws Exception {
		// 2026-10-22 사용자 확인: 이 이름이 한글에서 두 줄이 되어 머리말 선이 본문을 덮었다
		ExamDocument sample = SampleExam.document();
		ExamDocument doc = new ExamDocument("25년 가나고1 2학기 기말고사 교과서 공통영어2 ABC(가) 4과 기출문제",
				sample.questions(), sample.answers());

		renderer.render(template, out, doc, SampleExam.STYLES);

		Element headerArea = XmlSupport.descendants(topParagraphs(out).get(0), "header").get(0);
		Element titleRun = (Element) XmlSupport.descendants(headerArea, "t").get(0).getParentNode();
		Element header = XmlSupport.parse(HwpxPackage.read(out, HwpxVerifier.HEADER)).getDocumentElement();
		Element pr = XmlSupport.descendants(header, "charPr").stream()
				.filter(e -> e.getAttribute("id").equals(titleRun.getAttribute("charPrIDRef"))).findFirst().orElseThrow();
		assertThat(titleRun.getAttribute("charPrIDRef")).isNotEqualTo("5");
		assertThat(Integer.parseInt(pr.getAttribute("height"))).isBetween(1000, 1650);
		assertThat(pr.getAttribute("textColor")).isEqualTo("#203A7B"); // 크기 말고는 그대로
	}

	@Test
	void 밑줄과_특수문자를_서식에_맞게_넣는다() throws Exception {
		renderer.render(template, out, SampleExam.document(), SampleExam.STYLES);

		List<Element> ps = topParagraphs(out);
		assertThat(directRuns(ps.get(1))).containsExactly(
				"12:Fake news is a ", "18:deliberate", "12: attempt to manipulate people. A&B <tag> \"quote\"");
		assertThat(XmlSupport.children(ps.get(1), "linesegarray")).isEmpty();
		assertThat(new String(HwpxPackage.read(out, ExamHwpxRenderer.SECTION), StandardCharsets.UTF_8))
				.contains("A&amp;B &lt;tag&gt;");
		// 본문 굵게 → 14, 굵게+밑줄 → 19 (2번 주어진 문장)
		assertThat(directRuns(ps.get(11))).containsExactly(
				"14:(A)", "12: Moreover, people are inclined to ", "19:believe", "12: information.");
		// 정답: "1 번"(굵게) " - " "③"(굵게) "   해설"
		assertThat(directRuns(ps.get(ps.size() - 3))).containsExactly(
				"14:1 번", "12: - ", "14:③", "12:   정부 관계자의 경고 때문이 아니라 가짜 뉴스 때문이다.");
		assertThat(directRuns(ps.get(ps.size() - 5))).containsExactly("5:< 정 답 >");
	}

	@Test
	void 문서_정보와_미리보기_글을_바꾼다() throws Exception {
		renderer.render(template, out, SampleExam.document(), SampleExam.STYLES);

		String hpf = new String(HwpxPackage.read(out, ExamHwpxRenderer.CONTENT_HPF), StandardCharsets.UTF_8);
		assertThat(hpf).contains(">가나고1 (2학기 중간) 테스트</opf:title>");
		assertThat(XmlSupport.descendants(XmlSupport.parse(HwpxPackage.read(out, ExamHwpxRenderer.CONTENT_HPF)).getDocumentElement(), "meta")
				.stream().filter(m -> m.getAttribute("name").equals("lastsaveby")).map(Element::getTextContent))
				.containsExactly("");
		String preview = new String(HwpxPackage.read(out, ExamHwpxRenderer.PREVIEW_TEXT), StandardCharsets.UTF_8);
		assertThat(preview).startsWith("가나고1 (2학기 중간) 테스트\r\n다음 글의 내용과 일치하지 않는 것은?");
	}

	@Test
	void 템플릿에_없는_스타일_id를_쓰면_실패하고_결과를_지운다() {
		var bad = new cloud.fhwang.chaewon_t.template.ExamStyles(
				SampleExam.STYLES.question(), SampleExam.STYLES.body(), SampleExam.STYLES.bodyLast(),
				SampleExam.STYLES.gap(), SampleExam.STYLES.choice(), SampleExam.STYLES.choiceLast(),
				SampleExam.STYLES.spacer(), SampleExam.STYLES.answer(),
				new cloud.fhwang.chaewon_t.template.ExamStyles.CharStyles("12", "9999", "14", "19", "5"),
				"< 정 답 >");

		assertThatThrownBy(() -> renderer.render(template, out, SampleExam.document(), bad))
				.isInstanceOf(ConversionException.class)
				.hasMessageContaining("charPrIDRef=9999");
		assertThat(out).doesNotExist();
	}

	@Test
	void 견본_문단이_없는_스타일이면_실패() {
		var bad = new cloud.fhwang.chaewon_t.template.ExamStyles(
				SampleExam.STYLES.question(), new cloud.fhwang.chaewon_t.template.ExamStyles.ParagraphStyle("99", "2"),
				SampleExam.STYLES.bodyLast(), SampleExam.STYLES.gap(), SampleExam.STYLES.choice(),
				SampleExam.STYLES.choiceLast(), SampleExam.STYLES.spacer(), SampleExam.STYLES.answer(),
				SampleExam.STYLES.chars(), "< 정 답 >");

		assertThatThrownBy(() -> renderer.render(template, out, SampleExam.document(), bad))
				.isInstanceOf(ConversionException.class)
				.hasRootCauseMessage("템플릿에 견본 문단이 없습니다: paraPr 99 / style 2");
	}

	@Test
	void 문항이_없으면_실패() {
		assertThatThrownBy(() -> renderer.render(template, out, new ExamDocument("t", List.of(), List.of()), SampleExam.STYLES))
				.isInstanceOf(ConversionException.class)
				.hasMessageContaining("문항이 없어");
	}

	private static List<Element> topParagraphs(Path hwpx) throws Exception {
		Element root = XmlSupport.parse(HwpxPackage.read(hwpx, ExamHwpxRenderer.SECTION)).getDocumentElement();
		return XmlSupport.children(root, "p");
	}

	/** 직계 run을 "charPr:글자"로 */
	private static List<String> directRuns(Element p) {
		List<String> result = new ArrayList<>();
		for (Element run : XmlSupport.children(p, "run")) {
			List<Element> texts = XmlSupport.children(run, "t");
			if (!texts.isEmpty()) {
				result.add(run.getAttribute("charPrIDRef") + ":" + texts.get(0).getTextContent());
			}
		}
		return result;
	}

	private static List<String> entries(Path zip) throws Exception {
		try (ZipFile z = new ZipFile(zip.toFile())) {
			return Collections.list(z.entries()).stream()
					.map(e -> e.getName() + (e.getMethod() == ZipEntry.STORED ? " STORED" : " DEFLATED"))
					.toList();
		}
	}
}
