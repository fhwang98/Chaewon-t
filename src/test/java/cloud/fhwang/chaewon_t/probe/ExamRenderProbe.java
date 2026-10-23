package cloud.fhwang.chaewon_t.probe;

import java.nio.file.Files;
import java.nio.file.Path;

import cloud.fhwang.chaewon_t.extract.ExamDocument;
import cloud.fhwang.chaewon_t.render.ExamHwpxRenderer;
import cloud.fhwang.chaewon_t.render.SampleExam;

/**
 * 1단계 확인용: 샘플 템플릿에 문항을 채운 HWPX를 만든다. 결과를 한글에서 열어 확인한다.
 *
 * 실행: ./gradlew examRenderProbe -Pfile=samples/template.hwpx                 → 가짜 문항 3개
 *       ./gradlew examRenderProbe -Pfile=samples/template.hwpx,samples/sample.pdf → PDF 문항 전체
 * 결과: probe-out/exam.hwpx
 *
 * 한글에서 볼 것: 문항 번호 자동, 2단, 지문 상자, 밑줄, 특수문자, 머리말 시험 이름, 정답 구역
 */
public class ExamRenderProbe {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("사용법: ExamRenderProbe <template.hwpx>[,<sample.pdf>]");
			return;
		}
		String[] files = args[0].split(",");
		ExamDocument doc = files.length > 1
				? ExamParseProbe.read(Path.of(files[1]))
				: SampleExam.document();

		Path out = Path.of("probe-out/exam.hwpx");
		Files.createDirectories(out.toAbsolutePath().getParent());
		new ExamHwpxRenderer().render(Path.of(files[0]), out, doc, SampleExam.STYLES);
		System.out.println("생성: " + out.toAbsolutePath() + " (문항 " + doc.questions().size() + "개)");
	}
}
