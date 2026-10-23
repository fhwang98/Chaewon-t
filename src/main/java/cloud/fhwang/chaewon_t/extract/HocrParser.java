package cloud.fhwang.chaewon_t.extract;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Tesseract hOCR(XHTML) → 줄·단어 좌표. 좌표 단위는 이미지 px, 원점 왼쪽 위.
 *
 * hOCR은 title 속성에 값을 담는다: 줄 {@code "bbox 110 125 1016 158; baseline 0.001 -7; x_size 34"},
 * 단어 {@code "bbox 110 126 208 152; x_wconf 96"}. baseline의 두 번째 값은 bbox 아래 끝에서 기준선까지의 거리(위쪽이 음수)다.
 */
final class HocrParser {

	/**
	 * 단어 하나. confidence는 Tesseract 확신도 0~100.
	 *
	 * @param spacesBefore 앞 단어와의 사이 공백 수 (텍스트 출력에서 가져온다). -1이면 모름
	 */
	record OcrWord(String text, float x0, float y0, float x1, float y1, int confidence, int spacesBefore) {

		OcrWord(String text, float x0, float y0, float x1, float y1, int confidence) {
			this(text, x0, y0, x1, y1, confidence, -1);
		}
	}

	/** 줄 하나. baseline은 줄 왼쪽 끝의 기준선 y, height는 글자 높이(x_size) */
	record OcrLine(float x0, float y0, float x1, float y1, float baseline, float height, List<OcrWord> words) {
		OcrLine {
			words = List.copyOf(words);
		}
	}

	/** Tesseract가 줄로 쓰는 class들 (본문 줄 외에 제목·캡션·떠 있는 글자로 나눠 붙이기도 한다) */
	private static final Set<String> LINE_CLASSES = Set.of("ocr_line", "ocr_header", "ocr_caption", "ocr_textfloat");
	private static final Pattern BBOX = Pattern.compile("bbox (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+)");
	private static final Pattern BASELINE = Pattern.compile("baseline (-?[\\d.]+) (-?[\\d.]+)");
	private static final Pattern X_SIZE = Pattern.compile("x_size ([\\d.]+)");
	private static final Pattern WCONF = Pattern.compile("x_wconf (\\d+)");

	private HocrParser() {
	}

	static List<OcrLine> parse(InputStream hocr) throws IOException {
		NodeList spans;
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			// hOCR에는 XHTML DOCTYPE이 있어 DOCTYPE 자체는 막을 수 없다. 외부 DTD·엔티티를 읽지 않게만 한다
			f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			f.setFeature("http://xml.org/sax/features/external-general-entities", false);
			f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			f.setXIncludeAware(false);
			f.setExpandEntityReferences(false);
			spans = f.newDocumentBuilder().parse(hocr).getElementsByTagName("span");
		} catch (ParserConfigurationException | SAXException e) {
			throw new IOException("OCR 결과(hOCR)를 읽지 못했습니다.", e);
		}

		List<OcrLine> lines = new ArrayList<>();
		for (int i = 0; i < spans.getLength(); i++) {
			Element span = (Element) spans.item(i);
			if (LINE_CLASSES.contains(span.getAttribute("class"))) {
				OcrLine line = line(span);
				if (line != null) {
					lines.add(line);
				}
			}
		}
		return lines;
	}

	/**
	 * 텍스트 출력(같은 실행의 .txt)에서 단어 사이 공백 수를 가져와 붙인다.
	 * hOCR은 한글을 "다 음 에"처럼 음절마다 단어로 나누면서 띄어쓰기 여부를 버리지만, 텍스트 출력은 "다음에"로 맞게 낸다
	 * (preserve_interword_spaces=1일 때). 두 출력의 줄 순서는 같다. 줄 수나 글자가 다르면 그 줄은 모름(-1)으로 둔다
	 */
	static List<OcrLine> withSpaces(List<OcrLine> lines, String text) {
		List<String> textLines = text.lines().filter(l -> !l.isBlank()).map(HocrParser::cleanText).toList();
		if (textLines.size() != lines.size()) {
			return lines;
		}
		List<OcrLine> result = new ArrayList<>(lines.size());
		for (int i = 0; i < lines.size(); i++) {
			result.add(withSpaces(lines.get(i), textLines.get(i)));
		}
		return result;
	}

	private static OcrLine withSpaces(OcrLine line, String text) {
		String joined = String.join("", line.words().stream().map(OcrWord::text).toList());
		if (!joined.equals(text.replaceAll("\\s", ""))) {
			return line;
		}
		List<OcrWord> words = new ArrayList<>();
		int pos = 0;
		for (OcrWord w : line.words()) {
			int spaces = 0;
			while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
				spaces++;
				pos++;
			}
			pos += w.text().length();
			words.add(new OcrWord(w.text(), w.x0(), w.y0(), w.x1(), w.y1(), w.confidence(), spaces));
		}
		return new OcrLine(line.x0(), line.y0(), line.x1(), line.y1(), line.baseline(), line.height(), words);
	}

	private static OcrLine line(Element span) {
		String title = span.getAttribute("title");
		float[] box = bbox(title);
		if (box == null) {
			return null;
		}
		List<OcrWord> words = new ArrayList<>();
		NodeList children = span.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node n = children.item(i);
			if (n instanceof Element w && "ocrx_word".equals(w.getAttribute("class"))) {
				String text = cleanText(w.getTextContent().strip());
				float[] wb = bbox(w.getAttribute("title"));
				if (!text.isEmpty() && wb != null) {
					words.add(new OcrWord(text, wb[0], wb[1], wb[2], wb[3], number(WCONF, w.getAttribute("title"), 0)));
				}
			}
		}
		if (words.isEmpty()) {
			return null;
		}
		Matcher base = BASELINE.matcher(title);
		float baseline = base.find() ? box[3] + Float.parseFloat(base.group(2)) : box[3];
		float height = X_SIZE.matcher(title).find() ? number(X_SIZE, title, 0) : box[3] - box[1];
		return new OcrLine(box[0], box[1], box[2], box[3], baseline, height, words);
	}

	/**
	 * OCR이 따옴표를 백틱으로 읽은 것(`, ´)은 작은따옴표로 바꾼다. 시험지에 백틱이 쓰일 일은 없다 (2026-10-22 사용자 확인).
	 * hOCR과 텍스트 출력이 같은 글자여야 띄어쓰기 맞춤(withSpaces)이 되므로 둘 다 이걸 거친다
	 */
	static String cleanText(String text) {
		return text.replace('`', '\'').replace('´', '\'');
	}

	private static float[] bbox(String title) {
		Matcher m = BBOX.matcher(title);
		if (!m.find()) {
			return null;
		}
		return new float[] { Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2)),
				Float.parseFloat(m.group(3)), Float.parseFloat(m.group(4)) };
	}

	private static int number(Pattern p, String title, int fallback) {
		Matcher m = p.matcher(title);
		return m.find() ? (int) Float.parseFloat(m.group(1)) : fallback;
	}
}
