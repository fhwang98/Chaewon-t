import { useState } from 'react';
import { api, ApiError } from '../api/client';
import Steps from './Steps';

interface Props {
  onUploaded: (id: string) => void;
}

/** 1단계: PDF 올리기. 끌어다 놓거나 파일을 고른다 */
export default function UploadPage({ onUploaded }: Props) {
  const [uploading, setUploading] = useState<string | null>(null); // 올리는 파일 이름
  const [error, setError] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);

  async function upload(file: File | undefined) {
    if (!file || uploading) return;
    // 서버도 검사하지만, 다른 파일을 끌어다 놓은 경우는 올리기 전에 바로 알린다
    if (!file.name.toLowerCase().endsWith('.pdf') && file.type !== 'application/pdf') {
      setError(`PDF 파일만 올릴 수 있습니다. (고른 파일: ${file.name})`);
      return;
    }
    setUploading(file.name);
    setError(null);
    try {
      const status = await api.upload(file);
      onUploaded(status.id);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '서버에 연결하지 못했습니다.');
      setUploading(null);
    }
  }

  return (
    <main className="page narrow">
      <Steps current={0} />
      <div className="card">
        <h1>기출문제 PDF 올리기</h1>
        <p className="lead">2단 시험지 PDF를 올리면 문항·선택지·정답을 읽어 한글 시험지(HWPX)로 만듭니다.</p>
        {/* input을 display:none으로 숨기면 Tab으로 갈 수 없어 화면에서만 감춘다 (키보드로도 파일 고르기) */}
        <label
          className={`dropzone${dragOver ? ' over' : ''}${uploading ? ' busy' : ''}`}
          onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={(e) => { e.preventDefault(); setDragOver(false); upload(e.dataTransfer.files[0]); }}
        >
          <input className="visually-hidden" type="file" accept=".pdf,application/pdf" disabled={!!uploading}
            onChange={(e) => upload(e.target.files?.[0])} />
          <svg className="dropzone-icon" width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
            <path d="M14 3v5h5M12 18v-6M9 15l3-3 3 3" />
          </svg>
          {uploading ? (
            <span className="dropzone-text"><span className="spinner" aria-hidden="true" />{uploading} 올리는 중…</span>
          ) : (
            <>
              <span className="dropzone-text">PDF를 여기로 끌어다 놓으세요</span>
              <span className="button primary" aria-hidden="true">파일 고르기</span>
            </>
          )}
        </label>
        {error && <p className="notice error" role="alert">{error}</p>}
        <p className="hint">
          스캔본이나 프린터로 인쇄한 PDF처럼 글자 정보가 없는 쪽은 글자 인식(OCR)으로 읽어 몇 분 걸립니다.
          틀린 글자가 있을 수 있어 다음 화면에서 노랗게 표시한 곳을 확인해 주세요.
        </p>
      </div>
    </main>
  );
}
