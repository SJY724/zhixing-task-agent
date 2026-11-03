import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom, timeout, Observable } from 'rxjs';
import { ApiEnvelope, KnowledgeDocument, Metrics, SystemInfo, Task, TaskLog } from './models';

/** All requests stay on the local service origin. Model credentials never enter the browser. */
@Injectable({ providedIn: 'root' })
export class WorkbenchApi {
    private readonly http = inject(HttpClient);
    private readonly base = '/api/agent';
    private async unwrap<T>(request: Observable<ApiEnvelope<T>>, limit = 20000): Promise<T> {
        try {
            const response = await firstValueFrom<ApiEnvelope<T>>(request.pipe(timeout(limit)));
            if (response.code !== 200) throw new Error(response.message || '请求未完成');
            return response.data;
        } catch (error) {
            if (error instanceof HttpErrorResponse) {
                if (error.status === 0)
                    throw new Error('暂时无法连接后端，请确认“知行”服务正在运行。');
                const message = error.error?.message;
                throw new Error(message || `请求失败（HTTP ${error.status}），请稍后重试。`);
            }
            if (error instanceof Error && error.name === 'TimeoutError')
                throw new Error('请求等待超时，请刷新查看最新状态，避免重复提交。');
            throw error;
        }
    }
    tasks() {
        return this.unwrap<Task[]>(this.http.get<ApiEnvelope<Task[]>>(`${this.base}/tasks`));
    }
    task(id: string) {
        return this.unwrap<Task>(
            this.http.get<ApiEnvelope<Task>>(`${this.base}/tasks/${encodeURIComponent(id)}`),
        );
    }
    deletedTasks() {
        return this.unwrap<Task[]>(
            this.http.get<ApiEnvelope<Task[]>>(`${this.base}/tasks/deleted`),
        );
    }
    deleteTask(id: string) {
        return this.unwrap<{ deleted: boolean; recoverable: boolean }>(
            this.http.delete<ApiEnvelope<{ deleted: boolean; recoverable: boolean }>>(
                `${this.base}/tasks/${encodeURIComponent(id)}`,
            ),
        );
    }
    restoreTask(id: string) {
        return this.unwrap<Task>(
            this.http.post<ApiEnvelope<Task>>(
                `${this.base}/tasks/${encodeURIComponent(id)}/restore`,
                {},
            ),
        );
    }
    permanentlyDeleteTask(id: string) {
        return this.unwrap<{ deleted: boolean; recoverable: boolean }>(
            this.http.delete<ApiEnvelope<{ deleted: boolean; recoverable: boolean }>>(
                `${this.base}/tasks/${encodeURIComponent(id)}/permanent`,
            ),
        );
    }
    system() {
        return this.unwrap<SystemInfo>(
            this.http.get<ApiEnvelope<SystemInfo>>(`${this.base}/system`),
        );
    }
    metrics() {
        return this.unwrap<Metrics>(this.http.get<ApiEnvelope<Metrics>>(`${this.base}/metrics`));
    }
    knowledge() {
        return this.unwrap<KnowledgeDocument[]>(
            this.http.get<ApiEnvelope<KnowledgeDocument[]>>(`${this.base}/knowledge`),
        );
    }
    create(body: { question: string; textContent?: string; documentIds?: string[] }) {
        return this.unwrap<Task>(this.http.post<ApiEnvelope<Task>>(`${this.base}/tasks`, body));
    }
    confirm(id: string, approved: boolean, comment: string) {
        return this.unwrap<Task>(
            this.http.post<ApiEnvelope<Task>>(
                `${this.base}/tasks/${encodeURIComponent(id)}/confirm`,
                { approved, comment },
            ),
        );
    }
    retry(id: string) {
        return this.unwrap<Task>(
            this.http.post<ApiEnvelope<Task>>(
                `${this.base}/tasks/${encodeURIComponent(id)}/retry`,
                {},
            ),
        );
    }
    cancel(id: string) {
        return this.unwrap<Task>(
            this.http.post<ApiEnvelope<Task>>(
                `${this.base}/tasks/${encodeURIComponent(id)}/cancel`,
                {},
            ),
        );
    }
    logs(id: string) {
        return this.unwrap<TaskLog[]>(
            this.http.get<ApiEnvelope<TaskLog[]>>(
                `${this.base}/tasks/${encodeURIComponent(id)}/logs`,
            ),
        );
    }
    upload(file: File) {
        const body = new FormData();
        body.append('file', file);
        return this.unwrap<KnowledgeDocument>(
            this.http.post<ApiEnvelope<KnowledgeDocument>>(`${this.base}/knowledge/upload`, body),
            120000,
        );
    }
    deleteDocument(id: string) {
        return this.unwrap<unknown>(
            this.http.delete<ApiEnvelope<unknown>>(
                `${this.base}/knowledge/${encodeURIComponent(id)}`,
            ),
        );
    }
    rebuildKnowledge() {
        return this.unwrap<KnowledgeDocument[]>(
            this.http.post<ApiEnvelope<KnowledgeDocument[]>>(`${this.base}/knowledge/rebuild`, {}),
            120000,
        );
    }
}
