// 会话相关类型
export interface Session {
  id: string;
  initialQuestion: string;
  status: 'active' | 'completed' | 'archived';
  createdAt: string;
  updatedAt: string;
  finalizedOutput?: FinalizedOutput;
}

export interface Message {
  id: string;
  sessionId: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: string;
}

export interface FinalizedOutput {
  clearGoal: string;
  actionableSteps: string[];
  constraints: string[];
  successCriteria: string[];
  resources: string[];
}

// API响应类型
export interface ApiResponse<T = any> {
  success: boolean;
  data: T;
  message?: string;
}

export interface ApiError {
  success: false;
  error: string;
  code?: string;
}

// 用户输入类型
export interface QuestionInput {
  question: string;
  context?: string;
  expectedOutcome?: string;
}

// 校准进度类型
export interface CalibrationProgress {
  stage: 'clarifying' | 'structuring' | 'finalizing';
  currentStep: number;
  totalSteps: number;
  questionsAsked: number;
  clarity: number; // 0-100
}
