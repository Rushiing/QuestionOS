'use client';

import type { ReactNode } from 'react';
import { AuthProvider } from '../components/AuthProvider';
import { NavigationFeedback } from '../lib/navigation-feedback';

export function Providers({ children }: { children: ReactNode }) {
  return (
    <AuthProvider>
      <NavigationFeedback />
      {children}
    </AuthProvider>
  );
}
