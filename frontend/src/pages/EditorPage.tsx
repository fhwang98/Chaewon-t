import type { JSONContent } from '@tiptap/core';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { ConversionView } from '../api/types';
import { fromEditorJson, toEditorJson } from '../editor/convert';
import ExamEditor from '../editor/ExamEditor';

type SaveState = 'saved' | 'dirty' | 'saving' | 'error';

const SAVE_DELAY_MS = 1500;

const SAVE_LABEL: Record<SaveState, string> = {
  saved: '저장됨',
  dirty: '고치는 중…',
  saving: '저장 중…',
  error: '저장 실패 (다시 고치면 다시 시도합니다)',
};

/**
 * 2단계: 시험지 고치기. 머리말·문항·정답까지 한 문서로 고치고, 고친 뒤 1.5초가 지나면 서버에 자동 저장한다.
 * (서버의 경고 목록은 부정확한 것이 많아 화면에 보이지 않는다. 2026-10-21 사용자 요청)
 */
export default function EditorPage({ id, onHome }: { id: string; onHome: () => void }) {
  const [view, setView] = useState<ConversionView | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [headerTitle, setHeaderTitle] = useState('');
  const [saveState, setSaveState] = useState<SaveState>('saved');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // 자동 저장은 타이머에서 실행되므로 최신 값을 ref로 들고 있는다
  const editorJson = useRef<JSONContent | null>(null);
  const titleRef = useRef('');
  const editSeq = useRef(0);       // 고칠 때마다 1 증가
  const savedSeq = useRef(0);      // 마지막으로 저장한 시점의 editSeq
  const timer = useRef<number | undefined>(undefined);
  const queue = useRef<Promise<unknown>>(Promise.resolve()); // 저장 요청을 순서대로 보낸다

  useEffect(() => {
    api.get(id)
      .then((v) => {
        editorJson.current = toEditorJson(v.document);
        titleRef.current = v.document.headerTitle;
        setHeaderTitle(v.document.headerTitle);
        setView(v);
      })
      .catch((e) => setLoadError(e instanceof ApiError ? e.message : '서버에 연결하지 못했습니다.'));
  }, [id]);

  const initialJson = useMemo(() => (view ? toEditorJson(view.document) : null), [view]);

  const saveNow = useCallback((): Promise<unknown> => {
    window.clearTimeout(timer.current);
    if (!editorJson.current || editSeq.current === savedSeq.current) {
      return queue.current;
    }
    const seq = editSeq.current;
    const doc = fromEditorJson(editorJson.current, titleRef.current);
    const run = queue.current.then(async () => {
      setSaveState('saving');
      try {
        await api.save(id, doc);
        savedSeq.current = Math.max(savedSeq.current, seq);
        setSaveState(editSeq.current === savedSeq.current ? 'saved' : 'dirty');
      } catch (e) {
        setSaveState('error');
        throw e;
      }
    });
    queue.current = run.catch(() => undefined);
    return run;
  }, [id]);

  const changed = useCallback(() => {
    editSeq.current++;
    setSaveState('dirty');
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => { saveNow().catch(() => undefined); }, SAVE_DELAY_MS);
  }, [saveNow]);

  // 저장 안 된 채로 나가려 하면 브라우저가 묻게 한다
  useEffect(() => {
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (editSeq.current !== savedSeq.current) e.preventDefault();
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, []);

  /** 처음 화면으로. 고친 것은 먼저 저장한다 (저장에 실패하면 남아서 알린다) */
  async function goHome() {
    try {
      await saveNow();
      onHome();
    } catch {
      setActionError('저장하지 못해 이동하지 않았습니다. 잠시 뒤 다시 눌러 주세요.');
    }
  }

  async function makeHwpx() {
    setBusy(true);
    setActionError(null);
    try {
      await saveNow();
      await api.render(id);
      window.location.assign(api.downloadUrl(id));
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : 'HWPX를 만들지 못했습니다.');
    } finally {
      setBusy(false);
    }
  }

  if (loadError) {
    return (
      <main className="page narrow">
        <div className="card">
          <h1>문서를 열지 못했습니다</h1>
          <p className="notice error" role="alert">{loadError}</p>
          <a className="button primary" href="/">처음으로</a>
        </div>
      </main>
    );
  }
  if (!view || !initialJson) {
    return (
      <main className="page narrow">
        <p className="progress-text" aria-live="polite"><span className="spinner" aria-hidden="true" />불러오는 중…</p>
      </main>
    );
  }

  return (
    <main className="editor-page">
      <ExamEditor initial={initialJson} headerTitle={headerTitle}
        leading={<button type="button" className="text-btn" title="처음 화면으로 (고친 내용은 저장됩니다)" onClick={goHome}>← 새 PDF</button>}
        onHeaderTitleChange={(title) => { titleRef.current = title; setHeaderTitle(title); changed(); }}
        onChange={(json) => { editorJson.current = json; changed(); }}
        actions={(
          <>
            {actionError && <span className="error" role="alert">{actionError}</span>}
            <span className={`save-state ${saveState}`} aria-live="polite">{SAVE_LABEL[saveState]}</span>
            <button type="button" className="primary" disabled={busy} onClick={makeHwpx}>
              {busy ? '만드는 중…' : 'HWPX 내려받기'}
            </button>
          </>
        )} />
    </main>
  );
}
