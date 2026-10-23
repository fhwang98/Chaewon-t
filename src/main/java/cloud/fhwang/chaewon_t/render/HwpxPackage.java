package cloud.fhwang.chaewon_t.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * HWPX(ZIP) 읽기·쓰기. 원본 템플릿은 읽기만 한다.
 *
 * 쓸 때는 바꿀 항목만 교체하고 나머지는 내용 그대로 복사한다. 항목 순서와 압축 방식(STORED/DEFLATED)도 원본과 같게 둔다.
 * mimetype이 첫 항목·STORED가 아니면 한글이 파일을 열지 못한다.
 * (DEFLATED 항목은 JDK가 다시 압축하므로 압축된 바이트는 달라질 수 있지만 풀린 내용은 같다)
 */
final class HwpxPackage {

	private HwpxPackage() {
	}

	static List<String> names(Path hwpx) throws IOException {
		try (ZipFile zip = open(hwpx)) {
			return Collections.list(zip.entries()).stream().map(ZipEntry::getName).toList();
		}
	}

	/** 항목 내용. 없으면 null */
	static byte[] read(Path hwpx, String name) throws IOException {
		try (ZipFile zip = open(hwpx)) {
			ZipEntry e = zip.getEntry(name);
			return e == null ? null : read(zip, e);
		}
	}

	/** template의 모든 항목을 순서대로 out에 쓰되, replaced에 있는 항목은 그 내용으로 바꾼다 */
	static void write(Path template, Path out, Map<String, byte[]> replaced) throws IOException {
		try (ZipFile zip = open(template);
				ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(out), StandardCharsets.UTF_8)) {
			for (ZipEntry src : Collections.list(zip.entries())) {
				byte[] data = replaced.containsKey(src.getName()) ? replaced.get(src.getName()) : read(zip, src);
				zout.putNextEntry(entryLike(src, data));
				zout.write(data);
				zout.closeEntry();
			}
		}
	}

	/** 원본 항목과 같은 이름·압축 방식·시각. STORED는 크기와 CRC를 미리 알려 줘야 한다 */
	private static ZipEntry entryLike(ZipEntry src, byte[] data) {
		ZipEntry e = new ZipEntry(src.getName());
		e.setMethod(src.getMethod());
		if (src.getTime() != -1) {
			e.setTime(src.getTime());
		}
		if (src.getMethod() == ZipEntry.STORED) {
			CRC32 crc = new CRC32();
			crc.update(data);
			e.setSize(data.length);
			e.setCompressedSize(data.length);
			e.setCrc(crc.getValue());
		}
		return e;
	}

	private static ZipFile open(Path hwpx) throws IOException {
		return new ZipFile(hwpx.toFile(), StandardCharsets.UTF_8);
	}

	private static byte[] read(ZipFile zip, ZipEntry e) throws IOException {
		try (InputStream in = zip.getInputStream(e)) {
			return in.readAllBytes();
		}
	}
}
