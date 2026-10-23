import type { JSONContent } from '@tiptap/core';
import { EditorContent, useEditor, type Editor } from '@tiptap/react';
import { useEffect, useState, type CSSProperties, type ReactNode } from 'react';
import { examExtensions } from './extensions';
import { headerFontSize } from './headerFit';
import Toolbar from './Toolbar';
import './exam.css';

interface Props {
  initial: JSONContent;
  headerTitle: string;
  onHeaderTitleChange: (title: string) => void;
  onChange: (json: JSONContent) => void;
  /** 도구 막대 왼쪽 끝 (처음으로) */
  leading?: ReactNode;
  /** 도구 막대 오른쪽 끝 (저장 상태, HWPX 버튼) */
  actions?: ReactNode;
}

/**
 * 쪽 모양 (pt). 한글 템플릿(data/templates/exam/v1/template.hwpx)의 구역 설정 값:
 * A4, 좌우 여백 48.2, 위·아래 14.2, 머리말·꼬리말 영역 62.4, 2단 간격 22.7
 */
const PAGE_W = 595.28;
const PAGE_H = 841.88;
const MARGIN_X = 48.18;
const BODY_TOP = 14.17 + 62.36;
const BODY_H = PAGE_H - BODY_TOP - 62.36 - 14.17;
const BODY_W = PAGE_W - MARGIN_X * 2;
const COL_GAP = 22.68;
const COL_W = (BODY_W - COL_GAP) / 2;
/** 화면에서 쪽과 쪽 사이 회색 틈 */
const SHEET_GAP = 18;
/** 한 쪽의 본문 줄(2단) 다음 쪽 본문 줄까지 띄울 거리 = 아래 여백 + 틈 + 다음 쪽 위 여백 */
const ROW_GAP = PAGE_H - BODY_H + SHEET_GAP;

/**
 * 2단을 쪽마다 나눠 흘리는 CSS(column-height, 다단 줄바꿈)는 Chrome 145부터 된다 (2026-10 기준 실험 기능).
 * 안 되는 브라우저는 단 하나 너비의 긴 종이로 보여 준다 (줄바꿈 위치는 한글과 비슷하게 유지된다)
 */
const PAGED = typeof CSS !== 'undefined' && CSS.supports('column-height', '10px');

const ZOOMS = [1, 1.25, 1.5, 1.75];
const ZOOM_KEY = 'exam-editor-zoom';

const u = (pt: number) => `calc(${pt}pt * var(--z))`;

/** 한글 시험지처럼 보이는 문서 에디터: 쪽, 머리말, 2단, 문항 번호 자동, 지문 상자, 정답·해설까지 한 문서 */
export default function ExamEditor({ initial, headerTitle, onHeaderTitleChange, onChange, leading, actions }: Props) {
  const editor = useEditor({
    extensions: examExtensions,
    content: initial,
    // 맞춤법 검사 끔: 크롬이 영어 지문·한글에 빨간 점선을 긋고, 그 글자의 굵게(획 덧그리기)가 빠져 보였다 (2026-10-22)
    editorProps: { attributes: { spellcheck: 'false', autocorrect: 'off', autocapitalize: 'off' } },
    onUpdate: ({ editor }) => onChange(editor.getJSON()),
  });
  const [zoom, setZoom] = useState(loadZoom);
  const pages = usePageCount(editor, zoom);

  const changeZoom = (z: number) => {
    setZoom(z);
    try {
      localStorage.setItem(ZOOM_KEY, String(z));
    } catch {
      // 저장 못 해도 이번 화면에서는 적용된다
    }
  };

  // 이름이 길면 HWPX처럼 글자를 줄여 한 줄로 보여 준다
  const titleStyle = { fontSize: u(headerFontSize(headerTitle)) };
  const title = (
    <input className="sheet-title" style={titleStyle} value={headerTitle} placeholder="머리말 (시험 이름)" aria-label="머리말"
      maxLength={200} spellCheck={false} onChange={(e) => onHeaderTitleChange(e.target.value)} />
  );

  return (
    <div className="exam-editor">
      <Toolbar editor={editor} zoom={zoom} zooms={ZOOMS} onZoom={changeZoom} leading={leading} actions={actions} />
      <div className="desk">
        {PAGED ? (
          <div className="pages" style={{ '--z': zoom, width: u(PAGE_W),
            height: u(pages * PAGE_H + (pages - 1) * SHEET_GAP) } as CSSProperties}>
            {Array.from({ length: pages }, (_, i) => (
              <div key={i} className="sheet" style={{ top: u(i * (PAGE_H + SHEET_GAP)), width: u(PAGE_W), height: u(PAGE_H) }}>
                <div className="sheet-header">{i === 0 ? title : <span className="sheet-title" style={titleStyle}>{headerTitle}</span>}</div>
                <div className="sheet-footer">- {i + 1} -</div>
              </div>
            ))}
            <EditorContent editor={editor} className="exam paged" style={{ left: u(MARGIN_X), top: u(BODY_TOP),
              width: u(BODY_W), '--col-h': u(BODY_H), '--row-gap': u(ROW_GAP), '--col-gap': u(COL_GAP) } as CSSProperties} />
          </div>
        ) : (
          <div className="sheet strip" style={{ '--z': zoom, width: u(COL_W + 48), minHeight: u(PAGE_H) } as CSSProperties}>
            <div className="sheet-header">{title}</div>
            <EditorContent editor={editor} className="exam" />
          </div>
        )}
      </div>
    </div>
  );
}

function loadZoom(): number {
  try {
    const saved = Number(localStorage.getItem(ZOOM_KEY));
    return ZOOMS.includes(saved) ? saved : 1.25;
  } catch {
    return 1.25;
  }
}

/** 본문 높이를 재서 쪽 수를 정한다. 본문(2단 줄들) 높이 = 쪽 수 × 본문 높이 + (쪽 수 - 1) × 줄 사이 */
function usePageCount(editor: Editor, zoom: number): number {
  const [pages, setPages] = useState(1);
  useEffect(() => {
    if (!PAGED) return;
    const dom = editor.view.dom as HTMLElement;
    const measure = () => {
      const heightPt = dom.offsetHeight / (zoom * 96 / 72);
      setPages(Math.max(1, Math.round((heightPt + ROW_GAP) / (BODY_H + ROW_GAP))));
    };
    const observer = new ResizeObserver(measure);
    observer.observe(dom);
    measure();
    return () => observer.disconnect();
  }, [editor, zoom]);
  return pages;
}
