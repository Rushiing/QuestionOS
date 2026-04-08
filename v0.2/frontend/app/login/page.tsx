'use client';

import { useState } from 'react';
import { GoogleLoginButton } from '../../components/GoogleLoginButton';

export default function LoginPage() {
  const [error] = useState('');

  return (
    <div className="min-h-screen bg-gradient-to-b from-gray-50 to-white flex items-center justify-center px-4">
      <div className="w-full max-w-md">
        {/* Logo */}
        <div className="text-center mb-8">
          <h1 className="text-3xl font-bold text-slate-800">QuestionOS</h1>
          <p className="text-slate-500 mt-2">问题校准助手</p>
        </div>

        {/* Login Form */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-8">
          <h2 className="text-xl font-semibold text-slate-800 mb-6">登录</h2>

          <p className="text-sm text-slate-500 mb-5">
            当前 MVP 版本仅支持 Google 登录。邮箱账号登录将在后续迭代开放。
          </p>

          {error && (
            <div className="text-red-600 text-sm bg-red-50 px-4 py-2 rounded-lg mb-4">
              {error}
            </div>
          )}

          <GoogleLoginButton />

          <p className="text-center text-slate-500 text-sm mt-6">
            还没有账号？{' '}
            <a href="/register" className="text-teal-600 hover:text-teal-700 font-medium">
              立即注册
            </a>
          </p>
        </div>
      </div>
    </div>
  );
}