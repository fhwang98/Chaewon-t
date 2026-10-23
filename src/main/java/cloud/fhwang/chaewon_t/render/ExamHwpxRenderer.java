package cloud.fhwang.chaewon_t.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import cloud.fhwang.chaewon_t.common.ConversionException;
import cloud.fhwang.chaewon_t.common.ErrorType;
import cloud.fhwang.chaewon_t.extract.ExamDocument;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Block;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Paragraph;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Question;
import cloud.fhwang.chaewon_t.template.ExamStyles;

/**
 * 시험지 템플릿 HWPX + 시험지 내용 → 새 HWPX.
 *
 * 바꾸는 항목: 본문(section0.xml), 서식(header.xml의 본문 문단 보호 끄기), 문서 정보(content.hpf의 제목·저장자),
 * 미리보기 글(PrvText.txt).
 * 미리보기 그림(PrvImage.png)은 만들 수 없어 템플릿 것이 그대로 남는다.
 */
@Component
public class ExamHwpxRenderer {

	static final String SECTION = "Contents/section0.xml";
	static final String CONTENT_HPF = "Contents/content.hpf";
	static final String PREVIEW_TEXT = "Preview/PrvText.txt";

	/** 한글이 만든 PrvText.txt 크기와 비슷하게 자른다 */
	private static final int PREVIEW_MAX_CHARS = 1000;

	public void render(Path template, Path out, ExamDocument doc, ExamStyles styles) {
		if (!Files.isRegularFile(template)) {
			throw new ConversionException(ErrorType.TEMPLATE_NOT_FOUND, "템플릿 파일(template.hwpx)이 아직 등록되지 않았습니다.");
		}
		if (doc.questions().isEmpty()) {
			throw new ConversionException(ErrorType.RENDER_FAILED, "문항이 없어 시험지를 만들 수 없습니다.");
		}
		try {
			List<String> names = HwpxPackage.names(template);
			if (names.stream().filter(HwpxVerifier::isSection).count() != 1 || !names.contains(SECTION)) {
				throw new IllegalStateException("구역(section)이 하나인 템플릿만 지원합니다.");
			}

			Map<String, byte[]> replaced = new LinkedHashMap<>();
			Document header = allowBodySplit(HwpxPackage.read(template, HwpxVerifier.HEADER), styles);
			Document section = XmlSupport.parse(HwpxPackage.read(template, SECTION));
			ExamSectionWriter.write(section, doc, styles, new HeaderStyles(header)); // 에디터 글자·문단 모양은 header에 서식으로 더해진다
			replaced.put(SECTION, XmlSupport.serialize(section));
			replaced.put(HwpxVerifier.HEADER, XmlSupport.serialize(header));

			byte[] hpf = HwpxPackage.read(template, CONTENT_HPF);
			if (hpf != null) {
				replaced.put(CONTENT_HPF, updateMetadata(hpf, doc.headerTitle()));
			}
			if (names.contains(PREVIEW_TEXT)) {
				replaced.put(PREVIEW_TEXT, previewText(doc).getBytes(StandardCharsets.UTF_8));
			}

			HwpxPackage.write(template, out, replaced);

			List<String> problems = HwpxVerifier.verify(out, replaced.keySet());
			if (!problems.isEmpty()) {
				throw new ConversionException(ErrorType.RENDER_FAILED, "만든 HWPX 검사 실패: " + problems);
			}
		} catch (ConversionException e) {
			deleteQuietly(out);
			throw e;
		} catch (IOException | RuntimeException e) {
			deleteQuietly(out);
			throw new ConversionException(ErrorType.RENDER_FAILED, "HWPX 파일을 만들지 못했습니다.", e);
		}
	}

	/**
	 * 본문 문단 스타일의 "문단 보호"(keepLines)를 끈다.
	 * 템플릿은 지문 한 문단이 단·쪽을 넘지 못하게 되어 있어, PDF의 긴 지문이 통째로 다음 단으로 밀려 빈 공간이 생겼다.
	 * id를 새로 만들지 않고 속성 하나만 바꾸므로 paraPr id 연속성(CLAUDE.md 규칙 6)은 그대로다.
	 */
	private static Document allowBodySplit(byte[] header, ExamStyles styles) throws IOException {
		if (header == null) {
			throw new IOException(HwpxVerifier.HEADER + " 없음");
		}
		Document dom = XmlSupport.parse(header);
		java.util.Set<String> bodyIds = java.util.Set.of(styles.body().paraPr(), styles.bodyLast().paraPr());
		for (Element paraPr : XmlSupport.descendants(dom.getDocumentElement(), "paraPr")) {
			if (bodyIds.contains(paraPr.getAttribute("id"))) {
				for (Element breakSetting : XmlSupport.children(paraPr, "breakSetting")) {
					breakSetting.setAttribute("keepLines", "0");
				}
			}
		}
		return dom;
	}

	/** 문서 정보: 제목은 시험 이름으로, 마지막 저장자는 비운다 (템플릿 원래 값이 남지 않게) */
	private static byte[] updateMetadata(byte[] hpf, String title) throws IOException {
		Document dom = XmlSupport.parse(hpf);
		Element root = dom.getDocumentElement();
		for (Element t : XmlSupport.descendants(root, "title")) {
			t.setTextContent(title);
		}
		for (Element meta : XmlSupport.descendants(root, "meta")) {
			if ("lastsaveby".equals(meta.getAttribute("name"))) {
				meta.setTextContent("");
			}
		}
		return XmlSupport.serialize(dom);
	}

	/** 탐색기 미리보기용 글: 시험 이름과 앞쪽 문항 */
	static String previewText(ExamDocument doc) {
		StringBuilder sb = new StringBuilder(doc.headerTitle()).append("\r\n");
		for (Question q : doc.questions()) {
			sb.append(q.prompt().plainText()).append("\r\n");
			for (Block b : q.body()) {
				sb.append(b.paragraphs().stream().map(Paragraph::plainText).collect(Collectors.joining("\r\n"))).append("\r\n");
			}
			if (sb.length() > PREVIEW_MAX_CHARS) {
				break;
			}
		}
		return sb.length() > PREVIEW_MAX_CHARS ? sb.substring(0, PREVIEW_MAX_CHARS) : sb.toString();
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// 작업 폴더째 정리될 때 지워진다
		}
	}
}
