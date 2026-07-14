import type { Metadata } from 'next'
import Script from 'next/script'
import './globals.css'
import { Providers } from './providers'

export const metadata: Metadata = {
  title: 'QuestionOS - 智能问题校准系统',
  description: '将模糊的问题转化为清晰可执行的指令',
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="zh-CN">
      <head>
        <Script src="/runtime-config.js" strategy="beforeInteractive" />
      </head>
      <body className="antialiased font-sans">
        <Providers>
          <div className="min-h-screen">
            {children}
          </div>
        </Providers>
      </body>
    </html>
  )
}
