/** @type {import('next').NextConfig} */
// 浏览器端用 NEXT_PUBLIC_API_URL（公网或域名）；服务端 rewrite 默认走本机 Java，避免 SSR 绕公网。
const publicApi = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';
const internalApi = process.env.INTERNAL_API_URL || publicApi;

const nextConfig = {
  reactStrictMode: true,
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${internalApi}/api/:path*`,
      },
    ];
  },
}

module.exports = nextConfig