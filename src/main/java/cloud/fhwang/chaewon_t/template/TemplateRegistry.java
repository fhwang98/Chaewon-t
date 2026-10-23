package cloud.fhwang.chaewon_t.template;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import cloud.fhwang.chaewon_t.common.AppProperties;
import cloud.fhwang.chaewon_t.common.ConversionException;
import cloud.fhwang.chaewon_t.common.ErrorType;
import cloud.fhwang.chaewon_t.template.ExamStyles.ParagraphStyle;
import tools.jackson.databind.json.JsonMapper;

/**
 * data/templates/{종류}/v{번호}/ 폴더에서 템플릿을 찾는다. 종류마다 번호가 가장 큰 버전을 쓴다.
 *
 * 캐시하지 않고 호출할 때마다 읽는다. 템플릿이 몇 개 안 되고, 재시작 없이 템플릿을 바꿔 넣을 수 있어서다.
 */
@Component
public class TemplateRegistry {

	/** 폴더 이름으로 쓰이므로 영문 소문자·숫자·밑줄만 받는다 (경로 조작 방지) */
	private static final Pattern TYPE_NAME = Pattern.compile("[a-z0-9_]+");
	private static final Pattern VERSION_DIR = Pattern.compile("v(\\d+)");

	private final Path root;
	private final String defaultType;

	public TemplateRegistry(AppProperties props) {
		this.root = props.templateDir().toAbsolutePath().normalize();
		this.defaultType = props.template();
	}

	/** 설정(chaewon.template)에 지정한 기본 템플릿 */
	public Template defaultTemplate() {
		return get(defaultType);
	}

	public Template get(String documentType) {
		if (documentType == null || !TYPE_NAME.matcher(documentType).matches()) {
			throw notFound();
		}
		return latest(root.resolve(documentType)).orElseThrow(TemplateRegistry::notFound);
	}

	private Optional<Template> latest(Path typeDir) {
		if (!Files.isDirectory(typeDir)) {
			return Optional.empty();
		}
		try (Stream<Path> dirs = Files.list(typeDir)) {
			return dirs
					.filter(d -> VERSION_DIR.matcher(d.getFileName().toString()).matches())
					.filter(d -> Files.isRegularFile(d.resolve("mapping.json")))
					.max(Comparator.comparingInt(TemplateRegistry::versionOf))
					.map(TemplateRegistry::load);
		} catch (IOException e) {
			throw new ConversionException(ErrorType.TEMPLATE_INVALID, "템플릿 폴더를 읽지 못했습니다.", e);
		}
	}

	private static Template load(Path versionDir) {
		TemplateMapping mapping;
		try {
			mapping = JsonMapper.shared().readValue(versionDir.resolve("mapping.json"), TemplateMapping.class);
		} catch (RuntimeException e) { // Jackson 3의 JacksonException은 unchecked
			throw invalid("mapping.json을 읽지 못했습니다.", e);
		}
		validate(mapping, versionDir.getParent().getFileName().toString(), versionOf(versionDir));
		return new Template(mapping, versionDir.resolve("template.hwpx"));
	}

	private static void validate(TemplateMapping m, String folderType, int folderVersion) {
		require(folderType.equals(m.documentType()), "documentType이 폴더 이름과 다릅니다.");
		require(m.version() == folderVersion, "version이 폴더 번호와 다릅니다.");

		ExamStyles s = m.styles();
		require(s != null, "styles가 없습니다.");
		for (ParagraphStyle p : new ParagraphStyle[] { s.question(), s.body(), s.bodyLast(), s.gap(), s.choice(),
				s.choiceLast(), s.spacer(), s.answer() }) {
			require(p != null && hasText(p.paraPr()) && hasText(p.style()), "styles의 문단 스타일 id가 비어 있습니다.");
		}
		ExamStyles.CharStyles c = s.chars();
		require(c != null && hasText(c.normal()) && hasText(c.underline()) && hasText(c.bold())
				&& hasText(c.boldUnderline()) && hasText(c.heading()), "styles.chars의 글자 서식 id가 비어 있습니다.");
		require(s.answerHeading() != null, "styles.answerHeading이 없습니다.");

		PdfLayout pdf = m.pdf();
		require(pdf != null && pdf.columnSplit() > 0 && pdf.headerBottom() < pdf.footerTop() && pdf.boxLineMinWidth() > 0,
				"pdf 배치값이 잘못되었습니다.");
	}

	private static boolean hasText(String s) {
		return s != null && !s.isBlank();
	}

	private static int versionOf(Path versionDir) {
		Matcher m = VERSION_DIR.matcher(versionDir.getFileName().toString());
		return m.matches() ? Integer.parseInt(m.group(1)) : -1;
	}

	private static void require(boolean ok, String message) {
		if (!ok) {
			throw invalid(message, null);
		}
	}

	private static ConversionException invalid(String message, Throwable cause) {
		return new ConversionException(ErrorType.TEMPLATE_INVALID, "템플릿 설정 오류: " + message, cause);
	}

	private static ConversionException notFound() {
		return new ConversionException(ErrorType.TEMPLATE_NOT_FOUND, "시험지 템플릿이 없습니다.");
	}
}
