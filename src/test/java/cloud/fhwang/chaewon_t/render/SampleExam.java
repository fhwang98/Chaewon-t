package cloud.fhwang.chaewon_t.render;

import java.util.ArrayList;
import java.util.List;

import cloud.fhwang.chaewon_t.extract.ExamDocument;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Answer;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Block;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Paragraph;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Question;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Span;
import cloud.fhwang.chaewon_t.template.ExamStyles;
import cloud.fhwang.chaewon_t.template.ExamStyles.CharStyles;
import cloud.fhwang.chaewon_t.template.ExamStyles.ParagraphStyle;

/**
 * 테스트·probe 공용: 샘플 템플릿(samples/template.hwpx)의 스타일 id와 가짜 시험지.
 * 테스트 템플릿 src/test/resources/exam-template.hwpx는 샘플에서 역할별 문단만 남기고 글자·메타·그림을 가짜로 바꾼 것이다.
 */
public final class SampleExam {

	public static final ExamStyles STYLES = new ExamStyles(
			new ParagraphStyle("11", "1"),
			new ParagraphStyle("12", "2"),
			new ParagraphStyle("15", "2"),
			new ParagraphStyle("13", "0"),
			new ParagraphStyle("14", "4"),
			new ParagraphStyle("16", "4"),
			new ParagraphStyle("1", "0"),
			new ParagraphStyle("1", "0"),
			new CharStyles("12", "18", "14", "19", "5"),
			"< 정 답 >");

	private SampleExam() {
	}

	/** 객관식(밑줄 포함) / 주어진 문장 + 지문(선택지 없음) / 주관식([조건]) 3문항과 정답 */
	public static ExamDocument document() {
		Question q1 = new Question(1,
				new Paragraph(List.of(new Span("다음 글의 내용과 일치하지 ", false), new Span("않는", true), new Span(" 것은?", false))),
				"1-2",
				List.of(new Block(List.of(
						new Paragraph(List.of(new Span("Fake news is a ", false), new Span("deliberate", true),
								new Span(" attempt to manipulate people. A&B <tag> \"quote\"", false))),
						Paragraph.of("It is very common for fake news to spread during states of emergency.")))),
				choices("Gina accidentally helped spread fake news.", "Fake news is created to gain attention.",
						"People stayed in shelters.", "A fake message claimed that Ambon might sink.",
						"The government clarified the rumors were false."));
		Question q2 = new Question(2,
				Paragraph.of("글의 흐름으로 보아, 주어진 문장이 들어가기에 가장 적절한 곳은?"),
				"1-3",
				List.of(new Block(List.of(new Paragraph(List.of(new Span("(A)", false, true),
						new Span(" Moreover, people are inclined to ", false), new Span("believe", true, true),
						new Span(" information.", false))))),
						new Block(List.of(Paragraph.of("When information is astonishing, people want to share it. ( ① ) Also, ( ② ) fake news goes viral. ( ③ ) ( ④ ) ( ⑤ )")))),
				List.of());
		Question q3 = new Question(3,
				Paragraph.of("(주관식) 다음 빈칸에 들어갈 단어를 조건에 맞추어 쓰시오."),
				"1-1",
				List.of(new Block(List.of(Paragraph.of("A. That's because she was also a spreader of ____________."))),
						new Block(List.of(Paragraph.of("[조건]"), Paragraph.of("① 글에 있는 단어들을 사용하여 작성할 것"),
								Paragraph.of("② 두 단어로 쓸 것")))),
				List.of());
		List<Answer> answers = List.of(
				new Answer(1, "③", "정부 관계자의 경고 때문이 아니라 가짜 뉴스 때문이다."),
				new Answer(2, "①", ""),
				new Answer(3, "fake news", "Gina 역시 가짜 뉴스를 퍼뜨린 사람이었다."));
		return new ExamDocument("가나고1 (2학기 중간) 테스트", List.of(q1, q2, q3), answers);
	}

	private static List<Paragraph> choices(String... texts) {
		List<Paragraph> result = new ArrayList<>();
		String marks = "①②③④⑤";
		for (int i = 0; i < texts.length; i++) {
			result.add(Paragraph.of(marks.charAt(i) + " " + texts[i]));
		}
		return result;
	}
}
