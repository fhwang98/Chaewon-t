package cloud.fhwang.chaewon_t.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 변환 작업 1건 = data/tmp/{UUID}/ 폴더 1개.
 * 업로드 PDF, 추출 결과 JSON, 생성된 HWPX를 이 폴더에 두고, 보관 시간이 지나면 폴더째 지운다.
 */
@Component
public class TempWorkspace {

	private static final Logger log = LoggerFactory.getLogger(TempWorkspace.class);

	private final Path root;
	private final AppProperties props;

	public TempWorkspace(AppProperties props) {
		this.props = props;
		this.root = props.tmpDir().toAbsolutePath().normalize();
	}

	public String create() {
		String id = UUID.randomUUID().toString();
		try {
			Files.createDirectories(root.resolve(id));
		} catch (IOException e) {
			throw new ConversionException(ErrorType.STORAGE_FAILED, "작업 폴더를 만들지 못했습니다.", e);
		}
		return id;
	}

	/**
	 * 작업 폴더 안의 파일 경로. id와 파일 이름을 검사해 data/tmp 밖으로 나가지 못하게 한다 (경로 조작 방지).
	 */
	public Path file(String id, String name) {
		Path dir = dir(id);
		Path file = dir.resolve(name).normalize();
		if (!dir.equals(file.getParent())) {
			throw new IllegalArgumentException("작업 폴더 밖의 경로입니다.");
		}
		return file;
	}

	/** 작업 폴더 경로 (OCR 페이지 그림 같은 중간 파일을 둔다) */
	public Path directory(String id) {
		return dir(id);
	}

	public boolean exists(String id) {
		return Files.isDirectory(dir(id));
	}

	public void delete(String id) {
		deleteRecursively(dir(id));
	}

	@Scheduled(fixedDelayString = "${chaewon.cleanup-interval:PT10M}")
	public void deleteExpired() {
		int deleted = deleteOlderThan(Instant.now().minus(props.retention()));
		if (deleted > 0) {
			log.info("만료된 작업 폴더 {}개 삭제", deleted);
		}
	}

	/** 마지막 수정 시각이 cutoff 이전인 작업 폴더를 지운다. 지운 개수를 돌려준다. */
	int deleteOlderThan(Instant cutoff) {
		if (!Files.isDirectory(root)) {
			return 0;
		}
		List<Path> expired;
		try (Stream<Path> dirs = Files.list(root)) {
			expired = dirs.filter(Files::isDirectory)
					.filter(d -> isUuid(d.getFileName().toString()))
					.filter(d -> lastModified(d).isBefore(cutoff))
					.toList();
		} catch (IOException e) {
			log.warn("작업 폴더 목록을 읽지 못함: {}", e.getClass().getSimpleName());
			return 0;
		}
		expired.forEach(TempWorkspace::deleteRecursively);
		return expired.size();
	}

	private Path dir(String id) {
		// UUID 형식만 허용하므로 "../" 같은 값은 여기서 막힌다. startsWith는 한 번 더 확인하는 용도
		if (!isUuid(id)) {
			throw jobNotFound();
		}
		Path dir = root.resolve(id).normalize();
		if (!dir.startsWith(root)) {
			throw jobNotFound();
		}
		return dir;
	}

	private static boolean isUuid(String id) {
		if (id == null || id.length() != 36) {
			return false;
		}
		try {
			return UUID.fromString(id).toString().equals(id);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static Instant lastModified(Path dir) {
		try {
			return Files.getLastModifiedTime(dir).toInstant();
		} catch (IOException e) {
			return Instant.MAX; // 못 읽으면 지우지 않는다
		}
	}

	private static void deleteRecursively(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		} catch (IOException e) {
			log.warn("작업 폴더 삭제 실패: {}", e.getClass().getSimpleName());
		}
	}

	private static ConversionException jobNotFound() {
		return new ConversionException(ErrorType.JOB_NOT_FOUND,
				"변환 작업을 찾을 수 없습니다. 보관 시간이 지나 삭제되었을 수 있습니다.");
	}
}
