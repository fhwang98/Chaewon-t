// 서버 JSON 모양. Java 레코드(cloud.fhwang.chaewon_t.extract.ExamDocument, conversion.ConversionView)와 같게 유지한다.

export interface Span {
  text: string;
  underline: boolean;
  bold: boolean;
  /** OCR이 자신 없게 읽은 글자. 에디터에서 노란색, 고치면 사라진다 */
  uncertain?: boolean;
  // 에디터에서 넣는 글자 모양 (서버는 없으면 null을 보낸다)
  italic?: boolean | null;
  strike?: boolean | null;
  script?: 'sup' | 'sub' | null;
  /** 글자 크기 pt */
  size?: number | null;
  /** #RRGGBB */
  color?: string | null;
}

/** 문단. 정렬·들여쓰기는 바꾼 것만 있고, 없으면 역할(문항·지문·선택지) 기본 모양 */
export interface Paragraph {
  spans: Span[];
  align?: 'left' | 'center' | 'right' | 'justify' | null;
  /** 역할 기본 왼쪽 여백에 더할 들여쓰기 pt */
  indent?: number | null;
  /** 첫 줄 들여쓰기 pt (음수면 내어쓰기) */
  firstLine?: number | null;
}

/** 빈 줄 없이 이어지는 문단 묶음. 한글에서는 상자 하나로 보인다 */
export interface Block {
  paragraphs: Paragraph[];
}

export interface Question {
  number: number;
  prompt: Paragraph;
  /** 질문 끝 [ ] 안의 출처 */
  source: string;
  body: Block[];
  /** 선택지 ①~⑤ (번호 글자 포함). 주관식이면 비어 있다 */
  choices: Paragraph[];
}

export interface Answer {
  number: number;
  answer: string;
  explanation: string;
}

export interface ExamDocument {
  headerTitle: string;
  questions: Question[];
  answers: Answer[];
}

/** PDF 읽기 진행 상태 (Java conversion.ConversionStatus). OCR이 필요한 PDF는 몇 분 걸린다 */
export interface ConversionStatus {
  id: string;
  state: 'PROCESSING' | 'READY' | 'FAILED';
  donePages: number;
  /** 읽기 시작 전이면 0 */
  totalPages: number;
  /** 글자 인식(OCR)으로 읽는 페이지 수 */
  ocrPages: number;
  /** FAILED일 때 보여 줄 문장 */
  error: string | null;
  /** 읽는 중일 때 남은 시간 예상 (초). 모르면 null */
  remainingSeconds?: number | null;
}

export interface ConversionView {
  id: string;
  document: ExamDocument;
  warnings: string[];
  hasResult: boolean;
}

/** 오류 응답 (RFC 9457 ProblemDetail + errorType) */
export interface Problem {
  status?: number;
  title?: string;
  detail?: string;
  errorType?: string;
}
