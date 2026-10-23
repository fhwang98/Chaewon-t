import { useEffect, useState, type ReactNode } from 'react';
import { api, ApiError } from '../api/client';
import type { ConversionStatus } from '../api/types';
import Steps from './Steps';

const POLL_MS = 1000;

/**
 * PDF 읽기가 끝날 때까지 진행 상황을 보여 주고, 끝나면 children(에디터)을 그린다.
 * 서버가 읽기를 백그라운드로 하므로 1초마다 상태를 묻는다. 새로고침해도 같은 주소에서 이어서 기다린다.
 */
export default function ProcessingGate({ id, children, onCancelled }: {
  id: string;
  children: ReactNode;
  /** 취소한 뒤 (처음 화면으로) */
  onCancelled: () => void;
}) {
  const [status, setStatus] = useState<ConversionStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);

  async function cancel() {
    setCancelling(true);
    try {
      await api.cancel(id);
      onCancelled();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '취소하지 못했습니다.');
      setCancelling(false);
    }
  }

  useEffect(() => {
    let stopped = false;
    let timer: number | undefined;
    const poll = async () => {
      try {
        const s = await api.status(id);
        if (stopped) return;
        setStatus(s);
        if (s.state === 'PROCESSING') {
          timer = window.setTimeout(poll, POLL_MS);
        }
      } catch (e) {
        if (!stopped) setError(e instanceof ApiError ? e.message : '서버에 연결하지 못했습니다.');
      }
    };
    poll();
    return () => {
      stopped = true;
      window.clearTimeout(timer);
    };
  }, [id]);

  if (status?.state === 'READY') {
    return <>{children}</>;
  }
  const failed = error ?? (status?.state === 'FAILED' ? status.error ?? 'PDF를 읽지 못했습니다.' : null);
  if (failed) {
    return (
      <main className="page narrow">
        <Steps current={1} />
        <div className="card">
          <h1>PDF를 읽지 못했습니다</h1>
          <p className="notice error" role="alert">{failed}</p>
          <a className="button primary" href="/">다른 PDF 올리기</a>
        </div>
      </main>
    );
  }
  const percent = status && status.totalPages > 0 ? Math.round((status.donePages / status.totalPages) * 100) : null;
  return (
    <main className="page narrow">
      <Steps current={1} />
      <div className="card">
        <h1>PDF 읽는 중</h1>
        <p className="progress-text" aria-live="polite">
          {percent == null && <span className="spinner" aria-hidden="true" />}
          {progressText(status)}
        </p>
        {/* 쪽 수를 알기 전에는 막대를 그리지 않는다 (0%로 멈춘 것처럼 보이지 않게) */}
        {percent != null && (
          <div className="progress" role="progressbar" aria-label="읽은 쪽" aria-valuemin={0}
            aria-valuemax={status!.totalPages} aria-valuenow={status!.donePages}>
            <div className="progress-bar" style={{ width: `${percent}%` }} />
          </div>
        )}
        {status && status.ocrPages > 0 && (
          <p className="hint">글자 정보가 없는 쪽을 글자 인식(OCR)으로 읽고 있습니다. 한 쪽에 몇 초씩 걸립니다.
            이 화면을 닫아도 서버에서 계속 읽고, 같은 주소로 다시 오면 이어서 볼 수 있습니다.</p>
        )}
        <div className="card-actions">
          <button type="button" disabled={cancelling} onClick={cancel}>
            {cancelling ? '취소하는 중…' : '변환 취소'}
          </button>
        </div>
      </div>
    </main>
  );
}

export function progressText(status: ConversionStatus | null): string {
  if (!status || status.totalPages === 0) {
    return 'PDF를 읽을 준비를 하는 중…';
  }
  const ocr = status.ocrPages > 0 ? ` (글자 인식 ${status.ocrPages}쪽)` : '';
  const eta = remainingText(status.remainingSeconds);
  return `PDF를 읽는 중… ${status.donePages}/${status.totalPages}쪽${ocr}${eta ? ` · ${eta}` : ''}`;
}

/** 서버가 어림한 남은 시간. 처음 몇 쪽이 끝나면 실제 속도로 다시 계산된다 */
export function remainingText(seconds: number | null | undefined): string {
  if (seconds == null) return '';
  if (seconds <= 5) return '곧 끝납니다';
  if (seconds < 60) return `약 ${Math.ceil(seconds / 5) * 5}초 남음`;
  const min = Math.floor(seconds / 60);
  const sec = Math.round((seconds % 60) / 10) * 10;
  return sec === 0 || sec === 60 ? `약 ${sec === 60 ? min + 1 : min}분 남음` : `약 ${min}분 ${sec}초 남음`;
}
