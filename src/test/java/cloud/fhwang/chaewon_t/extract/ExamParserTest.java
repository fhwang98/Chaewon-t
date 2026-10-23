package cloud.fhwang.chaewon_t.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.fhwang.chaewon_t.extract.ExamDocument.Answer;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Paragraph;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Question;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Span;
import cloud.fhwang.chaewon_t.extract.PdfLine.Glyph;

/**
 * 줄 좌표는 샘플 PDF와 비슷하게 만든다: 단 왼쪽 48, 본문 52, 첫 줄 들여쓰기 59, 줄 간격 12.8, 덩어리 사이 25.5.
 * 글자 하나 너비 5pt.
 */
class ExamParserTest {

	static final float STEP = 12.8f;
	static final float GAP = 25.5f;

	final List<PdfLine> lines = new ArrayList<>();
	int page = 1;
	int column = 0;
	float y = 85;

	@Test
	void 객관식_문항과_정답을_나눈다() {
		add(48, "1. 다음 글의 <u>않는</u> 것은? [1-2]");
		add(59, "Fake news spreads");
		add(52, "very fast online and");
		add(52, "hurts people.");
		add(59, "It is common.");
		gap();
		add(48, "① first choice");
		add(48, "② second choice that");
		add(59, "wraps");
		add(48, "③ third");
		add(48, "④ fourth");
		add(48, "⑤ fifth");
		nextPage();
		add(48, "< 정 답 >");
		add(48, "1 번 - ③   해설은 이렇");
		add(48, "다.");

		ExamDocument doc = ExamParser.parse(lines, "제목");

		assertThat(doc.headerTitle()).isEqualTo("제목");
		Question q = doc.questions().get(0);
		assertThat(q.number()).isEqualTo(1);
		assertThat(q.prompt().spans()).containsExactly(
				new Span("다음 글의 ", false), new Span("않는", true), new Span(" 것은?", false));
		assertThat(q.source()).isEqualTo("1-2");
		assertThat(q.body()).hasSize(1);
		assertThat(texts(q.body().get(0).paragraphs()))
				.containsExactly("Fake news spreads very fast online and hurts people.", "It is common.");
		assertThat(texts(q.choices()))
				.containsExactly("① first choice", "② second choice that wraps", "③ third", "④ fourth", "⑤ fifth");
		assertThat(doc.answers()).containsExactly(new Answer(1, "③", "해설은 이렇다."));
	}

	@Test
	void 출처가_다음_줄로_넘어간_질문을_잇고_출처_없는_질문은_본문을_삼키지_않는다() {
		add(48, "1. 다음 글을 읽고 물음에 답하");
		add(61, "시오. [1-1]");
		add(59, "Body one.");
		gap();
		add(48, "2. 출처 없는 질문");
		add(59, "Body two.");

		List<Question> qs = ExamParser.parse(lines, "").questions();

		assertThat(qs.get(0).prompt().plainText()).isEqualTo("다음 글을 읽고 물음에 답하시오.");
		assertThat(qs.get(0).source()).isEqualTo("1-1");
		assertThat(qs.get(1).prompt().plainText()).isEqualTo("출처 없는 질문");
		assertThat(qs.get(1).source()).isEmpty();
		assertThat(texts(qs.get(1).body().get(0).paragraphs())).containsExactly("Body two.");
	}

	@Test
	void 주관식의_조건_번호는_본문이고_내어쓰기_줄은_이어_붙인다() {
		add(48, "1. (주관식) 빈칸을 채우시오. [1-1]");
		add(59, "Passage text.");
		gap();
		add(55, "[조건]");
		add(55, "① 글에 있는 단어들을 사용하여 영");
		add(64, "작할 것");
		add(55, "② 두 단어로 쓸 것");

		Question q = ExamParser.parse(lines, "").questions().get(0);

		assertThat(q.subjective()).isTrue();
		assertThat(q.choices()).isEmpty();
		assertThat(q.body()).hasSize(2);
		assertThat(texts(q.body().get(1).paragraphs()))
				.containsExactly("[조건]", "① 글에 있는 단어들을 사용하여 영작할 것", "② 두 단어로 쓸 것");
	}

	@Test
	void 한_줄에_있는_선택지_여러_개를_나눈다() {
		add(48, "1. 순서로 가장 적절한 것은? [2-1]");
		add(59, "Given text.");
		gap();
		add(48, "① (A) - (C) - (B)     ② (B) - (A) - (C)");
		add(48, "③ (B) - (C) - (A)     ④ (C) - (A) - (B)");
		add(48, "⑤ (C) - (B) - (A)");

		Question q = ExamParser.parse(lines, "").questions().get(0);

		assertThat(texts(q.choices())).containsExactly(
				"① (A) - (C) - (B)", "② (B) - (A) - (C)", "③ (B) - (C) - (A)", "④ (C) - (A) - (B)", "⑤ (C) - (B) - (A)");
	}

	@Test
	void 번호가_이어지지_않는_N_점_줄은_본문으로_본다() {
		add(48, "1. 질문 [1-1]");
		add(59, "Intro.");
		add(48, "3. Not a question.");
		gap();
		add(48, "2. 다음 질문 [1-2]");

		List<Question> qs = ExamParser.parse(lines, "").questions();

		assertThat(qs).extracting(Question::number).containsExactly(1, 2);
		assertThat(texts(qs.get(0).body().get(0).paragraphs())).containsExactly("Intro.", "3. Not a question.");
	}

	@Test
	void 단이_바뀌어도_지문이_이어진다() {
		add(48, "1. 질문 [1-1]");
		add(59, "The passage starts");
		add(52, "here and goes on to");
		column = 1;
		y = 85;
		add(313, "the next column.");
		gap();
		add(309, "① a");

		Question q = ExamParser.parse(lines, "").questions().get(0);

		assertThat(texts(q.body().get(0).paragraphs()))
				.containsExactly("The passage starts here and goes on to the next column.");
		assertThat(texts(q.choices())).containsExactly("① a");
	}

	@Test
	void 본문의_굵은_글자를_살린다() {
		add(48, "1. 순서로 배열한 것은? [1-1]");
		add(59, "Given text.");
		gap();
		add(52, "<b>(A)</b> One <b><u>key</u></b> idea.");

		Question q = ExamParser.parse(lines, "").questions().get(0);

		assertThat(q.body().get(1).paragraphs().get(0).spans()).containsExactly(
				new Span("(A)", false, true), new Span(" One ", false, false),
				new Span("key", true, true), new Span(" idea.", false, false));
	}

	@Test
	void 답과_해설은_공백_두_칸_이상으로_나눈다() {
		assertThat(ExamParser.toAnswer(2, "fake news   해설")).isEqualTo(new Answer(2, "fake news", "해설"));
		assertThat(ExamParser.toAnswer(3, "⑤")).isEqualTo(new Answer(3, "⑤", ""));
	}

	@Test
	void 공백이_한_칸이어도_동그라미_번호_답_뒤에서_나눈다() {
		assertThat(ExamParser.toAnswer(75, "② '광고에서' 해설")).isEqualTo(new Answer(75, "②", "'광고에서' 해설"));
		assertThat(ExamParser.toAnswer(76, "②, ④ 해설")).isEqualTo(new Answer(76, "②, ④", "해설"));
		assertThat(ExamParser.toAnswer(3, "(9) 무한 스크롤")).isEqualTo(new Answer(3, "(9)", "무한 스크롤")); // OCR이 못 읽은 번호
		assertThat(ExamParser.toAnswer(4, "two words")).isEqualTo(new Answer(4, "two words", ""));
	}

	@Test
	void 정답_구역에서는_번을_잘못_읽어도_다음_번호면_정답으로_본다() {
		add(48, "1. 다음 글의 제목은? [1]");
		add(52, "Body.");
		nextPage();
		add(48, "1 번 - ① 해설");
		add(48, "2H - ③ 해설");    // "2 번"을 OCR이 "2H"로 읽었다
		add(48, "5H - 이어지는 해설"); // 다음 번호가 아니면 앞 해설에 붙인다

		List<Answer> answers = ExamParser.parse(lines, "").answers();

		assertThat(answers).extracting(Answer::number).containsExactly(1, 2);
		assertThat(answers.get(1).explanation()).isEqualTo("해설5H - 이어지는 해설"); // 한글 뒤 줄바꿈은 붙인다 (Rich.join)
	}

	@Test
	void 번호를_못_읽은_문항이_있어도_질문처럼_보이는_다음_번호는_받는다() {
		add(48, "1. 다음 글의 제목은? [1]");
		add(52, "Body one.");
		add(48, "3. 다음 빈칸에 들어갈 말은? [2]"); // 2번 머리를 OCR이 못 읽었다
		add(52, "Body three.");
		add(48, "5. Not a question line");          // 질문 표시가 없으면 건너뛴 번호로 받지 않는다

		List<Question> questions = ExamParser.parse(lines, "").questions();

		assertThat(questions).extracting(Question::number).containsExactly(1, 3);
	}

	@Test
	void 덩어리_간격이_좁아도_1번부터_5번까지_줄을_시작하면_선택지다() {
		// sample5: 지문 상자 바로 아래 선택지가 줄 간격 1.4배라 지문 끝 줄에 붙었다. 한 줄에 둘이면 공백 한 칸이어도 나눈다
		add(48, "1. 다음 글의 순서로 가장 적절한 것은? [1-1]");
		add(52, "Passage line one");
		add(52, "ends here.");
		y += STEP * 0.4f;
		add(48, "① (A)-(C)-(B) ② (B)-(A)-(C)");
		add(48, "③ (B)-(C)-(A) ④ (C)-(A)-(B)");
		add(48, "⑤ (C)-(B)-(A)");

		Question q = ExamParser.parse(lines, "").questions().get(0);

		assertThat(texts(q.body().get(0).paragraphs())).containsExactly("Passage line one", "ends here.");
		assertThat(texts(q.choices())).containsExactly(
				"① (A)-(C)-(B)", "② (B)-(A)-(C)", "③ (B)-(C)-(A)", "④ (C)-(A)-(B)", "⑤ (C)-(B)-(A)");
	}

	@Test
	void 지문_안의_동그라미_번호는_선택지가_아니다() {
		add(48, "1. 다음 글에서 전체 흐름과 관계 없는 문장은? [1-1]");
		add(52, "Some text here and");
		add(52, "① more text, ② other");
		add(52, "③ and ④ and ⑤ end.");

		Question q = ExamParser.parse(lines, "").questions().get(0);

		assertThat(q.choices()).isEmpty();
	}

	@Test
	void 문항_번호가_단_왼쪽_끝에서_조금_오른쪽이어도_문항_시작이다() {
		// sample6 58번: 다른 문항보다 2pt 오른쪽
		add(48, "1. 첫 문항? [1-1]");
		add(52, "body");
		gap();
		add(50, "2. 둘째 문항? [1-2]");
		add(52, "body");

		assertThat(ExamParser.parse(lines, "").questions()).extracting(Question::number).containsExactly(1, 2);
	}

	private void add(float x, String marked) {
		List<Glyph> glyphs = new ArrayList<>();
		boolean underline = false;
		boolean bold = false;
		float gx = x;
		for (int i = 0; i < marked.length(); i++) {
			if (marked.startsWith("<b>", i)) {
				bold = true;
				i += 2;
				continue;
			}
			if (marked.startsWith("</b>", i)) {
				bold = false;
				i += 3;
				continue;
			}
			if (marked.startsWith("<u>", i)) {
				underline = true;
				i += 2;
				continue;
			}
			if (marked.startsWith("</u>", i)) {
				underline = false;
				i += 3;
				continue;
			}
			glyphs.add(new Glyph(String.valueOf(marked.charAt(i)), gx, 5, underline, bold));
			gx += 5;
		}
		lines.add(new PdfLine(page, column, x, y, 5, glyphs));
		y += STEP;
	}

	private void gap() {
		y += GAP - STEP;
	}

	private void nextPage() {
		page++;
		column = 0;
		y = 85;
	}

	private static List<String> texts(List<Paragraph> paragraphs) {
		return paragraphs.stream().map(Paragraph::plainText).toList();
	}
}
