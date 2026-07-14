'use client';

import { useEffect, useRef, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';

const NAVIGATION_START_EVENT = 'qos:navigation-start';
const PREFETCH_ROUTES = ['/', '/history', '/chat', '/consult', '/login'];

export function beginNavigation(): void {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(NAVIGATION_START_EVENT));
  }
}

export function NavigationFeedback() {
  const router = useRouter();
  const pathname = usePathname();
  const [pending, setPending] = useState(false);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    PREFETCH_ROUTES.forEach((route) => router.prefetch(route));
  }, [router]);

  useEffect(() => {
    const handleStart = () => {
      setPending(true);
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      timeoutRef.current = setTimeout(() => setPending(false), 12_000);
    };

    window.addEventListener(NAVIGATION_START_EVENT, handleStart);
    return () => {
      window.removeEventListener(NAVIGATION_START_EVENT, handleStart);
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
    };
  }, []);

  useEffect(() => {
    setPending(false);
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
  }, [pathname]);

  useEffect(() => {
    document.documentElement.toggleAttribute('data-route-pending', pending);
    return () => document.documentElement.removeAttribute('data-route-pending');
  }, [pending]);

  if (!pending) return null;

  return (
    <div
      className="pointer-events-none fixed inset-x-0 top-0 z-[100] h-0.5 overflow-hidden bg-[#d5ded9]"
      role="progressbar"
      aria-label="页面正在加载"
    >
      <span className="qos-route-progress block h-full w-1/3 bg-[#2f6a4a]" />
    </div>
  );
}
