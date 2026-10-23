package cloud.fhwang.chaewon_t.template;

import java.nio.file.Files;
import java.nio.file.Path;

/** 등록된 템플릿 1개 (한 종류의 최신 버전) */
public record Template(TemplateMapping mapping, Path hwpxPath) {

	/** template.hwpx가 아직 없으면 추출·검토까지만 되고 생성은 실패한다 */
	public boolean hwpxReady() {
		return Files.isRegularFile(hwpxPath);
	}
}
