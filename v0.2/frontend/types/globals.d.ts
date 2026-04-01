export {};

declare global {
  interface Window {
    /** 由 RootLayout 服务端注入，供浏览器直连 Java（不依赖 next build 内联 NEXT_PUBLIC_*） */
    __QOS_API_BASE__?: string;
    /** 由 RootLayout 服务端注入，供 Google 登录按钮读取 Client ID */
    __QOS_GOOGLE_CLIENT_ID__?: string;
  }
}
