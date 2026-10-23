package cloud.fhwang.chaewon_t.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cloud.fhwang.chaewon_t.extract.PdfLine.Glyph;

/**
 * OCR이 잘못 읽은 선택지 번호 ①~⑤를 되돌린다.
 *
 * Tesseract는 동그라미 숫자를 거의 못 읽는다. sample2·3에서 본 모양:
 *   숫자가 보이는 것  (2) (3) (5) 3) 2)       → 그 숫자
 *   숫자가 안 보이는 것 @ ® © (® @) @® (D (9)  → 앞 선택지 번호 + 1 (문항이 바뀌면 ①부터)
 *
 * 순서로 짐작한 번호는 "확인 필요"로 표시한다 (숫자가 보여 고친 것은 표시하지 않는다).
 *
 * 고치는 자리는 번호가 올 수 있는 곳만: 줄 첫머리, 넓은 공백(3칸) 뒤(한 줄에 선택지 둘), 정답 줄의 "N 번 - " 뒤.
 * 지문 속 "(A) - (C)" 같은 글자는 건드리지 않는다.
 * 예외: 지문 속(밑줄 문제·문장 삽입 문제의 ①~⑤)에서도 고치는 것
 *   괄호 없는 @ © ® (시험지에 원래 쓰일 일이 없다), 뒤 단어에 붙은 것("®reaffirming" → "③ reaffirming"), "3)" 꼴
 */
final class OcrMarks {

	private static final String CIRCLED = "①②③④⑤";
	private static final Pattern KNOWN = Pattern.compile("^(?:\\(([1-5])\\)?|([1-5])\\))$");
	private static final Pattern INLINE = Pattern.compile("^[@©®]{1,2}$");
	private static final Pattern INLINE_KNOWN = Pattern.compile("^([1-5])\\)$");
	/** 번호가 뒤 단어에 붙은 것: 첫 글자만 번호 */
	private static final Pattern GLUED = Pattern.compile("^[@©®]\\p{L}");
	private static final Pattern UNKNOWN = Pattern.compile("^(?:\\(?[@©®]{1,2}\\)?|\\([6-9]\\)|\\(D)$");
	/** 줄 끝 출처의 닫는 괄호를 1로 읽은 것: "[241" → "[24]", "[41~421" → "[41~42]" */
	private static final Pattern UNCLOSED_SOURCE = Pattern.compile("\\[[0-9][0-9~\\-]*1$");
	private static final Pattern QUESTION = Pattern.compile("^\\d+\\.\\s");
	/** 정답 줄 "N 번 - ". OCR이 "번"을 "H"처럼 읽기도 해 아무 1~2글자를 받는다 */
	private static final Pattern ANSWER = Pattern.compile("^\\d+\\s*(?:번|\\S{1,2})\\s*-\\s+");

	private OcrMarks() {
	}

	/**
	 * @param ocrPages OCR로 읽은 페이지 번호. 다른 페이지 줄은 고치지 않고 번호 순서만 따라간다
	 */
	static List<PdfLine> normalize(List<PdfLine> lines, Set<Integer> ocrPages) {
		List<PdfLine> result = new ArrayList<>(lines.size());
		int last = 0; // 마지막 선택지 번호. 0 = 아직 없음
		for (PdfLine line : lines) {
			String text = line.text().strip();
			if (QUESTION.matcher(text).find()) {
				last = 0;
			}
			boolean fix = ocrPages.contains(line.page());
			Matcher answer = ANSWER.matcher(text);
			List<Glyph> glyphs = new ArrayList<>(line.glyphs());
			if (answer.find()) {
				// 정답은 순서가 없어 숫자가 보이는 것만 고친다
				int start = firstNonBlank(glyphs, answer.end() + leading(line));
				if (fix && start >= 0) {
					replace(glyphs, start, known(token(glyphs, start)), false);
				}
				result.add(fix ? copy(line, glyphs) : line);
				continue;
			}
			// 번호는 왼쪽부터 정하고(앞 번호 + 1), 바꾸기는 오른쪽부터 한다 (앞을 바꾸면 뒤 글자 위치가 밀린다)
			List<Integer> positions = tokenStarts(glyphs);
			int[] numbers = new int[positions.size()];
			boolean[] glued = new boolean[positions.size()];
			boolean[] guessed = new boolean[positions.size()];
			for (int i = 0; i < positions.size(); i++) {
				String token = token(glyphs, positions.get(i));
				int n = CIRCLED.indexOf(token) + 1;
				if (n == 0 && fix) {
					if (isMarkPlace(glyphs, positions.get(i))) {
						n = known(token);
						if (n == 0 && UNKNOWN.matcher(token).matches()) {
							n = next(last);
							guessed[i] = true;
						}
					} else {
						Matcher inline = INLINE_KNOWN.matcher(token);
						if (inline.matches()) {
							n = Integer.parseInt(inline.group(1));
						} else if (INLINE.matcher(token).matches()) {
							n = next(last);
							guessed[i] = true;
						} else if (GLUED.matcher(token).find()) {
							n = next(last);
							glued[i] = true;
							guessed[i] = true;
						}
					}
					numbers[i] = n;
				}
				if (n > 0) {
					last = n;
				}
			}
			for (int i = positions.size() - 1; i >= 0; i--) {
				if (glued[i]) {
					splitGlued(glyphs, positions.get(i), numbers[i]);
				} else {
					replace(glyphs, positions.get(i), numbers[i], guessed[i]);
				}
			}
			if (fix) {
				closeSource(glyphs);
			}
			result.add(fix ? copy(line, glyphs) : line);
		}
		return result;
	}

	private static void closeSource(List<Glyph> glyphs) {
		StringBuilder sb = new StringBuilder();
		glyphs.forEach(g -> sb.append(g.text()));
		String text = sb.toString().stripTrailing();
		if (UNCLOSED_SOURCE.matcher(text).find()) {
			int i = text.length() - 1; // 한 글자 = 한 Glyph
			Glyph g = glyphs.get(i);
			glyphs.set(i, new Glyph("]", g.x(), g.width(), g.underline(), g.bold()));
		}
	}

	private static int next(int last) {
		return last >= 5 ? 1 : last + 1;
	}

	/** "®reaffirming" → "③ reaffirming": 첫 글자만 번호로 바꾸고 공백을 끼운다 (뒤 단어의 밑줄은 그대로) */
	private static void splitGlued(List<Glyph> glyphs, int start, int n) {
		Glyph first = glyphs.get(start);
		glyphs.set(start, new Glyph(String.valueOf(CIRCLED.charAt(n - 1)), first.x(), first.width(), false, false, true));
		glyphs.add(start + 1, new Glyph(" ", first.x() + first.width(), 0, false, false));
	}

	/** 단어가 시작하는 글자 위치 */
	private static List<Integer> tokenStarts(List<Glyph> glyphs) {
		List<Integer> starts = new ArrayList<>();
		for (int i = 0; i < glyphs.size(); i++) {
			if (!glyphs.get(i).text().isBlank() && (i == 0 || glyphs.get(i - 1).text().isBlank())) {
				starts.add(i);
			}
		}
		return starts;
	}

	/** 선택지 번호가 올 자리: 줄 첫 단어, 공백 3칸 이상 뒤 */
	private static boolean isMarkPlace(List<Glyph> glyphs, int start) {
		int blanks = 0;
		for (int i = start - 1; i >= 0 && glyphs.get(i).text().isBlank(); i--) {
			blanks++;
		}
		return blanks == start || blanks >= 3;
	}

	/** start부터 공백 전까지의 글자 */
	private static String token(List<Glyph> glyphs, int start) {
		StringBuilder sb = new StringBuilder();
		for (int i = start; i < glyphs.size() && !glyphs.get(i).text().isBlank(); i++) {
			sb.append(glyphs.get(i).text());
		}
		return sb.toString();
	}

	private static int known(String token) {
		Matcher m = KNOWN.matcher(token);
		if (!m.matches()) {
			return 0;
		}
		return Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
	}

	/** start의 단어를 동그라미 숫자 한 글자로 바꾼다. n이 0이면 그대로. guessed면 "확인 필요" 표시 */
	private static void replace(List<Glyph> glyphs, int start, int n, boolean guessed) {
		if (n <= 0) {
			return;
		}
		int end = start;
		while (end < glyphs.size() && !glyphs.get(end).text().isBlank()) {
			end++;
		}
		Glyph first = glyphs.get(start);
		Glyph lastGlyph = glyphs.get(end - 1);
		Glyph mark = new Glyph(String.valueOf(CIRCLED.charAt(n - 1)), first.x(),
				lastGlyph.x() + lastGlyph.width() - first.x(), false, false, guessed);
		glyphs.subList(start, end).clear();
		glyphs.add(start, mark);
	}

	/** text().strip() 기준 위치를 글자 목록 위치로 (OCR 줄 글자는 한 글자 = 한 Glyph) */
	private static int leading(PdfLine line) {
		String text = line.text();
		return text.length() - text.stripLeading().length();
	}

	private static int firstNonBlank(List<Glyph> glyphs, int from) {
		for (int i = from; i < glyphs.size(); i++) {
			if (!glyphs.get(i).text().isBlank()) {
				return i;
			}
		}
		return -1;
	}

	private static PdfLine copy(PdfLine line, List<Glyph> glyphs) {
		return new PdfLine(line.page(), line.column(), line.x(), line.y(), line.height(), glyphs);
	}
}
