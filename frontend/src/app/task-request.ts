export interface CreateTaskRequest {
    question: string;
    textContent?: string;
    documentIds?: string[];
}

/** The backend accepts a local text scope OR selected documents, never both. */
export function buildTaskRequest(
    question: string,
    material: string,
    documentIds: readonly string[],
): CreateTaskRequest {
    const request: CreateTaskRequest = { question: question.trim() };
    if (material.trim()) request.textContent = material.trim();
    else if (documentIds.length) request.documentIds = [...new Set(documentIds)];
    return request;
}
