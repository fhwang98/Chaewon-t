package cloud.fhwang.chaewon_t.probe;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * 1단계 확인용: PDF 텍스트를 페이지별로, 좌표와 함께 출력한다. (Apache PDFBox 3.0.x)
 *
 * 실행: PdfProbe <sample.pdf> [출력폴더]
 *
 * 출력 파일
 *  - page-N.txt        : 페이지 전체 텍스트 (글자 깨짐, 표 한 행이 한 줄로 나오는지 확인)
 *  - page-N-coords.txt : 텍스트 조각마다 x, y, 너비, 높이 (PDFTextStripperByArea 영역 좌표 정할 때 사용)
 *
 * 좌표 기준: 단위 pt, 원점은 페이지 왼쪽 위. PDFTextStripperByArea도 같은 기준(getX, getY)으로 영역을 판정한다.
 */
public class PdfProbe {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("사용법: PdfProbe <sample.pdf> [출력폴더]");
            return;
        }
        File pdf = new File(args[0]);
        Path outDir = Path.of(args.length > 1 ? args[1] : "probe-out/pdf");
        Files.createDirectories(outDir);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int pages = doc.getNumberOfPages();
            System.out.println("페이지 수: " + pages);

            for (int p = 1; p <= pages; p++) {
                PDPage page = doc.getPage(p - 1);
                PDRectangle box = page.getMediaBox();
                System.out.printf(Locale.ROOT, "%n=== %d페이지 (%.1f x %.1f pt, 회전 %d도)%n",
                        p, box.getWidth(), box.getHeight(), page.getRotation());

                String text = plainText(doc, p);
                if (text.isBlank()) {
                    System.out.println("[경고] 텍스트 없음. 스캔(이미지) PDF일 수 있습니다.");
                }
                Files.writeString(outDir.resolve("page-" + p + ".txt"), text, StandardCharsets.UTF_8);

                Path coordsFile = outDir.resolve("page-" + p + "-coords.txt");
                writeCoordinates(doc, p, coordsFile);

                System.out.println(text);
                System.out.println("→ 저장: " + outDir.resolve("page-" + p + ".txt") + ", " + coordsFile);
            }
        }
    }

    private static String plainText(PDDocument doc, int pageNo) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        return stripper.getText(doc);
    }

    private static void writeCoordinates(PDDocument doc, int pageNo, Path file) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            out.println("x\ty\tw\th\ttext");

            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (positions.isEmpty() || text.isBlank()) return;
                    TextPosition first = positions.get(0);
                    TextPosition last = positions.get(positions.size() - 1);
                    float width = last.getX() + last.getWidth() - first.getX();
                    out.printf(Locale.ROOT, "%.1f\t%.1f\t%.1f\t%.1f\t%s%n",
                            first.getX(), first.getY(), width, first.getHeight(), text);
                }
            };
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageNo);
            stripper.setEndPage(pageNo);
            stripper.getText(doc); // writeString 콜백으로 좌표가 기록된다
        }
    }
}
