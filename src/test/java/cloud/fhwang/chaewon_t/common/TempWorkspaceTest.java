package cloud.fhwang.chaewon_t.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TempWorkspaceTest {

	@TempDir
	Path tmp;

	TempWorkspace workspace;

	@BeforeEach
	void setUp() {
		workspace = new TempWorkspace(new AppProperties(tmp.resolve("templates"), tmp.resolve("work"), Duration.ofHours(1), 20, "exam", AppProperties.Ocr.defaults()));
	}

	@Test
	void 작업_폴더_안의_파일_경로를_준다() {
		String id = workspace.create();

		Path file = workspace.file(id, "input.pdf");

		assertThat(file).isEqualTo(tmp.resolve("work").resolve(id).resolve("input.pdf").toAbsolutePath().normalize());
		assertThat(file.getParent()).isDirectory();
	}

	@Test
	void UUID가_아닌_id는_거부한다() {
		assertThatThrownBy(() -> workspace.file("../../etc", "passwd"))
				.isInstanceOf(ConversionException.class)
				.extracting("type").isEqualTo(ErrorType.JOB_NOT_FOUND);
	}

	@Test
	void 파일_이름으로_폴더_밖에_나갈_수_없다() {
		String id = workspace.create();

		assertThatThrownBy(() -> workspace.file(id, "../other/x")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> workspace.file(id, "sub/x")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 보관_시간이_지난_폴더만_지운다() throws Exception {
		String oldId = workspace.create();
		Files.writeString(workspace.file(oldId, "a.txt"), "x");
		String newId = workspace.create();

		Path oldDir = workspace.file(oldId, "a.txt").getParent();
		Files.setLastModifiedTime(oldDir, java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofHours(2))));

		int deleted = workspace.deleteOlderThan(Instant.now().minus(Duration.ofHours(1)));

		assertThat(deleted).isEqualTo(1);
		assertThat(oldDir).doesNotExist();
		assertThat(workspace.file(newId, "x").getParent()).isDirectory();
	}
}
