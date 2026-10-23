package cloud.fhwang.chaewon_t.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.fhwang.chaewon_t.extract.ExamDocument.Answer;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Block;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Paragraph;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Question;

class ExamValidatorTest {

	@Test
	void 문제가_없으면_경고도_없다() {
		ExamDocument doc = new ExamDocument("t",
				List.of(multipleChoice(1, 5), inlineChoices(2), subjective(3)),
				List.of(new Answer(1, "①", ""), new Answer(2, "②", ""), new Answer(3, "a", "")));

		assertThat(ExamValidator.warnings(doc)).isEmpty();
	}

	@Test
	void 선택지_수와_정답_번호를_확인한다() {
		ExamDocument doc = new ExamDocument("t",
				List.of(multipleChoice(2, 3), multipleChoice(4, 5)),
				List.of(new Answer(2, "①", ""), new Answer(9, "①", "")));

		assertThat(ExamValidator.warnings(doc)).containsExactly(
				"문항 번호가 1번이 아니라 2번부터 시작합니다.",
				"2번: 선택지가 3개입니다.",
				"3번 다음이 4번입니다.",
				"정답이 없는 문항: 4",
				"문항이 없는 정답: 9");
	}

	@Test
	void 문항이나_정답이_없으면_알린다() {
		assertThat(ExamValidator.warnings(new ExamDocument("t", List.of(), List.of())))
				.containsExactly("문항을 찾지 못했습니다.");
		assertThat(ExamValidator.warnings(new ExamDocument("t", List.of(multipleChoice(1, 5)), List.of())))
				.containsExactly("정답·해설을 찾지 못했습니다.");
	}

	@Test
	void 머리말이_줄여도_한_줄에_안_들어갈_만큼_길면_알린다() {
		ExamDocument fits = new ExamDocument("25년 가나고1 2학기 기말고사 교과서 공통영어2 ABC(가) 4과 기출문제",
				List.of(multipleChoice(1, 5)), List.of(new Answer(1, "①", "")));
		ExamDocument tooLong = new ExamDocument("가".repeat(50), fits.questions(), fits.answers());

		assertThat(ExamValidator.warnings(fits)).isEmpty(); // 글자를 줄이면 들어간다
		assertThat(ExamValidator.warnings(tooLong)).containsExactly("머리말이 너무 길어 두 줄로 넘어갑니다. 줄여 주세요.");
	}

	@Test
	void 머리말_기본값은_PDF_제목에서_연도_학교_학기만_뽑는다() {
		assertThat(ExamPdfReader.shortTitle("가나고1 교과서 1과+2과(Further Reading) (26 2학기 중간) 기출문제"))
				.isEqualTo("26년 가나고1 (2학기 중간)");
		assertThat(ExamPdfReader.shortTitle("모양이 다른 제목")).isEqualTo("모양이 다른 제목");
	}

	private static Question multipleChoice(int n, int choices) {
		List<Paragraph> cs = java.util.stream.IntStream.range(0, choices).mapToObj(i -> Paragraph.of("c" + i)).toList();
		return new Question(n, Paragraph.of("질문"), "1-1", List.of(new Block(List.of(Paragraph.of("본문")))), cs);
	}

	private static Question inlineChoices(int n) {
		return new Question(n, Paragraph.of("들어가기에 적절한 곳은?"), "1-1",
				List.of(new Block(List.of(Paragraph.of("a ( ① ) b ( ② ) c ( ③ ) d ( ④ ) e ( ⑤ )")))), List.of());
	}

	private static Question subjective(int n) {
		return new Question(n, Paragraph.of("(주관식) 쓰시오."), "1-1",
				List.of(new Block(List.of(Paragraph.of("[조건]"), Paragraph.of("① 두 단어")))), List.of());
	}
}
