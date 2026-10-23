package cloud.fhwang.chaewon_t.render;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import cloud.fhwang.chaewon_t.extract.ExamDocument.Paragraph;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Span;

/**
 * 에디터에서 넣은 글자·문단 모양을 header.xml에 서식으로 더한다.
 *
 * 역할이 쓰는 템플릿 서식(예: 본문 charPr 12, paraPr 12)을 복제해 바뀐 값만 고치고, 기존 마지막 id 다음 번호로 붙인다.
 * itemCnt도 함께 고친다 (CLAUDE.md 규칙 6: id가 끊기면 그 서식이 전부 기본으로 떨어진다). 같은 조합은 한 번만 만든다.
 *
 * header.xml 구조 (템플릿에서 확인):
 *   charPr: height(1/100pt) textColor 속성, 자식 fontRef ratio spacing relSz offset [italic] [bold] underline strikeout outline shadow
 *           [supscript|subscript]. 취소선은 strikeout의 shape(NONE → SOLID)
 *   paraPr: align horizontal, margin(intent·left)은 hp:switch 안에 두 벌 — 새 한글용 case와 옛 한글용 default(값이 두 배)
 *
 * [확인 필요] 복제한 서식을 한글이 그대로 보여 주는지(특히 italic·첨자 요소 위치). 2026-10-21 아직 한글에서 열어 보지 않았다
 */
final class HeaderStyles {

	private static final Map<String, String> ALIGN = Map.of(
			"left", "LEFT", "center", "CENTER", "right", "RIGHT", "justify", "JUSTIFY");

	private final Element charProperties;
	private final Element paraProperties;
	private final Map<String, Element> charById = new HashMap<>();
	private final Map<String, Element> paraById = new HashMap<>();
	private final Map<String, String> made = new HashMap<>();
	private int nextChar;
	private int nextPara;

	HeaderStyles(Document header) {
		Element root = header.getDocumentElement();
		this.charProperties = XmlSupport.descendants(root, "charProperties").get(0);
		this.paraProperties = XmlSupport.descendants(root, "paraProperties").get(0);
		for (Element e : XmlSupport.children(charProperties, "charPr")) {
			charById.put(e.getAttribute("id"), e);
			nextChar = Math.max(nextChar, Integer.parseInt(e.getAttribute("id")) + 1);
		}
		for (Element e : XmlSupport.children(paraProperties, "paraPr")) {
			paraById.put(e.getAttribute("id"), e);
			nextPara = Math.max(nextPara, Integer.parseInt(e.getAttribute("id")) + 1);
		}
	}

	/** 글자 조각의 charPr id. 밑줄·굵게 말고 다른 모양이 없으면 base 그대로 */
	String charPr(String baseId, Span span) {
		if (!span.hasExtraFormat()) {
			return baseId;
		}
		String key = "c" + baseId + "|" + span.italic() + span.strike() + span.script() + span.size() + span.color();
		return made.computeIfAbsent(key, k -> {
			Element pr = cloneOf(charById, baseId, "charPr");
			String id = String.valueOf(nextChar++);
			pr.setAttribute("id", id);
			if (span.size() != null) {
				pr.setAttribute("height", String.valueOf(Math.round(span.size() * 100)));
			}
			if (span.color() != null) {
				pr.setAttribute("textColor", span.color().toUpperCase(Locale.ROOT));
			}
			if (span.italic() && XmlSupport.children(pr, "italic").isEmpty()) {
				Element before = first(pr, "bold", "underline", "strikeout", "outline", "shadow");
				pr.insertBefore(XmlSupport.createSibling(pr, "italic"), before);
			}
			if (span.strike()) {
				List<Element> strike = XmlSupport.children(pr, "strikeout");
				if (strike.isEmpty()) {
					Element s = XmlSupport.createSibling(pr, "strikeout");
					s.setAttribute("color", "#000000");
					strike = List.of(s);
					pr.insertBefore(s, first(pr, "outline", "shadow"));
				}
				strike.get(0).setAttribute("shape", "SOLID");
			}
			if (span.script() != null) {
				XmlSupport.removeAll(XmlSupport.children(pr, "supscript"));
				XmlSupport.removeAll(XmlSupport.children(pr, "subscript"));
				pr.appendChild(XmlSupport.createSibling(pr, "sup".equals(span.script()) ? "supscript" : "subscript"));
			}
			add(charProperties, pr);
			return id;
		});
	}

	/** 글자 서식의 크기 (pt) */
	float height(String charPrId) {
		return Integer.parseInt(cloneOf(charById, charPrId, "charPr").getAttribute("height")) / 100f;
	}

	/** 문단의 paraPr id. 바꾼 문단 모양이 없으면 base 그대로 */
	String paraPr(String baseId, Paragraph p) {
		if (p == null || !p.hasStyle()) {
			return baseId;
		}
		String key = "p" + baseId + "|" + p.align() + "|" + p.indent() + "|" + p.firstLine();
		return made.computeIfAbsent(key, k -> {
			Element pr = cloneOf(paraById, baseId, "paraPr");
			String id = String.valueOf(nextPara++);
			pr.setAttribute("id", id);
			if (p.align() != null) {
				for (Element align : XmlSupport.children(pr, "align")) {
					align.setAttribute("horizontal", ALIGN.get(p.align()));
				}
			}
			for (Element margin : XmlSupport.descendants(pr, "margin")) {
				// 옛 한글용(hp:default) 값은 새 한글용(hp:case)의 두 배로 들어 있다 (템플릿: case 700, default 1400)
				int scale = "default".equals(((Element) margin.getParentNode()).getLocalName()) ? 2 : 1;
				if (p.indent() != null) {
					for (Element left : XmlSupport.children(margin, "left")) {
						int base = Integer.parseInt(left.getAttribute("value"));
						left.setAttribute("value", String.valueOf(base + Math.round(p.indent() * 100) * scale));
					}
				}
				if (p.firstLine() != null) {
					for (Element intent : XmlSupport.children(margin, "intent")) {
						intent.setAttribute("value", String.valueOf(Math.round(p.firstLine() * 100) * scale));
					}
				}
			}
			add(paraProperties, pr);
			return id;
		});
	}

	private static Element cloneOf(Map<String, Element> byId, String id, String what) {
		Element base = byId.get(id);
		if (base == null) {
			throw new IllegalStateException("header.xml에 " + what + " id " + id + "가 없습니다.");
		}
		return (Element) base.cloneNode(true);
	}

	private static void add(Element list, Element pr) {
		list.appendChild(pr);
		list.setAttribute("itemCnt", String.valueOf(XmlSupport.children(list, pr.getLocalName()).size()));
	}

	/** 이름 중 처음으로 있는 자식 (없으면 null = 맨 뒤에 붙인다) */
	private static Element first(Element parent, String... names) {
		for (String name : names) {
			List<Element> found = XmlSupport.children(parent, name);
			if (!found.isEmpty()) {
				return found.get(0);
			}
		}
		return null;
	}
}
