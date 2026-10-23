import { usePath } from './route';
import EditorPage from './pages/EditorPage';
import ProcessingGate from './pages/ProcessingGate';
import UploadPage from './pages/UploadPage';

const EDITOR_PATH = /^\/conversions\/([0-9a-f-]{36})$/;

export default function App() {
  const [path, navigate] = usePath();
  const editor = EDITOR_PATH.exec(path);

  return (
    <div className="app">
      <header className="app-header">
        <a className="brand" href="/" onClick={(e) => { e.preventDefault(); navigate('/'); }}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
            strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
            <path d="M14 3v5h5M9 13h6M9 17h4" />
          </svg>
          PDF → HWPX 시험지
        </a>
      </header>
      {editor
        ? (
          <ProcessingGate key={editor[1]} id={editor[1]} onCancelled={() => navigate('/')}>
            <EditorPage id={editor[1]} onHome={() => navigate('/')} />
          </ProcessingGate>
        )
        : <UploadPage onUploaded={(id) => navigate(`/conversions/${id}`)} />}
    </div>
  );
}
