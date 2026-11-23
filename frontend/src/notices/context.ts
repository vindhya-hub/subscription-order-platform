import { createContext, useContext } from 'react';

export type NoticeTone = 'success' | 'error' | 'info';

export interface Notice {
  id: number;
  tone: NoticeTone;
  text: string;
}

export interface Notices {
  notify: (tone: NoticeTone, text: string) => void;
}

export const NoticeContext = createContext<Notices | null>(null);

/** Short-lived confirmations and errors shown in the corner of the screen. */
export function useNotices(): Notices {
  const notices = useContext(NoticeContext);
  if (!notices) {
    throw new Error('useNotices must be used inside <NoticeProvider>');
  }
  return notices;
}
