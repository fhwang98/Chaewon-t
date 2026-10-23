import { useCallback, useEffect, useState } from 'react';

/**
 * 화면이 둘(업로드, 에디터)뿐이라 라우터 라이브러리 없이 주소(pathname)만 본다.
 * 프론트 nginx(frontend/nginx.conf의 try_files)가 /conversions/{id}로 바로 들어와도 index.html을 준다.
 */
export function usePath(): [string, (path: string) => void] {
  const [path, setPath] = useState(window.location.pathname);

  useEffect(() => {
    const onPop = () => setPath(window.location.pathname);
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  const navigate = useCallback((next: string) => {
    window.history.pushState(null, '', next);
    setPath(next);
  }, []);

  return [path, navigate];
}
