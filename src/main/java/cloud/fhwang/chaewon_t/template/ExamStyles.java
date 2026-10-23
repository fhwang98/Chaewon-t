package cloud.fhwang.chaewon_t.template;

/**
 * 시험지 템플릿의 역할별 문단 스타일과 글자 서식 id (mapping.json의 styles).
 * id 값은 템플릿 Contents/header.xml에 정의된 것이어야 한다.
 *
 * @param question      문항 머리 (번호 자동)
 * @param body          본문 문단
 * @param bodyLast      선택지가 없는 문항의 마지막 본문 문단
 * @param gap           덩어리 사이 빈 줄 (지문↔선택지 등)
 * @param choice        선택지 ①~④
 * @param choiceLast    마지막 선택지
 * @param spacer        문항 사이 빈 줄
 * @param answer        정답·해설 문단
 * @param answerHeading 정답 구역 제목 글자 (예: "< 정 답 >")
 */
public record ExamStyles(
		ParagraphStyle question,
		ParagraphStyle body,
		ParagraphStyle bodyLast,
		ParagraphStyle gap,
		ParagraphStyle choice,
		ParagraphStyle choiceLast,
		ParagraphStyle spacer,
		ParagraphStyle answer,
		CharStyles chars,
		String answerHeading) {

	/** 문단 = paraPrIDRef + styleIDRef 조합 */
	public record ParagraphStyle(String paraPr, String style) {
	}

	/** 글자 서식 charPrIDRef */
	public record CharStyles(String normal, String underline, String bold, String boldUnderline, String heading) {
	}
}
