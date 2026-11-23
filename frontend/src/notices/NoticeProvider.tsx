import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import { NoticeContext, type Notice, type NoticeTone } from './context';

const VISIBLE_FOR_MS: Record<NoticeTone, number> = { success: 5000, info: 5000, error: 9000 };
const MAX_NOTICES = 4;

export function NoticeProvider({ children }: { children: ReactNode }) {
  const [notices, setNotices] = useState<Notice[]>([]);
  const nextId = useRef(0);

  const dismiss = useCallback((id: number) => {
    setNotices((current) => current.filter((notice) => notice.id !== id));
  }, []);

  const notify = useCallback(
    (tone: NoticeTone, text: string) => {
      nextId.current += 1;
      const id = nextId.current;
      setNotices((current) => [...current.slice(1 - MAX_NOTICES), { id, tone, text }]);
      window.setTimeout(() => dismiss(id), VISIBLE_FOR_MS[tone]);
    },
    [dismiss],
  );

  const value = useMemo(() => ({ notify }), [notify]);

  return (
    <NoticeContext value={value}>
      {children}
      <div className="notices" role="status" aria-live="polite">
        {notices.map((notice) => (
          <div key={notice.id} className={`notice notice-${notice.tone}`}>
            <span>{notice.text}</span>
            <button type="button" className="notice-close" aria-label="Dismiss" onClick={() => dismiss(notice.id)}>
              ×
            </button>
          </div>
        ))}
      </div>
    </NoticeContext>
  );
}
