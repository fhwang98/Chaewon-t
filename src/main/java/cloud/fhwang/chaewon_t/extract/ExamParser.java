package cloud.fhwang.chaewon_t.extract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cloud.fhwang.chaewon_t.extract.ExamDocument.Answer;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Block;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Paragraph;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Question;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Span;
import cloud.fhwang.chaewon_t.extract.PdfLine.Glyph;

/**
 * 줄 목록(읽는 순서) → 시험지 구조.
 *
 * 규칙 (samples/sample.pdf 좌표로 정했다. 다른 PDF에서는 틀릴 수 있어 검토 화면에서 고친다)
 *  - 문항 시작: 단 왼쪽 끝(4pt 안)에서 "N. "으로 시작하고 N이 직전 문항 + 1.
 *    (sample6 58·86번은 번호가 다른 문항보다 2pt 오른쪽에서 시작해 앞 문항에 붙었다)
 *    OCR이 번호 하나를 못 읽으면 뒤 문항이 전부 앞 문항에 붙으므로, 질문처럼 보이는 줄(? 또는 "시오"로 끝나거나 [출처])이면
 *    + 2·+ 3도 받는다 (빠진 번호는 ExamValidator가 경고한다)
 *  - 질문 끝의 [출처]가 다음 줄로 넘어가면 그 줄까지 질문
 *  - 덩어리(Block) 경계: 같은 단 안에서 줄 간격이 평소의 1.5배보다 클 때
 *  - 문단 경계: 앞 줄이 단 오른쪽 끝까지 차지 않았을 때, 또는 첫 줄 들여쓰기(앞 줄보다 3pt 이상 오른쪽)일 때.
 *    단 ①, (A), [보기]처럼 표시로 시작한 문단은 둘째 줄부터 내어쓰기라 들여쓴 줄을 이어지는 줄로 본다
 *  - 선택지: 덩어리 첫 줄이 ①로 시작하면 선택지 시작. 이후 같은 x에서 ②~⑤로 시작하는 줄이 새 선택지.
 *    덩어리 경계가 아니어도 ①로 시작한 줄이 앞 줄(지문)보다 왼쪽에서 시작하고, 뒤로 같은 x에서 ②~⑤가 차례로
 *    줄을 시작하면 선택지다 (sample5: 지문 상자 바로 아래 선택지가 줄 간격 1.4배라 덩어리 경계로 안 잡혀 지문에 붙었다.
 *    지문은 상자 안이라 선택지보다 오른쪽에서 시작한다. 지문 속 "① …"으로 시작하는 줄은 앞 줄과 x가 같다)
 *    질문에 (주관식)/(서술형)이 있으면 ①②는 [조건] 같은 본문으로 본다
 *  - 정답: "< 정 답 >" 또는 "1 번 - " 줄부터. "N 번 - 답   해설" (답과 해설은 공백 2칸 이상으로 나뉨)
 */
final class ExamParser {

	private static final Pattern QUESTION = Pattern.compile("^(\\d+)\\.\\s");
	/** 번호를 건너뛴 문항을 받을 때 질문 줄인지 보는 표시 */
	private static final Pattern LOOKS_LIKE_PROMPT = Pattern.compile("\\?|시오|\\(주관식\\)|\\(서술형\\)|\\[[^\\[\\]]*]\\s*$");
	private static final int MAX_SKIP = 3;
	private static final Pattern SOURCE = Pattern.compile("\\s*\\[([^\\[\\]]*)]\\s*$");
	private static final Pattern ANSWER = Pattern.compile("^(\\d+)\\s*번\\s*-\\s*");
	/** 정답 구역 안에서만: OCR이 "번"을 "H", "버" 등으로 읽은 것. 직전 정답 번호 + 1일 때만 받는다 */
	private static final Pattern ANSWER_LOOSE = Pattern.compile("^(\\d+)\\s*\\S{1,2}\\s*-\\s+");
	private static final Pattern ANSWER_HEADING = Pattern.compile("^<\\s*정\\s*답\\s*>$");
	private static final Pattern ANSWER_SPLIT = Pattern.compile("\\s{2,}");
	/**
	 * 공백 2칸이 없을 때(OCR은 공백 폭을 잘 못 맞춘다): 동그라미 번호로 된 답 "②" 또는 "②, ④" 뒤에서 나눈다.
	 * OCR이 번호를 못 읽은 "(9)", "@)" 같은 것도 답 자리로 보고 나눈다 (검토 화면에서 번호만 고치면 되게)
	 */
	private static final Pattern CIRCLED_ANSWER = Pattern.compile(
			"^([①-⑤](?:\\s*,\\s*[①-⑤])*|\\(\\S{1,2}\\)?|[0-9]\\)|[@©®]{1,2}\\)?)\\s+(.+)$");
	private static final String CHOICE_MARKS = "①②③④⑤";
	/** 내어쓰기 문단의 첫머리 표시: ① (A) A. [보기] - • ※ */
	private static final Pattern HANGING_MARK = Pattern.compile("^([①-⑳]|\\([A-Za-z]\\)|[A-Z]\\.\\s|\\[[^\\]]+]|[-•※])");
	/**
	 * 한 줄에 선택지가 여럿일 때 나누는 자리: "① …   ② …". 공백 한 칸이어도 나눈다
	 * (sample5 순서 문제 "①(A) - (C) - (B) ②(B) - (A) - (C)"는 넓은 틈이 공백 한 칸으로 읽혔다)
	 */
	private static final Pattern CHOICE_SPLIT = Pattern.compile("\\s+(?=[②③④⑤])");

	private static final float INDENT = 3f;        // 이만큼 오른쪽에서 시작하면 들여쓴 새 문단
	private static final float SHORT_LINE = 25f;   // 단 오른쪽 끝에서 이만큼 모자라면 문단이 끝난 줄
	private static final float BLOCK_GAP = 1.5f;   // 평소 줄 간격의 이 배수보다 벌어지면 새 덩어리
	private static final float ALIGNED = 2f;       // x가 이만큼 안이면 같은 위치
	private static final float QUESTION_LEFT = 4f; // 문항 번호가 단 왼쪽 끝에서 이만큼 안이면 문항 시작
	private static final int CHOICE_LOOKAHEAD = 20; // ① 줄 뒤로 ②~⑤를 찾아보는 줄 수

	private ExamParser() {
	}

	static ExamDocument parse(List<PdfLine> lines, String headerTitle) {
		Geometry geo = Geometry.of(lines);
		List<QuestionDraft> questions = new ArrayList<>();
		List<Rich> answers = new ArrayList<>();
		List<Integer> answerNumbers = new ArrayList<>();
		boolean inAnswers = false;
		PdfLine prev = null;

		for (int i = 0; i < lines.size(); i++) {
			PdfLine line = lines.get(i);
			String text = line.text().strip();
			if (text.isEmpty()) {
				continue;
			}
			Matcher answer = ANSWER.matcher(text);
			if (!inAnswers && !questions.isEmpty()
					&& (ANSWER_HEADING.matcher(text).matches() || (answer.find() && answer.group(1).equals("1")))) {
				inAnswers = true;
			}

			if (inAnswers) {
				answer.reset();
				Matcher loose = ANSWER_LOOSE.matcher(text);
				boolean strict = answer.find();
				if (strict || (loose.find() && !answerNumbers.isEmpty()
						&& Integer.parseInt(loose.group(1)) == answerNumbers.get(answerNumbers.size() - 1) + 1)) {
					Matcher m = strict ? answer : loose;
					answerNumbers.add(Integer.parseInt(m.group(1)));
					answers.add(Rich.of(line).dropPrefix(m.end() + leadingSpaces(line)));
				} else if (!answers.isEmpty()) {
					answers.get(answers.size() - 1).join(line);
				}
			} else {
				Matcher q = QUESTION.matcher(text);
				if (q.find() && geo.relX(line) < QUESTION_LEFT && isNext(questions, Integer.parseInt(q.group(1)), text)) {
					questions.add(new QuestionDraft(Integer.parseInt(q.group(1)), line, q.end() + leadingSpaces(line)));
				} else if (!questions.isEmpty()) {
					questions.get(questions.size() - 1).add(line, prev, geo, choiceRun(lines, i));
				}
				// 첫 문항 앞의 글자는 버린다
			}
			prev = line;
		}

		List<Question> parsed = questions.stream().map(QuestionDraft::build).toList();
		List<Answer> parsedAnswers = new ArrayList<>();
		for (int i = 0; i < answers.size(); i++) {
			parsedAnswers.add(toAnswer(answerNumbers.get(i), answers.get(i).plainText().strip()));
		}
		return new ExamDocument(headerTitle, parsed, parsedAnswers);
	}

	/**
	 * i번 줄이 앞 줄보다 왼쪽에서 ①로 시작하고, 뒤따르는 같은 x의 줄들이 ②, ③, ④, ⑤ 차례로 시작하는지
	 * (한 줄에 여럿이어도 된다)
	 */
	static boolean choiceRun(List<PdfLine> lines, int i) {
		PdfLine first = lines.get(i);
		if (!first.text().strip().startsWith("①") || i == 0) {
			return false;
		}
		PdfLine before = lines.get(i - 1);
		if (before.page() == first.page() && before.column() == first.column() && first.x() > before.x() - ALIGNED) {
			return false;
		}
		int expected = 0;
		for (int j = i; j < Math.min(lines.size(), i + CHOICE_LOOKAHEAD); j++) {
			PdfLine line = lines.get(j);
			String text = line.text().strip();
			if (line.page() != first.page() || line.column() != first.column()
					|| (j > i && QUESTION.matcher(text).find())) {
				return false;
			}
			if (Math.abs(line.x() - first.x()) >= ALIGNED || text.isEmpty() || CHOICE_MARKS.indexOf(text.charAt(0)) < 0) {
				continue;
			}
			for (String part : CHOICE_SPLIT.split(text)) {
				if (CHOICE_MARKS.indexOf(part.charAt(0)) != expected) {
					return false;
				}
				expected++;
			}
			if (expected == CHOICE_MARKS.length()) {
				return true;
			}
		}
		return false;
	}

	private static boolean isNext(List<QuestionDraft> questions, int number, String text) {
		if (questions.isEmpty()) {
			return true;
		}
		int skip = number - questions.get(questions.size() - 1).number;
		return skip == 1 || (skip <= MAX_SKIP && skip > 1 && LOOKS_LIKE_PROMPT.matcher(text).find());
	}

	/** "③   해설" → 답 "③", 해설. 공백 2칸 이상이 없으면 동그라미 번호 뒤에서, 그것도 아니면 전부 답 */
	static Answer toAnswer(int number, String text) {
		Matcher split = ANSWER_SPLIT.matcher(text);
		if (split.find()) {
			return new Answer(number, text.substring(0, split.start()), text.substring(split.end()));
		}
		Matcher circled = CIRCLED_ANSWER.matcher(text);
		if (circled.matches()) {
			return new Answer(number, circled.group(1), circled.group(2));
		}
		return new Answer(number, text, "");
	}

	private static int leadingSpaces(PdfLine line) {
		String text = line.text();
		return text.length() - text.stripLeading().length();
	}

	/** 한 문항을 모으는 중간 상태 */
	private static final class QuestionDraft {

		final int number;
		final Rich prompt;
		boolean promptDone;
		final List<List<Rich>> blocks = new ArrayList<>();
		final List<Rich> choices = new ArrayList<>();
		float choiceX = Float.NaN;

		QuestionDraft(int number, PdfLine first, int prefixLength) {
			this.number = number;
			this.prompt = Rich.of(first).dropPrefix(prefixLength);
			this.promptDone = SOURCE.matcher(prompt.plainText()).find();
		}

		void add(PdfLine line, PdfLine prev, Geometry geo, boolean choiceRun) {
			String text = line.text().strip();
			boolean gap = geo.gapBefore(line, prev);

			if (!promptDone) {
				promptDone = true;
				// [출처]가 다음 줄로 넘어간 경우만 질문에 붙인다 (출처가 없는 질문이 본문을 삼키지 않게)
				Rich joined = prompt.copy().join(line);
				if (!gap && SOURCE.matcher(joined.plainText()).find()) {
					prompt.join(line);
					return;
				}
			}

			if (!choices.isEmpty()) {
				if (startsWithChoice(text) && Math.abs(line.x() - choiceX) < ALIGNED) {
					choices.add(Rich.of(line));
				} else {
					choices.get(choices.size() - 1).join(line);
				}
				return;
			}
			if (!subjective() && text.startsWith("①") && (gap || geo.newColumn(line, prev) || blocks.isEmpty() || choiceRun)) {
				choiceX = line.x();
				choices.add(Rich.of(line));
				return;
			}
			if (blocks.isEmpty() || gap) {
				blocks.add(new ArrayList<>(List.of(Rich.of(line))));
				return;
			}
			List<Rich> block = blocks.get(blocks.size() - 1);
			Rich current = block.get(block.size() - 1);
			boolean indented = geo.relX(line) > geo.relX(prev) + INDENT
					&& !HANGING_MARK.matcher(current.plainText().stripLeading()).find();
			if (indented || geo.isShort(prev)) {
				block.add(Rich.of(line));
			} else {
				block.get(block.size() - 1).join(line);
			}
		}

		boolean subjective() {
			return new Question(number, prompt.toParagraph(), "", List.of(), List.of()).subjective();
		}

		Question build() {
			String source = "";
			Rich p = prompt.copy();
			Matcher m = SOURCE.matcher(p.plainText());
			if (m.find()) {
				source = m.group(1).strip();
				p = p.cut(m.start());
			}
			List<Block> body = blocks.stream()
					.map(b -> new Block(b.stream().map(Rich::toParagraph).toList()))
					.toList();
			List<Paragraph> splitChoices = choices.stream()
					.flatMap(c -> c.split(CHOICE_SPLIT).stream())
					.map(Rich::toParagraph)
					.toList();
			return new Question(number, p.toParagraph(), source, body, splitChoices);
		}

		private static boolean startsWithChoice(String text) {
			return !text.isEmpty() && CHOICE_MARKS.indexOf(text.charAt(0)) >= 0;
		}
	}

	/** 밑줄·굵게·확인 필요 표시가 붙은 글자열 */
	private static final class Rich {

		final StringBuilder text = new StringBuilder();
		final List<Boolean> underline = new ArrayList<>();
		final List<Boolean> bold = new ArrayList<>();
		final List<Boolean> uncertain = new ArrayList<>();

		static Rich of(PdfLine line) {
			Rich r = new Rich();
			r.append(line.glyphs());
			return r;
		}

		Rich copy() {
			Rich r = new Rich();
			r.text.append(text);
			r.underline.addAll(underline);
			r.bold.addAll(bold);
			r.uncertain.addAll(uncertain);
			return r;
		}

		String plainText() {
			return text.toString();
		}

		/** 앞에서 n글자를 버린다 ("12. " 같은 번호) */
		Rich dropPrefix(int n) {
			return slice(Math.min(n, text.length()), text.length());
		}

		/** end 이후를 버린다 */
		Rich cut(int end) {
			return slice(0, end);
		}

		Rich slice(int start, int end) {
			Rich r = new Rich();
			r.text.append(text, start, end);
			r.underline.addAll(underline.subList(start, end));
			r.bold.addAll(bold.subList(start, end));
			r.uncertain.addAll(uncertain.subList(start, end));
			return r;
		}

		/** 구분 패턴 자리에서 나눈다 (구분 글자는 버림) */
		List<Rich> split(Pattern separator) {
			List<Rich> parts = new ArrayList<>();
			Matcher m = separator.matcher(text);
			int start = 0;
			while (m.find()) {
				parts.add(slice(start, m.start()));
				start = m.end();
			}
			parts.add(slice(start, text.length()));
			return parts;
		}

		/** 다음 줄을 잇는다. 줄 끝에 공백이 없으면 영어 단어 사이에만 공백을 넣는다 (한글은 단어 중간에서 줄이 바뀐다) */
		Rich join(PdfLine line) {
			String next = line.text().stripLeading();
			if (next.isEmpty()) {
				return this;
			}
			if (text.length() > 0 && !Character.isWhitespace(text.charAt(text.length() - 1))
					&& needsSpace(text.charAt(text.length() - 1), next.charAt(0))) {
				text.append(' ');
				underline.add(false);
				bold.add(false);
				uncertain.add(false);
			}
			int skip = line.text().length() - next.length();
			append(line.glyphs().subList(Math.min(skip, line.glyphs().size()), line.glyphs().size()));
			return this;
		}

		private void append(List<Glyph> glyphs) {
			for (Glyph g : glyphs) {
				text.append(g.text());
				for (int i = 0; i < g.text().length(); i++) {
					underline.add(g.underline());
					bold.add(g.bold());
					uncertain.add(g.uncertain());
				}
			}
		}

		private static boolean needsSpace(char last, char first) {
			boolean lastLatin = isAsciiWord(last) || ".,;:!?)\"'”’".indexOf(last) >= 0;
			boolean firstLatin = isAsciiWord(first) || "(\"'“‘".indexOf(first) >= 0;
			return lastLatin && firstLatin && last != '-';
		}

		private static boolean isAsciiWord(char c) {
			return c < 128 && Character.isLetterOrDigit(c);
		}

		/** 앞뒤 공백을 자르고 같은 서식끼리 묶어 문단으로 */
		Paragraph toParagraph() {
			int start = 0;
			int end = text.length();
			while (start < end && Character.isWhitespace(text.charAt(start))) {
				start++;
			}
			while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
				end--;
			}
			List<Span> spans = new ArrayList<>();
			int i = start;
			while (i < end) {
				boolean u = underline.get(i);
				boolean b = bold.get(i);
				boolean q = uncertain.get(i);
				int j = i;
				while (j < end && underline.get(j) == u && bold.get(j) == b && uncertain.get(j) == q) {
					j++;
				}
				spans.add(new Span(text.substring(i, j), u, b, q));
				i = j;
			}
			return new Paragraph(spans);
		}
	}

	/** 단마다의 왼쪽·오른쪽 끝과 평소 줄 간격 */
	private record Geometry(float spacing, Map<String, Float> left, Map<String, Float> right) {

		static Geometry of(List<PdfLine> lines) {
			Map<String, Float> left = new HashMap<>();
			Map<String, Float> right = new HashMap<>();
			List<Float> gaps = new ArrayList<>();
			PdfLine prev = null;
			for (PdfLine l : lines) {
				left.merge(key(l), l.x(), Math::min);
				right.merge(key(l), l.endX(), Math::max);
				if (prev != null && key(prev).equals(key(l))) {
					float d = l.y() - prev.y();
					if (d > 0 && d < 30) {
						gaps.add(d);
					}
				}
				prev = l;
			}
			gaps.sort(Float::compare);
			// 작은 쪽 중앙값: 줄이 적을 때 덩어리 사이 간격이 평소 줄 간격으로 잡히지 않게
			float spacing = gaps.isEmpty() ? 12f : gaps.get((gaps.size() - 1) / 2);
			return new Geometry(spacing, left, right);
		}

		boolean gapBefore(PdfLine line, PdfLine prev) {
			return prev != null && key(prev).equals(key(line)) && line.y() - prev.y() > spacing * BLOCK_GAP;
		}

		boolean newColumn(PdfLine line, PdfLine prev) {
			return prev == null || !key(prev).equals(key(line));
		}

		float relX(PdfLine line) {
			return line.x() - left.get(key(line));
		}

		boolean isShort(PdfLine line) {
			return line.endX() < right.get(key(line)) - SHORT_LINE;
		}

		private static String key(PdfLine l) {
			return l.page() + ":" + l.column();
		}
	}
}
