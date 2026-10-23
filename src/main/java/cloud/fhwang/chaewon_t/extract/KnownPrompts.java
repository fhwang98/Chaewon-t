package cloud.fhwang.chaewon_t.extract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cloud.fhwang.chaewon_t.extract.ExamDocument.Paragraph;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Question;
import cloud.fhwang.chaewon_t.extract.ExamDocument.Span;

/**
 * OCR로 읽은 문항 머리를 자주 나오는 문장(resources/extract/known-prompts.txt)으로 고친다.
 * 시험지 문항 머리는 "다음 글의 주제로 가장 적절한 것은?"처럼 정해진 문장이 되풀이되므로,
 * "다음 22 제목으로 가장 적절한 것은?"이나 "영작하시2." 같은 OCR 오류를 목록과 견줘 바로잡을 수 있다.
 *
 * 띄어쓰기를 뺀 글자로 편집 거리(글자를 몇 개 바꾸면 같아지는지)를 잰다. 바꾸는 조건:
 *  - 가장 가까운 문장과의 거리가 그 문장 길이의 20% 이하이면서 4 이하 (그보다 다르면 다른 종류의 문항일 수 있다).
 *    sample2에서 OCR 오류는 모두 4 이하였고, "요약하고자 할 때 빈칸 (A) ~ (C)"처럼 원래 표현이 다른 문항은 5였다
 *  - 두 번째로 가까운 문장보다 2 이상 가깝다. "제목으로"/"주제로"처럼 비슷한 문장 사이에서 OCR이 뭉개진
 *    "다음 29 FH Z 가장 적절한 것은?"은 어느 쪽인지 모르므로 그대로 둔다
 *  - 거리가 0이면 바꾸지 않는다 (이미 맞다. 밑줄 위치가 다른 학교 시험지를 템플릿 밑줄로 덮지 않게)
 *  - 문장보다 3글자 이상 짧으면 바꾸지 않는다. 문항 머리가 두 줄이라 뒷부분("곳은? [23]")이 본문으로 갔을 수 있어서,
 *    채워 넣으면 같은 글자가 두 번 나온다
 */
final class KnownPrompts {

	private static final double MAX_DISTANCE_RATIO = 0.2;
	private static final int MAX_DISTANCE = 4;
	private static final int MIN_MARGIN = 2;
	private static final Pattern UNDERLINE = Pattern.compile("<u>(.*?)</u>");

	/** 목록의 한 문장: 띄어쓰기 뺀 글자(비교용)와 넣을 글자 조각 */
	private record Prompt(String key, List<Span> spans) {
	}

	private static final List<Prompt> PROMPTS = load();

	private KnownPrompts() {
	}

	static ExamDocument fix(ExamDocument doc) {
		List<Question> questions = doc.questions().stream().map(KnownPrompts::fix).toList();
		return new ExamDocument(doc.headerTitle(), questions, doc.answers());
	}

	/**
	 * 띄어쓰기만 목록 문장에 맞춘다 (텍스트 PDF에도 쓴다). 두 줄로 나뉜 문항 머리는 줄이 어절에서 바뀌었는지
	 * 글자 중간에서 바뀌었는지 알 수 없어 "적절한곳은?", "적절하지않은"처럼 붙는다 (sample5·6 100문항 중 30여 개).
	 * 띄어쓰기를 뺀 글자가 목록 문장과 똑같을 때만, 빠진 공백만 넣는다. 글자와 밑줄·굵게는 PDF 그대로 둔다
	 */
	static ExamDocument fixSpacing(ExamDocument doc) {
		List<Question> questions = doc.questions().stream().map(q -> {
			Paragraph spaced = spaced(q.prompt());
			return spaced == null ? q : new Question(q.number(), spaced, q.source(), q.body(), q.choices());
		}).toList();
		return new ExamDocument(doc.headerTitle(), questions, doc.answers());
	}

	/** 공백을 넣은 문항 머리. 목록에 똑같은 문장이 없거나 넣을 공백이 없으면 null */
	static Paragraph spaced(Paragraph prompt) {
		String plain = prompt.plainText();
		String key = key(plain);
		String known = PROMPTS.stream().filter(p -> p.key().equals(key)).findFirst()
				.map(p -> new Paragraph(p.spans()).plainText()).orElse(null);
		if (known == null || known.equals(plain)) {
			return null;
		}
		// 글자 하나씩 서식과 함께 늘어놓고, 목록 문장에서 공백인 자리에 원문 공백이 없으면 넣는다
		List<Span> chars = new ArrayList<>();
		prompt.spans().forEach(sp -> sp.text().codePoints()
				.forEach(cp -> chars.add(withText(sp, Character.toString(cp)))));
		List<Span> out = new ArrayList<>();
		int i = 0;
		boolean added = false;
		for (int k = 0; k < known.length(); ) {
			int cp = known.codePointAt(k);
			k += Character.charCount(cp);
			while (i < chars.size() && chars.get(i).text().isBlank() && !Character.isWhitespace(cp)) {
				out.add(chars.get(i++)); // 원문에만 있는 공백은 그대로 둔다
			}
			if (Character.isWhitespace(cp)) {
				if (i < chars.size() && chars.get(i).text().isBlank()) {
					out.add(chars.get(i++));
				} else if (!out.isEmpty()) {
					Span before = out.get(out.size() - 1);
					boolean underline = before.underline() && i < chars.size() && chars.get(i).underline();
					out.add(new Span(" ", underline, before.bold(), before.uncertain()));
					added = true;
				}
			} else if (i < chars.size()) {
				out.add(chars.get(i++));
			}
		}
		while (i < chars.size()) {
			out.add(chars.get(i++));
		}
		return added ? new Paragraph(merge(out), prompt.align(), prompt.indent(), prompt.firstLine()) : null;
	}

	private static Span withText(Span s, String text) {
		return new Span(text, s.underline(), s.bold(), s.uncertain(), s.italic(), s.strike(), s.script(), s.size(), s.color());
	}

	/** 서식이 같은 이웃 글자를 한 조각으로 */
	private static List<Span> merge(List<Span> chars) {
		List<Span> spans = new ArrayList<>();
		for (Span c : chars) {
			Span last = spans.isEmpty() ? null : spans.get(spans.size() - 1);
			if (last != null && withText(last, "").equals(withText(c, ""))) {
				spans.set(spans.size() - 1, withText(last, last.text() + c.text()));
			} else {
				spans.add(c);
			}
		}
		return spans;
	}

	private static Question fix(Question q) {
		Paragraph fixed = match(q.prompt().plainText());
		if (fixed == null) {
			return q;
		}
		return new Question(q.number(), fixed, q.source(), q.body(), q.choices());
	}

	/** 바꿀 문장. 조건에 맞지 않으면 null */
	static Paragraph match(String prompt) {
		String key = key(prompt);
		if (key.isEmpty()) {
			return null;
		}
		Prompt best = null;
		int bestDistance = Integer.MAX_VALUE;
		int second = Integer.MAX_VALUE;
		for (Prompt p : PROMPTS) {
			int d = distance(key, p.key());
			if (d < bestDistance) {
				second = bestDistance;
				bestDistance = d;
				best = p;
			} else if (d < second) {
				second = d;
			}
		}
		if (best == null || bestDistance == 0 || bestDistance > MAX_DISTANCE
				|| bestDistance > best.key().length() * MAX_DISTANCE_RATIO
				|| second - bestDistance < MIN_MARGIN || key.length() < best.key().length() - 2) {
			return null;
		}
		return new Paragraph(best.spans());
	}

	private static String key(String text) {
		return text.replaceAll("\\s", "");
	}

	/** 레벤슈타인 거리 (글자 단위) */
	static int distance(String a, String b) {
		int[] x = a.codePoints().toArray();
		int[] y = b.codePoints().toArray();
		int[] prev = new int[y.length + 1];
		int[] cur = new int[y.length + 1];
		for (int j = 0; j <= y.length; j++) {
			prev[j] = j;
		}
		for (int i = 1; i <= x.length; i++) {
			cur[0] = i;
			for (int j = 1; j <= y.length; j++) {
				int cost = x[i - 1] == y[j - 1] ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return prev[y.length];
	}

	private static List<Prompt> load() {
		try (InputStream in = KnownPrompts.class.getResourceAsStream("/extract/known-prompts.txt")) {
			if (in == null) {
				return List.of();
			}
			List<Prompt> prompts = new ArrayList<>();
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
				String text = line.strip();
				if (text.isEmpty() || text.startsWith("#")) {
					continue;
				}
				List<Span> spans = spans(text);
				prompts.add(new Prompt(key(new Paragraph(spans).plainText()), spans));
			}
			return List.copyOf(prompts);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** "일치하지 <u>않는</u> 것은?" → 글자 조각 */
	private static List<Span> spans(String marked) {
		List<Span> spans = new ArrayList<>();
		Matcher m = UNDERLINE.matcher(marked);
		int pos = 0;
		while (m.find()) {
			if (m.start() > pos) {
				spans.add(new Span(marked.substring(pos, m.start()), false));
			}
			spans.add(new Span(m.group(1), true));
			pos = m.end();
		}
		if (pos < marked.length()) {
			spans.add(new Span(marked.substring(pos), false));
		}
		return spans;
	}
}
