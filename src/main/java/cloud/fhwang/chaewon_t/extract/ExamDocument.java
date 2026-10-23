package cloud.fhwang.chaewon_t.extract;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PDF에서 뽑은 시험지 = HWPX에 넣을 내용 (중간 모델). 에디터가 저장하는 JSON도 이 모양이다.
 *
 * 크기 제한(@Size)은 에디터 저장(PUT) 요청을 검사할 때 쓴다. 샘플(문항 100개)보다 넉넉하게 잡았다.
 *
 * @param headerTitle 머리말에 들어갈 시험 이름
 */
public record ExamDocument(
		@Size(max = 200) String headerTitle,
		@Size(max = 300) List<@Valid Question> questions,
		@Size(max = 300) List<@Valid Answer> answers) {

	private static final java.util.regex.Pattern SUBJECTIVE = java.util.regex.Pattern.compile("\\((주관식|서술형)\\)");

	public ExamDocument {
		headerTitle = headerTitle == null ? "" : headerTitle;
		questions = questions == null ? List.of() : List.copyOf(questions);
		answers = answers == null ? List.of() : List.copyOf(answers);
	}

	/**
	 * 문항 1개. 번호는 템플릿의 문단 번호 매기기가 자동으로 붙이므로 number는 확인·검증용이다.
	 *
	 * @param prompt  질문 글 (출처 제외)
	 * @param source  [ ] 안의 출처. 없으면 빈 문자열
	 * @param body    빈 줄로 나뉘는 본문 덩어리들 (주어진 문장, 지문, [조건] 등)
	 * @param choices 선택지 ①~⑤ (번호 글자 포함). 주관식이면 비어 있다
	 */
	public record Question(
			int number,
			@Valid Paragraph prompt,
			@Size(max = 200) String source,
			@Size(max = 50) List<@Valid Block> body,
			@Size(max = 20) List<@Valid Paragraph> choices) {

		public Question {
			prompt = prompt == null ? Paragraph.of("") : prompt;
			source = source == null ? "" : source;
			body = body == null ? List.of() : List.copyOf(body);
			choices = choices == null ? List.of() : List.copyOf(choices);
		}

		/** 주관식·서술형이면 선택지가 없다 */
		public boolean subjective() {
			return SUBJECTIVE.matcher(prompt.plainText()).find();
		}
	}

	/** 빈 줄 없이 이어지는 문단 묶음. 템플릿에서는 상자 하나로 보인다 */
	public record Block(@Size(max = 200) List<@Valid Paragraph> paragraphs) {

		public Block {
			paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
		}
	}

	/**
	 * 문단. 정렬·들여쓰기는 에디터에서 바꾼 것만 들어 있고, 없으면(null) 역할(문항·지문·선택지) 기본 모양을 따른다.
	 *
	 * @param align     left, center, right, justify
	 * @param indent    역할 기본 왼쪽 여백에 더할 들여쓰기 (pt)
	 * @param firstLine 첫 줄 들여쓰기 (pt, 음수면 내어쓰기). 역할 기본값 대신 쓴다
	 */
	public record Paragraph(
			@Size(max = 1000) List<@Valid Span> spans,
			@Pattern(regexp = "left|center|right|justify") String align,
			@DecimalMin("0") @DecimalMax("100") Float indent,
			@DecimalMin("-50") @DecimalMax("50") Float firstLine) {

		public Paragraph {
			spans = spans == null ? List.of() : List.copyOf(spans);
		}

		public Paragraph(List<Span> spans) {
			this(spans, null, null, null);
		}

		/** 역할 기본 모양과 다른 문단 모양이 있는지 */
		public boolean hasStyle() {
			return align != null || indent != null || firstLine != null;
		}

		public static Paragraph of(String text) {
			return new Paragraph(List.of(new Span(text, false)));
		}

		/** 서식을 뺀 글자 */
		public String plainText() {
			return spans.stream().map(Span::text).collect(Collectors.joining());
		}
	}

	/**
	 * 같은 서식의 글자 조각. underline·bold에 따라 템플릿의 밑줄·굵게 글자 서식을 쓴다.
	 *
	 * @param uncertain OCR이 자신 없게 읽은 글자 (에디터에서 노란색으로 표시, 고치면 사라진다). HWPX에는 영향 없음.
	 *                  나중에 더한 값이라 없을 수 있다(이전에 저장한 document.json, 화면이 보내지 않은 경우).
	 *                  Jackson 3은 JSON에 없는 boolean 기본형을 오류로 보므로 Boolean으로 받아 없으면 false
	 */
	public record Span(
			@Size(max = 20_000) String text,
			boolean underline,
			boolean bold,
			Boolean uncertain,
			Boolean italic,
			Boolean strike,
			@Pattern(regexp = "sup|sub") String script,
			@DecimalMin("4") @DecimalMax("72") Float size,
			@Pattern(regexp = "#[0-9A-Fa-f]{6}") String color) {

		public Span {
			text = text == null ? "" : text;
			uncertain = Boolean.TRUE.equals(uncertain);
			italic = Boolean.TRUE.equals(italic);
			strike = Boolean.TRUE.equals(strike);
		}

		public Span(String text, boolean underline, boolean bold, Boolean uncertain) {
			this(text, underline, bold, uncertain, false, false, null, null, null);
		}

		public Span(String text, boolean underline, boolean bold) {
			this(text, underline, bold, false);
		}

		public Span(String text, boolean underline) {
			this(text, underline, false, false);
		}

		/** 밑줄·굵게 말고 다른 글자 모양(기울임·취소선·첨자·크기·색)이 있는지. 있으면 템플릿 글자 서식을 복제해 쓴다 */
		public boolean hasExtraFormat() {
			return italic || strike || script != null || size != null || color != null;
		}
	}

	/**
	 * 정답·해설 1개. 템플릿에서는 "N 번 - 답   해설"
	 *
	 * @param answer      ②, (A) degraded ... 처럼 정답 부분
	 * @param explanation 해설. 없으면 빈 문자열
	 */
	public record Answer(int number, @Size(max = 2000) String answer, @Size(max = 20_000) String explanation) {

		public Answer {
			answer = answer == null ? "" : answer;
			explanation = explanation == null ? "" : explanation;
		}
	}
}
