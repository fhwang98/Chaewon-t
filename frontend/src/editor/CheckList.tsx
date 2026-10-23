import { useEditorState, type Editor } from '@tiptap/react';
import { clearAllUncertain, selectRange, uncertainList } from './extensions';

/** 확인할 곳 전체 목록. 누르면 그 자리로 간다. 고치면 목록에서 저절로 빠진다 */
export default function CheckList({ editor, onClose }: { editor: Editor; onClose: () => void }) {
  const items = useEditorState({ editor, selector: ({ editor }) => uncertainList(editor.state) });
  return (
    <div className="toolbar-panel checks" role="dialog" aria-label="확인할 곳 목록">
      <div className="checks-head">
        <strong>확인할 곳 {items.length}개</strong>
        <span className="hint">글자 인식(OCR)이 확실하지 않은 곳입니다. 고치거나 "확인함"을 누르면 목록에서 빠집니다.</span>
        <button type="button" className="text-btn" disabled={items.length === 0}
          title="표시를 모두 지웁니다 (Ctrl+Z로 되돌릴 수 있습니다)" onClick={() => clearAllUncertain(editor)}>모두 확인함</button>
        <button type="button" className="text-btn" onClick={onClose}>닫기</button>
      </div>
      <ol className="checks-list">
        {items.map((item) => (
          <li key={item.from}>
            <button type="button" onClick={() => selectRange(editor, item)}>
              <span className="checks-where">{item.question > 0 ? `${item.question}번` : '정답'}</span>
              <span className="checks-text">
                {item.before && '…'}{item.before}<mark>{item.text}</mark>{item.after}{item.after && '…'}
              </span>
            </button>
          </li>
        ))}
      </ol>
    </div>
  );
}
