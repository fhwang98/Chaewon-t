package cloud.fhwang.chaewon_t.probe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 1단계 확인용: HWPX 템플릿 내부 구조를 출력한다.
 *
 * 실행: HwpxProbe <template.hwpx> [출력폴더]
 *
 * 확인하는 것
 * 1. ZIP 항목 순서와 압축 방식 (mimetype 위치·STORED 여부)
 * 2. 본문 XML(Contents/section*.xml)을 출력 폴더에 저장
 * 3. {{키}} 자리표시자가 한 덩어리로 들어 있는지, 쪼개진 것이 있는지
 * 4. 표·행·셀 태그 개수 (반복 행 처리 설계용)
 *
 * JDK만 사용한다. 외부 라이브러리 없음.
 */
public class HwpxProbe {

	// 정상 자리표시자: {{name}}, {{items.qty}}
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[A-Za-z0-9_.]+}}");

	// 텍스트 요소: <hp:t>...</hp:t> 처럼 접두어가 붙은 t 태그 (접두어는 파일마다 다를 수 있어 \w+로 둠)
	private static final Pattern TEXT_RUN = Pattern.compile("<(\\w+):t(?:\\s[^>]*)?>(.*?)</\\1:t>", Pattern.DOTALL);

	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			System.out.println("사용법: HwpxProbe <template.hwpx> [출력폴더]");
			return;
		}
		Path hwpx = Path.of(args[0]);
		Path outDir = Path.of(args.length > 1 ? args[1] : "probe-out/hwpx");
		Files.createDirectories(outDir);

		try (ZipFile zip = new ZipFile(hwpx.toFile(), StandardCharsets.UTF_8)) {
			printEntries(zip);
			printMimetype(zip);

			List<? extends ZipEntry> sections = Collections.list(zip.entries()).stream()
					.filter(e -> e.getName().matches("Contents/section\\d+\\.xml"))
					.toList();

			if (sections.isEmpty()) {
				System.out.println("\n[경고] Contents/section*.xml 이 없습니다. 위 항목 목록에서 본문 XML 이름을 확인하세요.");
			}

			for (ZipEntry section : sections) {
				String xml = read(zip, section);
				Path saved = outDir.resolve(Path.of(section.getName()).getFileName());
				Files.writeString(saved, xml, StandardCharsets.UTF_8);

				System.out.println("\n=== " + section.getName() + " (" + xml.length() + "자) → " + saved);
				checkPlaceholders(xml);
				countTableTags(xml);
			}
		}
	}

	private static void printEntries(ZipFile zip) {
		System.out.println("=== ZIP 항목 (저장 순서)");
		int i = 0;
		for (ZipEntry e : Collections.list(zip.entries())) {
			String method = e.getMethod() == ZipEntry.STORED ? "STORED  " : "DEFLATED";
			System.out.printf("%3d  %s  %8d bytes  %s%n", i++, method, e.getSize(), e.getName());
		}
	}

	private static void printMimetype(ZipFile zip) throws IOException {
		ZipEntry mime = zip.getEntry("mimetype");
		if (mime == null) {
			System.out.println("\n[참고] mimetype 항목 없음");
			return;
		}
		System.out.println("\n=== mimetype 내용: " + read(zip, mime).trim());
	}

	private static void checkPlaceholders(String xml) {
		// 1) XML 원문에서 바로 찾은 정상 자리표시자
		Matcher m = PLACEHOLDER.matcher(xml);
		int ok = 0;
		System.out.println("-- 정상 자리표시자");
		while (m.find()) {
			ok++;
			System.out.println("   " + m.group());
		}
		if (ok == 0)
			System.out.println("   (없음)");

		// 2) 쪼개진 자리표시자 의심: 여는 {{ 개수와 정상 개수가 다르면 어딘가 나뉘어 있음
		int opens = count(xml, "{{");
		if (opens != ok) {
			System.out.printf("-- [경고] '{{' %d개 중 %d개만 온전함. 쪼개진 자리표시자가 있습니다.%n", opens, ok);
			printSplitCandidates(xml);
		}
	}

	// 텍스트 요소를 순서대로 이어 붙였을 때만 보이는 자리표시자 = 여러 텍스트 요소에 걸쳐 쪼개진 것
	private static void printSplitCandidates(String xml) {
		java.util.Set<String> intact = new java.util.HashSet<>();
		Matcher direct = PLACEHOLDER.matcher(xml);
		while (direct.find())
			intact.add(direct.group());

		Matcher t = TEXT_RUN.matcher(xml);
		StringBuilder joined = new StringBuilder();
		while (t.find())
			joined.append(t.group(2));
		Matcher m = PLACEHOLDER.matcher(joined);
		System.out.println("   쪼개진 자리표시자:");
		while (m.find()) {
			if (!intact.contains(m.group()))
				System.out.println("   " + m.group());
		}
		System.out.println("   → 한글에서 해당 칸의 자리표시자를 지우고 서식 변경 없이 한 번에 다시 입력하세요.");
	}

	private static void countTableTags(String xml) {
		// OWPML 표 구조 태그 이름은 샘플로 확인 필요. 흔히 쓰이는 tbl/tr/tc를 접두어와 무관하게 센다.
		System.out.printf("-- 표 태그: tbl %d개, tr %d개, tc %d개%n",
				countTag(xml, "tbl"), countTag(xml, "tr"), countTag(xml, "tc"));
	}

	private static int countTag(String xml, String local) {
		Matcher m = Pattern.compile("<\\w+:" + local + "[\\s>]").matcher(xml);
		int n = 0;
		while (m.find())
			n++;
		return n;
	}

	private static int count(String s, String token) {
		int n = 0, i = 0;
		while ((i = s.indexOf(token, i)) >= 0) {
			n++;
			i += token.length();
		}
		return n;
	}

	private static String read(ZipFile zip, ZipEntry e) throws IOException {
		try (InputStream in = zip.getInputStream(e)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
