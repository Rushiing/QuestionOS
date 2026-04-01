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

/** Google Client ID：支持运行时注入，避免 NEXT_PUBLIC_* 仅构建期生效导致空值 */
function browserGoogleClientIdScript(): string {
  const clientId = (
    process.env.INTERNAL_GOOGLE_CLIENT_ID ||
    process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID ||
    ''
  ).trim()
  return `window.__QOS_GOOGLE_CLIENT_ID__=${JSON.stringify(clientId)};`
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
        <script dangerouslySetInnerHTML={{ __html: browserGoogleClientIdScript() }} />
      </head>
      <body className={`${inter.variable} antialiased`}>
        <main className="min-h-screen">
          {children}
        </main>
      </body>
    </html>
  )
}
