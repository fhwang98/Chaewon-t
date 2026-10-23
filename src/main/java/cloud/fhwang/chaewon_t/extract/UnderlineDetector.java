package cloud.fhwang.chaewon_t.extract;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * 한 페이지에 그려진 가로선을 모은다. PDF의 밑줄은 글자 속성이 아니라 글자 아래에 그린 선이다.
 *
 * 선(moveTo→lineTo)과 아주 납작한 사각형 둘 다 가로선으로 본다. 좌표는 원점 왼쪽 위로 바꿔서 돌려준다.
 * 샘플 PDF에서 밑줄은 짧은 선이고, 단 너비만 한 선은 상자 테두리였다 → 길이로 거르는 것은 호출하는 쪽(PdfLayout.boxLineMinWidth)
 *
 * 끝이 세로선이나 곡선(둥근 모서리)과 맞닿은 가로선은 상자 테두리로 표시한다(box). sample5·6은 상자가 단보다 좁아(230pt)
 * 길이 기준을 넘지 않아서, 지문 상자 윗변이 바로 위 문항 머리의 밑줄로 잡혔다 (100문항 중 50개)
 */
final class UnderlineDetector extends PDFGraphicsStreamEngine {

	/** 가로선 하나. y는 원점 왼쪽 위. box면 상자 테두리 */
	record Segment(float x1, float x2, float y, boolean box) {
		Segment(float x1, float x2, float y) {
			this(x1, x2, y, false);
		}

		float width() {
			return x2 - x1;
		}
	}

	private static final float FLAT = 1.5f;   // 이보다 얇은 사각형은 선으로 본다
	private static final float LEVEL = 0.5f;  // 시작·끝 y 차이가 이 안이면 가로선
	private static final float JOINT = 2f;    // 가로선 끝과 세로선·곡선이 이만큼 가까우면 맞닿았다고 본다

	/** 세로선 하나 (PDF 좌표 그대로) */
	private record Vertical(double x, double y1, double y2) {
	}

	private final float pageTop;
	private final List<Segment> segments = new ArrayList<>();
	private final List<Vertical> verticals = new ArrayList<>();
	private final List<Point2D> curveEnds = new ArrayList<>();
	private Point2D current;

	private UnderlineDetector(PDPage page) {
		super(page);
		this.pageTop = page.getCropBox().getUpperRightY();
	}

	static List<Segment> horizontalSegments(PDPage page) throws IOException {
		UnderlineDetector detector = new UnderlineDetector(page);
		detector.processPage(page);
		return detector.segments.stream().map(detector::markBox).toList();
	}

	private Segment markBox(Segment s) {
		double y = pageTop - s.y();
		return touches(s.x1(), y) || touches(s.x2(), y) ? new Segment(s.x1(), s.x2(), s.y(), true) : s;
	}

	private boolean touches(double x, double y) {
		return verticals.stream().anyMatch(v -> Math.abs(v.x() - x) <= JOINT && y >= v.y1() - JOINT && y <= v.y2() + JOINT)
				|| curveEnds.stream().anyMatch(p -> Math.abs(p.getX() - x) <= JOINT && Math.abs(p.getY() - y) <= JOINT);
	}

	@Override
	public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
		double minX = Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX()));
		double maxX = Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX()));
		double minY = Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY()));
		double maxY = Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY()));
		if (maxY - minY < FLAT && maxX - minX > FLAT) {
			add(minX, maxX, (minY + maxY) / 2);
		} else if (maxX - minX < FLAT && maxY - minY > FLAT) {
			verticals.add(new Vertical((minX + maxX) / 2, minY, maxY));
		}
	}

	@Override
	public void moveTo(float x, float y) {
		current = new Point2D.Float(x, y);
	}

	@Override
	public void lineTo(float x, float y) {
		if (current != null && Math.abs(current.getY() - y) < LEVEL && Math.abs(current.getX() - x) > FLAT) {
			add(Math.min(current.getX(), x), Math.max(current.getX(), x), y);
		} else if (current != null && Math.abs(current.getX() - x) < LEVEL && Math.abs(current.getY() - y) > FLAT) {
			verticals.add(new Vertical(x, Math.min(current.getY(), y), Math.max(current.getY(), y)));
		}
		current = new Point2D.Float(x, y);
	}

	private void add(double x1, double x2, double y) {
		segments.add(new Segment((float) x1, (float) x2, (float) (pageTop - y)));
	}

	@Override
	public Point2D getCurrentPoint() {
		return current;
	}

	/**
	 * Type3 글꼴은 글자 모양을 선·면으로 그린다. 그대로 두면 글자 획이 가로선으로 잡혀 밑줄로 오인된다
	 * (sample2: 한 페이지에 획 조각 2,400개). 글자는 밑줄이 아니므로 그리지 않고 넘어간다
	 */
	@Override
	protected void showType3Glyph(Matrix textRenderingMatrix, PDType3Font font, int code, Vector displacement) {
	}

	// 아래는 밑줄 찾기에 필요 없는 그리기 연산
	@Override
	public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
		if (current != null) {
			curveEnds.add(current);
		}
		current = new Point2D.Float(x3, y3);
		curveEnds.add(current);
	}

	@Override
	public void closePath() {
	}

	@Override
	public void endPath() {
	}

	@Override
	public void strokePath() {
	}

	@Override
	public void fillPath(int windingRule) {
	}

	@Override
	public void fillAndStrokePath(int windingRule) {
	}

	@Override
	public void drawImage(PDImage pdImage) {
	}

	@Override
	public void clip(int windingRule) {
	}

	@Override
	public void shadingFill(COSName shadingName) {
	}
}
