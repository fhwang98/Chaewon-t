package cloud.fhwang.chaewon_t.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import cloud.fhwang.chaewon_t.extract.ExamDocument.Answer;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Question;
import cloud.fhwang.chaewon_t.template.HeaderTitleFit;

/**
 * 추출 결과에서 확인이 필요한 곳을 찾는다. 결과는 검토 화면에 경고로 보여 주고 생성은 막지 않는다
 * (PDF마다 예외가 있을 수 있어 사람이 판단한다).
 */
public final class ExamValidator {

	private static final int CHOICE_COUNT = 5;

	private ExamValidator() {
	}

	public static List<String> warnings(ExamDocument doc) {
		List<String> warnings = new ArrayList<>();
		// 길면 HWPX에서 글자를 줄여 한 줄에 넣는다. 가장 작게 줄여도 넘칠 때만 알린다
		if (HeaderTitleFit.overflows(doc.headerTitle(), HeaderTitleFit.TEMPLATE_WIDTH)) {
			warnings.add("머리말이 너무 길어 두 줄로 넘어갑니다. 줄여 주세요.");
		}
		if (doc.questions().isEmpty()) {
			warnings.add("문항을 찾지 못했습니다.");
			return warnings;
		}

		int expected = doc.questions().get(0).number();
		if (expected != 1) {
			warnings.add("문항 번호가 1번이 아니라 " + expected + "번부터 시작합니다.");
		}
		for (Question q : doc.questions()) {
			if (q.number() != expected) {
				warnings.add(expected + "번 다음이 " + q.number() + "번입니다.");
			}
			expected = q.number() + 1;
			if (q.prompt().plainText().isBlank()) {
				warnings.add(q.number() + "번: 질문이 비어 있습니다.");
			}
			if (q.body().isEmpty()) {
				warnings.add(q.number() + "번: 본문이 비어 있습니다.");
			}
			// 문장 삽입·어휘 문제처럼 ①~⑤가 본문 안에 있으면 선택지 문단이 없는 것이 정상이다
			boolean inlineChoices = q.choices().isEmpty() && hasCircledNumbers(q);
			if (!q.subjective() && !inlineChoices && q.choices().size() != CHOICE_COUNT) {
				warnings.add(q.number() + "번: 선택지가 " + q.choices().size() + "개입니다.");
			}
		}

		if (doc.answers().isEmpty()) {
			warnings.add("정답·해설을 찾지 못했습니다.");
			return warnings;
		}
		Set<Integer> questionNumbers = doc.questions().stream().map(Question::number).collect(Collectors.toCollection(TreeSet::new));
		Set<Integer> answerNumbers = doc.answers().stream().map(Answer::number).collect(Collectors.toCollection(TreeSet::new));
		Set<Integer> noAnswer = new TreeSet<>(questionNumbers);
		noAnswer.removeAll(answerNumbers);
		Set<Integer> noQuestion = new TreeSet<>(answerNumbers);
		noQuestion.removeAll(questionNumbers);
		if (!noAnswer.isEmpty()) {
			warnings.add("정답이 없는 문항: " + join(noAnswer));
		}
		if (!noQuestion.isEmpty()) {
			warnings.add("문항이 없는 정답: " + join(noQuestion));
		}
		if (answerNumbers.size() != doc.answers().size()) {
			warnings.add("같은 번호의 정답이 여러 개 있습니다.");
		}
		return warnings;
	}

	private static boolean hasCircledNumbers(Question q) {
		String body = q.body().stream()
				.flatMap(b -> b.paragraphs().stream())
				.map(ExamDocument.Paragraph::plainText)
				.collect(Collectors.joining());
		return "①②③④⑤".chars().allMatch(c -> body.indexOf(c) >= 0);
	}

	private static String join(Set<Integer> numbers) {
		return numbers.stream().map(String::valueOf).collect(Collectors.joining(", "));
	}
}
