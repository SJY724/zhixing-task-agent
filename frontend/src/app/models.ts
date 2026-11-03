export type TaskStatus =
    | 'PENDING'
    | 'RUNNING'
    | 'RETRYING'
    | 'AWAITING_CONFIRMATION'
    | 'SUCCEEDED'
    | 'FAILED'
    | 'CANCELLED';
export interface Task {
    taskId: string;
    question: string;
    status: TaskStatus;
    createdAt: string;
    updatedAt: string;
    context: string | null;
    answer: string | null;
    errorMessage: string | null;
    confirmComment: string | null;
    steps: string[];
    traceId?: string;
    parentTaskId?: string;
    documentIds?: string[];
    textContent?: string | null;
    phase?: string;
}
export interface KnowledgeDocument {
    id: string;
    name: string;
    chunkCount: number;
    createdAt: string;
    status: string;
    errorMessage?: string;
}
export interface SystemInfo {
    mode: string;
    chatModel: string;
    embeddingModel: string;
    knowledgeReady: boolean;
    documentCount: number;
    storage: string;
    confirmationRequired: boolean;
    apiKeyConfigured?: boolean;
    knowledgeError?: string | null;
}
export interface Metrics {
    total: number;
    awaiting: number;
    succeeded: number;
    failed: number;
    cancelled: number;
}
export interface TaskLog {
    timestamp: string;
    taskId: string;
    traceId: string;
    stage: string;
    message: string;
}
export interface ApiEnvelope<T> {
    code: number;
    message: string;
    data: T;
    traceId?: string;
    timestamp?: string;
}
