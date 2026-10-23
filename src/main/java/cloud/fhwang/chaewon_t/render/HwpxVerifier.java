package cloud.fhwang.chaewon_t.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

/**
 * 생성한 HWPX를 다시 읽어 검사한다 (CLAUDE.md HWPX 생성 규칙 8).
 *  - 바꾼 XML이 파싱되는지
 *  - 본문의 paraPrIDRef / charPrIDRef / styleIDRef가 모두 header.xml에 정의된 id인지 (없는 id를 쓰면 서식이 기본으로 떨어진다)
 *  - {{ 가 남아 있지 않은지
 * 문제 설명에는 항목 이름과 id만 넣는다 (문서 내용을 넣지 않음).
 */
final class HwpxVerifier {

	static final String HEADER = "Contents/header.xml";

	/** 참조 속성 이름 → header.xml에서 그 id를 정의하는 요소 이름 */
	private static final Map<String, String> ID_REFS = Map.of(
			"paraPrIDRef", "paraPr",
			"charPrIDRef", "charPr",
			"styleIDRef", "style");

	private HwpxVerifier() {
	}

	/**
	 * @param modified 바꾼 항목 이름들. 이 중 섹션 XML은 id 참조까지 검사한다
	 * @return 문제 목록. 비어 있으면 통과
	 */
	static List<String> verify(Path hwpx, Collection<String> modified) throws IOException {
		List<String> problems = new ArrayList<>();
		Map<String, Set<String>> definedIds = definedIds(HwpxPackage.read(hwpx, HEADER));

		for (String name : modified) {
			if (!(name.endsWith(".xml") || name.endsWith(".hpf"))) {
				continue;
			}
			byte[] data = HwpxPackage.read(hwpx, name);
			Document dom;
			try {
				dom = XmlSupport.parse(data);
			} catch (IOException e) {
				problems.add(name + ": XML 파싱 실패");
				continue;
			}
			if (isSection(name)) {
				problems.addAll(undefinedRefs(name, dom.getDocumentElement(), definedIds));
				if (new String(data, StandardCharsets.UTF_8).contains("{{")) {
					problems.add(name + ": {{ 가 남아 있음");
				}
			}
		}
		return problems;
	}

	static boolean isSection(String name) {
		return name.matches("Contents/section\\d+\\.xml");
	}

	private static Map<String, Set<String>> definedIds(byte[] header) throws IOException {
		if (header == null) {
			throw new IOException(HEADER + " 없음");
		}
		Element root = XmlSupport.parse(header).getDocumentElement();
		Map<String, Set<String>> ids = new java.util.HashMap<>();
		for (String definer : ID_REFS.values()) {
			Set<String> set = new HashSet<>();
			for (Element e : XmlSupport.descendants(root, definer)) {
				if (e.hasAttribute("id")) {
					set.add(e.getAttribute("id"));
				}
			}
			ids.put(definer, set);
		}
		return ids;
	}

	private static List<String> undefinedRefs(String name, Element root, Map<String, Set<String>> definedIds) {
		Set<String> missing = new TreeSet<>();
		for (Element e : XmlSupport.descendants(root, "*")) {
			NamedNodeMap attrs = e.getAttributes();
			for (int i = 0; i < attrs.getLength(); i++) {
				Attr a = (Attr) attrs.item(i);
				String definer = ID_REFS.get(a.getLocalName() != null ? a.getLocalName() : a.getName());
				if (definer != null && !definedIds.get(definer).contains(a.getValue())) {
					missing.add(a.getName() + "=" + a.getValue());
				}
			}
		}
		return missing.stream().map(m -> name + ": header.xml에 없는 " + m).toList();
	}
}
