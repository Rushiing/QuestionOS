import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { Session, Message, CalibrationProgress } from '@/types';

interface AppState {
  // 当前会话
  currentSession: Session | null;
  messages: Message[];
  progress: CalibrationProgress | null;
  
  // Actions
  setCurrentSession: (session: Session | null) => void;
  setMessages: (messages: Message[]) => void;
  addMessage: (message: Message) => void;
  setProgress: (progress: CalibrationProgress | null) => void;
  clearSession: () => void;
}

export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
      // Initial state
      currentSession: null,
      messages: [],
      progress: null,

      // Actions
      setCurrentSession: (session) => set({ currentSession: session }),
      
      setMessages: (messages) => set({ messages }),
      
      addMessage: (message) => 
        set((state) => ({ 
          messages: [...state.messages, message] 
        })),
      
      setProgress: (progress) => set({ progress }),
      
      clearSession: () => set({ 
        currentSession: null, 
        messages: [], 
        progress: null 
      }),
    }),
    {
      name: 'questionos-storage',
      partialize: (state) => ({ 
        currentSession: state.currentSession,
        messages: state.messages,
      }),
    }
  )
);

// 会话状态store
interface SessionState {
  isLoading: boolean;
  error: string | null;
  setLoading: (loading: boolean) => void;
  setError: (error: string | null) => void;
  clearError: () => void;
}

export const useSessionStore = create<SessionState>()((set) => ({
  isLoading: false,
  error: null,
  setLoading: (loading) => set({ isLoading: loading }),
  setError: (error) => set({ error }),
  clearError: () => set({ error: null }),
}));
