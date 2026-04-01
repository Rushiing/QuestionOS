export {};

declare global {
  interface Window {
    /** 由 RootLayout 服务端注入，供浏览器直连 Java（不依赖 next build 内联 NEXT_PUBLIC_*） */
    __QOS_API_BASE__?: string;
  }
}
