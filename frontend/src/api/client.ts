import type { ConversionStatus, ConversionView, ExamDocument, Problem } from './types';

/** 서버가 돌려준 오류. message는 화면에 그대로 보여 줄 수 있는 문장이다 */
export class ApiError extends Error {
  readonly status: number;
  readonly errorType?: string;

  constructor(message: string, status: number, errorType?: string) {
    super(message);
    this.status = status;
    this.errorType = errorType;
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) {
    throw await toError(res);
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

async function toError(res: Response): Promise<ApiError> {
  try {
    const problem = (await res.json()) as Problem;
    return new ApiError(problem.detail ?? problem.title ?? `오류가 발생했습니다 (${res.status})`, res.status, problem.errorType);
  } catch {
    return new ApiError(`서버 오류 (${res.status})`, res.status);
  }
}

const base = (id: string) => `/api/conversions/${encodeURIComponent(id)}`;

export const api = {
  /** 올리면 바로 돌아온다. 읽기는 서버에서 이어지므로 status로 끝났는지 본다 */
  upload(file: File): Promise<ConversionStatus> {
    const form = new FormData();
    form.append('file', file);
    return request('/api/conversions', { method: 'POST', body: form });
  },

  /** 작업 취소·삭제. 읽는 중이면 서버가 멈춘다 */
  cancel(id: string): Promise<void> {
    return request(base(id), { method: 'DELETE' });
  },

  status(id: string): Promise<ConversionStatus> {
    return request(`${base(id)}/status`);
  },

  get(id: string): Promise<ConversionView> {
    return request(base(id));
  },

  save(id: string, document: ExamDocument): Promise<ConversionView> {
    return request(`${base(id)}/document`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(document),
    });
  },

  render(id: string): Promise<void> {
    return request(`${base(id)}/render`, { method: 'POST' });
  },

  downloadUrl(id: string): string {
    return `${base(id)}/download`;
  },
};
