import type { Metadata } from 'next'
import { Inter } from 'next/font/google'
import './globals.css'

/** 避免 layout 被静态化导致只读到 build 时的环境变量 */
export const dynamic = 'force-dynamic'

const inter = Inter({ 
  subsets: ['latin'],
  variable: '--font-inter',
})

export const metadata: Metadata = {
  title: 'QuestionOS - 智能问题校准系统',
  description: '将模糊的问题转化为清晰可执行的指令',
}

/** 浏览器 API 基址：优先运行时变量（Railway 上 INTERNAL_API_URL 常在运行时才有，未必参与 client bundle 构建） */
function browserApiBaseScript(): string {
  const raw = (process.env.INTERNAL_API_URL || process.env.NEXT_PUBLIC_API_URL || '').trim()
  const base = raw.replace(/\/$/, '')
  return `window.__QOS_API_BASE__=${JSON.stringify(base)};`
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="zh-CN">
      <head>
        <script dangerouslySetInnerHTML={{ __html: browserApiBaseScript() }} />
      </head>
      <body className={`${inter.variable} antialiased`}>
        <main className="min-h-screen">
          {children}
        </main>
      </body>
    </html>
  )
}
