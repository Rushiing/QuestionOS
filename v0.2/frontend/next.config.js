/** @type {import('next').NextConfig} */
// 浏览器侧 API 走同源 /api/*，由 app/api/[[...path]]/route.ts 在运行时转发到后端（避免 rewrites 在 build 时被写死成 localhost 导致线上 502）。
const nextConfig = {
  reactStrictMode: true,
  async headers() {
    return [
      {
        source: '/_next/static/:path*',
        headers: [
          {
            key: 'Cache-Control',
            value: 'public, max-age=31536000, immutable',
          },
        ],
      },
    ];
  },
};

module.exports = nextConfig;
