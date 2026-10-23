package cloud.fhwang.chaewon_t.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import cloud.fhwang.chaewon_t.extract.ExamDocument.Paragraph;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Span;

/** OCR 결과는 sample2를 Tesseract로 읽었을 때 실제로 나온 것 */
class KnownPromptsTest {

	@Test
	void OCR이_조금_틀린_문항_머리는_목록의_문장으로_고친다() {
		assertThat(KnownPrompts.match("다음 22 제목으로 가장 적절한 것은?").plainText())
				.isEqualTo("다음 글의 제목으로 가장 적절한 것은?");
		assertThat(KnownPrompts.match("(주관식) 다음 우리말을 주어진 조건에 맞게 바르게 영작하시2.").plainText())
				.isEqualTo("(주관식) 다음 우리말을 주어진 조건에 맞게 바르게 영작하시오.");
	}

	@Test
	void 밑줄도_목록대로_넣는다() {
		Paragraph fixed = KnownPrompts.match("다음 글의 밑줄 친 부분 중, 문맥상 날말의 쓰임이 적절하지않은 것은?");

		assertThat(fixed.spans()).contains(new Span("않은", true));
		assertThat(fixed.plainText()).isEqualTo("다음 글의 밑줄 친 부분 중, 문맥상 낱말의 쓰임이 적절하지 않은 것은?");
	}

	@Test
	void 이미_맞거나_어느_문장인지_모르거나_많이_다르면_그대로() {
		assertThat(KnownPrompts.match("다음 글의 주제로 가장 적절한 것은?")).isNull();   // 이미 맞다
		assertThat(KnownPrompts.match("다음 29 FH Z 가장 적절한 것은?")).isNull();    // 주제로? 제목으로?
		assertThat(KnownPrompts.match("(주관식) Q : Why does a mother cat sometimes pick up kittens?")).isNull();
		assertThat(KnownPrompts.match("글의 흐름으로 보아, 주어진 문장이 들어가기에 가장 적절한")).isNull(); // 뒷부분이 다음 줄
		// 원래 표현이 다른 문항 (목록의 "요약하고자 한다. 빈칸 (A), (B)에"와 5글자 다르다)
		assertThat(KnownPrompts.match("다음 글의 내용을 요약하고자 할 때 빈칸 (A) ~ (ㅇ)에 들어갈 말로 가장 적절한 것은?")).isNull();
	}

	@Test
	void 띄어쓰기만_빠진_문항_머리는_공백만_넣고_원래_서식은_둔다() {
		// 두 줄 문항 머리를 이을 때 "적절하지/않은"이 붙었다 (sample6). PDF의 밑줄 위치("않")는 그대로
		Paragraph prompt = new Paragraph(java.util.List.of(
				new Span("다음 글의 밑줄 친 부분 중, 문맥상 낱말의 쓰임이 적절하지", false, true),
				new Span("않", true, true), new Span("은 것은?", false, true)));

		Paragraph spaced = KnownPrompts.spaced(prompt);

		assertThat(spaced.plainText()).isEqualTo("다음 글의 밑줄 친 부분 중, 문맥상 낱말의 쓰임이 적절하지 않은 것은?");
		assertThat(spaced.spans()).containsExactly(
				new Span("다음 글의 밑줄 친 부분 중, 문맥상 낱말의 쓰임이 적절하지 ", false, true),
				new Span("않", true, true), new Span("은 것은?", false, true));
		assertThat(KnownPrompts.spaced(new Paragraph(java.util.List.of(new Span("다음 글의 주제로 가장 적절한 것은?", false)))))
				.isNull(); // 이미 맞다
		assertThat(KnownPrompts.spaced(new Paragraph(java.util.List.of(new Span("다음글의 주제는?", false)))))
				.isNull(); // 목록에 없는 문장
	}

	@Test
	void 편집_거리() {
		assertThat(KnownPrompts.distance("주제로", "제목으로")).isEqualTo(3);
		assertThat(KnownPrompts.distance("가나다", "가나다")).isZero();
	}
}
