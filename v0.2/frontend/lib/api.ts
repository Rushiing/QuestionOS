import axios, { AxiosInstance, AxiosRequestConfig, AxiosResponse } from 'axios';
import { API_BASE_URL } from './runtime-config';

// API基础配置

// 创建axios实例
const apiClient: AxiosInstance = axios.create({
  baseURL: `${API_BASE_URL}/api`,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// 请求拦截器
apiClient.interceptors.request.use(
  (config) => {
    // 可以在这里添加认证token
    const token = typeof window !== 'undefined' ? localStorage.getItem('token') : null;
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// 响应拦截器
apiClient.interceptors.response.use(
  (response: AxiosResponse) => {
    return response.data;
  },
  (error) => {
    // 统一错误处理
    if (error.response) {
      // 服务器返回错误状态码
      const { status, data } = error.response;
      console.error(`API Error ${status}:`, data);
      
      if (status === 401) {
        // 未授权，清除token并跳转登录
        if (typeof window !== 'undefined') {
          localStorage.removeItem('token');
          window.location.href = '/login';
        }
      }
    } else if (error.request) {
      // 请求发出但没有收到响应
      console.error('Network Error:', error.request);
    } else {
      // 请求配置出错
      console.error('Request Error:', error.message);
    }
    
    return Promise.reject(error);
  }
);

// API方法封装
export const api = {
  // GET请求
  get: <T = any>(url: string, config?: AxiosRequestConfig): Promise<T> => {
    return apiClient.get(url, config);
  },

  // POST请求
  post: <T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> => {
    return apiClient.post(url, data, config);
  },

  // PUT请求
  put: <T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> => {
    return apiClient.put(url, data, config);
  },

  // PATCH请求
  patch: <T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> => {
    return apiClient.patch(url, data, config);
  },

  // DELETE请求
  delete: <T = any>(url: string, config?: AxiosRequestConfig): Promise<T> => {
    return apiClient.delete(url, config);
  },
};

// QuestionOS 专用API
export const questionApi = {
  // 创建问题会话
  createSession: (initialQuestion: string) => {
    return api.post('/sessions', { question: initialQuestion });
  },

  // 获取会话详情
  getSession: (sessionId: string) => {
    return api.get(`/sessions/${sessionId}`);
  },

  // 发送消息
  sendMessage: (sessionId: string, message: string) => {
    return api.post(`/sessions/${sessionId}/messages`, { content: message });
  },

  // 获取会话消息列表
  getMessages: (sessionId: string) => {
    return api.get(`/sessions/${sessionId}/messages`);
  },

  // 完成校准，生成最终输出
  finalizeSession: (sessionId: string) => {
    return api.post(`/sessions/${sessionId}/finalize`);
  },
};

export default apiClient;
