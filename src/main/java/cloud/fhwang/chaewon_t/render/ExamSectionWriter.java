package cloud.fhwang.chaewon_t.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import cloud.fhwang.chaewon_t.extract.ExamDocument;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Answer;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Block;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Paragraph;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Question;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Span;
import cloud.fhwang.chaewon_t.template.ExamStyles;
import cloud.fhwang.chaewon_t.template.HeaderTitleFit;
import cloud.fhwang.chaewon_t.template.ExamStyles.ParagraphStyle;

/**
 * 템플릿 섹션 XML(DOM)을 시험지 내용으로 바꾼다.
 *
 * 1. 첫 문단은 남긴다: 구역 설정(secPr, 2단 colPr)과 머리말·꼬리말 ctrl이 같은 문단·같은 run 안에 들어 있다.
 *    이 문단에서는 글자(t)만 첫 문항 질문으로 바꾸고 ctrl은 건드리지 않는다
 * 2. 나머지 본문 문단은 지우고, 역할별 견본 문단을 복제해 차례로 붙인다
 * 3. 글자를 넣은 문단은 linesegarray(줄 배치 캐시)를 지운다. 한글이 열 때 다시 계산한다
 *
 * [확인 필요] 복제한 문단의 id가 겹쳐도 되는지 (템플릿에도 id="2147483648" 문단이 여럿 있어 괜찮을 가능성이 높다)
 */
final class ExamSectionWriter {

	private static final String P = "p";
	private static final String RUN = "run";
	private static final String TEXT = "t";
	private static final String LINESEG = "linesegarray";
	private static final String CHAR_REF = "charPrIDRef";

	private ExamSectionWriter() {
	}

	static void write(Document section, ExamDocument doc, ExamStyles styles, HeaderStyles header) {
		if (doc.questions().isEmpty()) {
			throw new IllegalArgumentException("문항이 없습니다.");
		}
		Element root = section.getDocumentElement();
		List<Element> paragraphs = XmlSupport.children(root, P);
		if (paragraphs.isEmpty() || !styles.question().equals(styleOf(paragraphs.get(0)))) {
			throw new IllegalStateException("템플릿 첫 문단이 문항 머리 스타일이 아닙니다.");
		}
		Element first = paragraphs.get(0);
		Map<ParagraphStyle, Element> samples = findSamples(paragraphs.subList(1, paragraphs.size()), styles);
		XmlSupport.removeAll(paragraphs.subList(1, paragraphs.size()));

		fillHeader(first, doc.headerTitle(), header);

		List<Out> outs = layout(doc, styles, header);
		replaceText(first, outs.get(0).runs());
		first.setAttribute("paraPrIDRef", header.paraPr(outs.get(0).style().paraPr(), outs.get(0).source()));
		for (Out out : outs.subList(1, outs.size())) {
			Element p = build(samples.get(out.style()), out.runs());
			// 정렬·들여쓰기를 바꾼 문단은 역할 문단 모양을 복제한 것 (역할이 정해진 뒤 여기서 만들어 쓰지 않는 서식이 생기지 않게)
			p.setAttribute("paraPrIDRef", header.paraPr(out.style().paraPr(), out.source()));
			root.appendChild(p);
		}
	}

	/**
	 * 출력할 문단 하나. runs가 null이면 견본(빈 줄)을 그대로 복제한다.
	 * source는 문단 모양(정렬·들여쓰기)을 가진 원래 문단 (빈 줄·정답이면 null)
	 */
	private record Out(ParagraphStyle style, List<Run> runs, Paragraph source) {

		Out(ParagraphStyle style, List<Run> runs) {
			this(style, runs, null);
		}
	}

	private record Run(String text, String charPr) {
	}

	/** 문항·정답을 역할 문단 순서로 펼친다 */
	private static List<Out> layout(ExamDocument doc, ExamStyles s, HeaderStyles h) {
		List<Out> outs = new ArrayList<>();
		for (Question q : doc.questions()) {
			List<Run> prompt = runs(q.prompt(), s.chars(), true, h);
			if (!q.source().isBlank()) {
				prompt.add(new Run(" [" + q.source() + "]", s.chars().bold()));
			}
			outs.add(new Out(s.question(), merge(prompt), q.prompt()));

			List<Block> blocks = q.body().stream().filter(b -> !b.paragraphs().isEmpty()).toList();
			for (int b = 0; b < blocks.size(); b++) {
				if (b > 0) {
					outs.add(new Out(s.gap(), null));
				}
				for (Paragraph p : blocks.get(b).paragraphs()) {
					outs.add(new Out(s.body(), merge(runs(p, s.chars(), false, h)), p));
				}
			}

			if (q.choices().isEmpty()) {
				// 선택지가 없으면 마지막 본문 문단이 다음 문단과 붙어 있지 않은(keepWithNext 아님) 스타일을 쓴다
				Out last = outs.get(outs.size() - 1);
				if (last.style().equals(s.body())) {
					outs.set(outs.size() - 1, new Out(s.bodyLast(), last.runs(), last.source()));
				}
			} else {
				if (!blocks.isEmpty()) {
					outs.add(new Out(s.gap(), null));
				}
				for (int c = 0; c < q.choices().size(); c++) {
					ParagraphStyle style = c == q.choices().size() - 1 ? s.choiceLast() : s.choice();
					Paragraph choice = q.choices().get(c);
					outs.add(new Out(style, merge(runs(choice, s.chars(), false, h)), choice));
				}
			}
			outs.add(new Out(s.spacer(), null));
		}

		if (!doc.answers().isEmpty()) {
			outs.add(new Out(s.spacer(), null));
			outs.add(new Out(s.answer(), List.of(new Run(s.answerHeading(), s.chars().heading()))));
			outs.add(new Out(s.spacer(), null));
			for (Answer a : doc.answers()) {
				List<Run> runs = new ArrayList<>();
				runs.add(new Run(a.number() + " 번", s.chars().bold()));
				runs.add(new Run(" - ", s.chars().normal()));
				runs.add(new Run(a.answer(), s.chars().bold()));
				if (!a.explanation().isBlank()) {
					runs.add(new Run("   " + a.explanation(), s.chars().normal()));
				}
				outs.add(new Out(s.answer(), runs));
			}
		}
		return outs;
	}

	/**
	 * 글자 조각 → run. 문항 머리처럼 문단 전체가 굵은 역할이면 allBold.
	 * 밑줄·굵게는 템플릿 글자 서식 4가지 중 하나를 고르고, 다른 글자 모양(기울임 등)이 있으면 그 서식을 복제한 것을 쓴다
	 */
	private static List<Run> runs(Paragraph p, ExamStyles.CharStyles chars, boolean allBold, HeaderStyles h) {
		List<Run> runs = new ArrayList<>();
		for (Span span : p.spans()) {
			boolean bold = allBold || span.bold();
			String charPr = bold
					? (span.underline() ? chars.boldUnderline() : chars.bold())
					: (span.underline() ? chars.underline() : chars.normal());
			runs.add(new Run(span.text(), h.charPr(charPr, span)));
		}
		return runs;
	}

	/** 서식이 같은 이웃 run을 합친다 */
	private static List<Run> merge(List<Run> runs) {
		List<Run> merged = new ArrayList<>();
		for (Run r : runs) {
			if (r.text().isEmpty()) {
				continue;
			}
			Run last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
			if (last != null && last.charPr().equals(r.charPr())) {
				merged.set(merged.size() - 1, new Run(last.text() + r.text(), r.charPr()));
			} else {
				merged.add(r);
			}
		}
		return merged;
	}

	/** 역할마다 첫 번째 견본 문단. 구역 설정·컨트롤이 든 문단은 견본으로 쓰지 않는다 */
	private static Map<ParagraphStyle, Element> findSamples(List<Element> paragraphs, ExamStyles s) {
		List<ParagraphStyle> needed = List.of(s.question(), s.body(), s.bodyLast(), s.gap(), s.choice(),
				s.choiceLast(), s.spacer(), s.answer());
		Map<ParagraphStyle, Element> samples = new HashMap<>();
		for (Element p : paragraphs) {
			ParagraphStyle style = styleOf(p);
			if (needed.contains(style) && !samples.containsKey(style)
					&& XmlSupport.descendants(p, "ctrl").isEmpty() && XmlSupport.descendants(p, "secPr").isEmpty()) {
				samples.put(style, p);
			}
		}
		for (ParagraphStyle style : needed) {
			if (!samples.containsKey(style)) {
				throw new IllegalStateException("템플릿에 견본 문단이 없습니다: paraPr " + style.paraPr() + " / style " + style.style());
			}
		}
		return samples;
	}

	/** 견본을 복제하고 run을 새로 만든다. runs가 null이면 견본 그대로 */
	private static Element build(Element sample, List<Run> runs) {
		Element p = (Element) sample.cloneNode(true);
		if (runs == null) {
			return p;
		}
		Element runSample = XmlSupport.children(sample, RUN).stream().findFirst()
				.orElseGet(() -> XmlSupport.createSibling(sample, RUN));
		XmlSupport.removeAll(XmlSupport.children(p, RUN));
		XmlSupport.removeAll(XmlSupport.children(p, LINESEG));
		appendRuns(p, runSample, runs);
		return p;
	}

	/** 첫 문단: ctrl·secPr는 남기고 직계 run의 글자만 바꾼다 */
	private static void replaceText(Element first, List<Run> runs) {
		Element runSample = XmlSupport.children(first, RUN).get(0);
		for (Element run : XmlSupport.children(first, RUN)) {
			XmlSupport.removeAll(XmlSupport.children(run, TEXT));
			if (!run.hasChildNodes()) {
				first.removeChild(run);
			}
		}
		XmlSupport.removeAll(XmlSupport.children(first, LINESEG));
		appendRuns(first, runSample, runs);
	}

	private static void appendRuns(Element p, Element runSample, List<Run> runs) {
		if (runs.isEmpty()) {
			runs = List.of(new Run("", runSample.getAttribute(CHAR_REF)));
		}
		for (Run r : runs) {
			Element run = (Element) runSample.cloneNode(false); // 속성만 복사
			run.setAttribute(CHAR_REF, r.charPr());
			if (!r.text().isEmpty()) {
				Element t = XmlSupport.createSibling(runSample, TEXT);
				t.setTextContent(r.text()); // & < 는 직렬화할 때 자동 이스케이프
				run.appendChild(t);
			}
			p.appendChild(run);
		}
	}

	/**
	 * 머리말 글자: 첫 t에 시험 이름을 넣고 나머지 t는 비운다. 한 줄에 안 들어갈 것 같으면 첫 t의 글자 크기를 줄인다
	 * (글자 서식을 복제해 크기만 바꾼다. HeaderTitleFit).
	 * [확인 필요] 샘플 머리말은 run 2개(서식이 다름)로 나뉘어 있다. 한글에서 보고 처리 방식을 정한다
	 */
	private static void fillHeader(Element first, String title, HeaderStyles styles) {
		for (Element header : XmlSupport.descendants(first, "header")) {
			List<Element> texts = XmlSupport.descendants(header, TEXT);
			for (int i = 0; i < texts.size(); i++) {
				texts.get(i).setTextContent(i == 0 ? title : "");
			}
			if (!texts.isEmpty() && texts.get(0).getParentNode() instanceof Element run) {
				fitTitle(run, title, header, styles);
			}
			for (Element p : XmlSupport.descendants(header, P)) {
				if (!XmlSupport.descendants(p, TEXT).isEmpty()) {
					XmlSupport.removeAll(XmlSupport.children(p, LINESEG));
				}
			}
		}
	}

	private static void fitTitle(Element run, String title, Element header, HeaderStyles styles) {
		String charPr = run.getAttribute(CHAR_REF);
		List<Element> subLists = XmlSupport.children(header, "subList");
		if (charPr.isEmpty() || subLists.isEmpty() || subLists.get(0).getAttribute("textWidth").isEmpty()) {
			return;
		}
		float base = styles.height(charPr);
		float width = Integer.parseInt(subLists.get(0).getAttribute("textWidth")) / 100f;
		float size = HeaderTitleFit.size(title, base, width);
		if (size < base) {
			run.setAttribute(CHAR_REF, styles.charPr(charPr, new Span(title, false, false, false, false, false, null, size, null)));
		}
	}

	private static ParagraphStyle styleOf(Element p) {
		return new ParagraphStyle(p.getAttribute("paraPrIDRef"), p.getAttribute("styleIDRef"));
	}
}
