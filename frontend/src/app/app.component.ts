import {
    Component,
    ChangeDetectionStrategy,
    computed,
    inject,
    signal,
    OnDestroy,
    OnInit,
    HostListener,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { WorkbenchApi } from './workbench-api.service';
import { IconComponent } from './icon.component';
import { SafeMarkdownPipe } from './safe-markdown.pipe';
import { KnowledgeDocument, Metrics, SystemInfo, Task, TaskLog, TaskStatus } from './models';
import { buildTaskRequest } from './task-request';
import { runningMessage } from './task-progress';
import { selectedVisible, deleteSelected } from './task-selection';

type View = 'overview' | 'tasks' | 'knowledge' | 'system';
const terminal = new Set<TaskStatus>(['SUCCEEDED', 'FAILED', 'CANCELLED']);
const statuses: Record<TaskStatus, string> = {
    PENDING: '等待执行',
    RUNNING: '正在处理',
    RETRYING: '正在重试',
    AWAITING_CONFIRMATION: '等待审批',
    SUCCEEDED: '已完成',
    FAILED: '执行失败',
    CANCELLED: '已取消',
};

@Component({
    selector: 'app-root',
    standalone: true,
    imports: [CommonModule, FormsModule, IconComponent, SafeMarkdownPipe],
    templateUrl: './app.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent implements OnInit, OnDestroy {
    readonly runningMessage = runningMessage;
    private readonly api = inject(WorkbenchApi);
    readonly view = signal<View>('overview');
    readonly tasks = signal<Task[]>([]);
    readonly deletedTasks = signal<Task[]>([]);
    readonly showDeletedTasks = signal(false);
    readonly taskDeleteCandidate = signal<Task | null>(null);
    readonly taskPermanentDelete = signal(false);
    readonly taskDeleteBusy = signal(false);
    readonly checkedTaskIds = signal<ReadonlySet<string>>(new Set());
    readonly taskDeleteTargets = signal<Task[]>([]);
    readonly taskDeleteProgress = signal(0);
    readonly selectableTasks = computed(() => this.visibleTasks().filter((task) => this.isTerminal(task)));
    readonly checkedTasks = computed(() => selectedVisible(this.selectableTasks(), this.checkedTaskIds()));
    readonly allTasksChecked = computed(() => this.selectableTasks().length > 0 && this.checkedTasks().length === this.selectableTasks().length);
    readonly deletedTasksError = signal('');
    readonly documents = signal<KnowledgeDocument[]>([]);
    readonly system = signal<SystemInfo | null>(null);
    readonly metrics = signal<Metrics>({
        total: 0,
        awaiting: 0,
        succeeded: 0,
        failed: 0,
        cancelled: 0,
    });
    readonly connected = signal(false);
    readonly loading = signal(true);
    readonly refreshing = signal(false);
    readonly lastSync = signal<Date | null>(null);
    readonly error = signal('');
    readonly knowledgeError = signal('');
    readonly systemError = signal('');
    readonly toast = signal<{ message: string; kind: 'success' | 'error' | 'info' } | null>(null);
    readonly mobileNav = signal(false);
    readonly selectedId = signal<string | null>(null);
    readonly selected = computed(
        () => this.tasks().find((t) => t.taskId === this.selectedId()) || null,
    );
    readonly detailTab = signal<'result' | 'activity' | 'logs'>('result');
    readonly logs = signal<TaskLog[]>([]);
    readonly logsLoading = signal(false);
    readonly logsError = signal('');
    readonly composeOpen = signal(false);
    readonly submitBusy = signal(false);
    readonly actionBusy = signal(false);
    readonly uploadBusy = signal(false);
    readonly rebuildBusy = signal(false);
    readonly rebuildConfirmOpen = signal(false);
    readonly activeCount = computed(
        () => this.tasks().filter((t) => !terminal.has(t.status)).length,
    );
    readonly awaiting = computed(() =>
        this.tasks().filter((t) => t.status === 'AWAITING_CONFIRMATION'),
    );
    readonly visibleTasks = computed(() => {
        const search = this.search().trim().toLowerCase();
        return (this.showDeletedTasks() ? this.deletedTasks() : this.tasks()).filter(
            (task) =>
                (this.filter() === 'ALL' || task.status === this.filter()) &&
                (!search ||
                    task.question.toLowerCase().includes(search) ||
                    task.taskId.toLowerCase().includes(search)),
        );
    });
    readonly recentTasks = computed(() => this.tasks().slice(0, 5));
    readonly readyDocuments = computed(() => this.documents().filter((d) => this.documentReady(d)));
    readonly needsRebuild = computed(
        () =>
            this.documents().some((d) => !this.documentReady(d)) || !!this.system()?.knowledgeError,
    );
    readonly search = signal('');
    readonly filter = signal('ALL');
    readonly deleteCandidate = signal<KnowledgeDocument | null>(null);
    readonly deleteBusy = signal(false);
    question = '';
    material = '';
    approvalComment = '';
    selectedDocumentIds: string[] = [];
    private poll?: ReturnType<typeof setInterval>;
    private toastTimer?: ReturnType<typeof setTimeout>;
    private tick = 0;
    private destroyed = false;
    private logsPending = false;
    private revision = 0;
    readonly statusOptions = Object.entries(statuses).map(([value, label]) => ({ value, label }));
    readonly pageTitles: Record<View, string> = {
        overview: '工作概览',
        tasks: '任务中心',
        knowledge: '知识空间',
        system: '系统状态',
    };
    readonly presets = [
        {
            label: '差旅住宿标准',
            question: '一线城市差旅住宿限额是多少？请注明制度来源。',
            icon: 'book',
        },
        {
            label: '紧急采购流程',
            question: '紧急采购可以走什么流程，需要多久补齐材料？',
            icon: 'layers',
        },
        {
            label: '检验回答边界',
            question: '火星移民基地的氧气配给限额是多少？请提供制度依据。',
            icon: 'shield',
        },
    ];

    ngOnInit() {
        void this.refresh(true);
        this.poll = setInterval(() => {
            if (
                document.hidden ||
                this.refreshing() ||
                this.destroyed ||
                this.submitBusy() ||
                this.actionBusy() ||
                this.uploadBusy() ||
                this.rebuildBusy() ||
                this.deleteBusy() ||
                this.taskDeleteBusy()
            )
                return;
            void this.refresh(++this.tick % 4 === 0);
        }, 4000);
    }
    ngOnDestroy() {
        this.destroyed = true;
        if (this.poll) clearInterval(this.poll);
        if (this.toastTimer) clearTimeout(this.toastTimer);
    }
    @HostListener('document:keydown.escape') escape() {
        if (this.taskDeleteCandidate()) {
            if (!this.taskDeleteBusy()) this.taskDeleteCandidate.set(null);
        } else if (this.deleteCandidate()) this.deleteCandidate.set(null);
        else if (this.rebuildConfirmOpen()) this.rebuildConfirmOpen.set(false);
        else if (this.composeOpen() && !this.submitBusy()) this.composeOpen.set(false);
        else this.closeDetail();
        this.mobileNav.set(false);
    }
    @HostListener('document:keydown', ['$event']) trapFocus(event: KeyboardEvent) {
        if (event.key !== 'Tab') return;
        const selector =
            this.deleteCandidate() || this.taskDeleteCandidate() || this.rebuildConfirmOpen()
                ? '.confirmation-dialog'
                : this.composeOpen()
                  ? '.compose-dialog'
                  : this.selected()
                    ? '.detail-drawer'
                    : null;
        if (!selector) return;
        const dialog = document.querySelector(selector);
        const targets = Array.from(
            dialog?.querySelectorAll<HTMLElement>(
                'button:not([disabled]),input:not([disabled]),textarea:not([disabled]),select:not([disabled]),summary,[tabindex="0"]',
            ) || [],
        ).filter((el) => el.getClientRects().length > 0);
        if (!targets.length) return;
        const first = targets[0],
            last = targets[targets.length - 1];
        if (
            event.shiftKey &&
            (document.activeElement === first || !dialog?.contains(document.activeElement))
        ) {
            event.preventDefault();
            last.focus();
        } else if (
            !event.shiftKey &&
            (document.activeElement === last || !dialog?.contains(document.activeElement))
        ) {
            event.preventDefault();
            first.focus();
        }
    }
    async refresh(full = false) {
        if (this.refreshing()) return;
        full = full || this.showDeletedTasks();
        this.refreshing.set(true);
        const revision = this.revision;
        const requests: Promise<unknown>[] = [this.api.tasks()];
        if (full || !this.system())
            requests.push(
                this.api.system(),
                this.api.knowledge(),
                this.api.metrics(),
                this.api.deletedTasks(),
            );
        const results = await Promise.allSettled(requests);
        if (this.destroyed) return;
        if (revision !== this.revision) {
            this.loading.set(false);
            this.refreshing.set(false);
            return;
        }
        const taskResult = results[0];
        if (taskResult.status === 'fulfilled') {
            this.tasks.set(taskResult.value as Task[]);
            this.connected.set(true);
            this.error.set('');
            this.lastSync.set(new Date());
            if (this.selectedId() && !this.selected()) this.closeDetail();
            if (this.selected() && this.detailTab() === 'logs') void this.loadLogs(false);
        } else {
            this.connected.set(false);
            this.error.set(this.errorText(taskResult.reason));
        }
        if (results[1]?.status === 'fulfilled') {
            this.system.set(results[1].value as SystemInfo);
            this.systemError.set('');
        } else if (results[1]?.status === 'rejected')
            this.systemError.set(this.errorText(results[1].reason));
        if (results[2]?.status === 'fulfilled') {
            this.documents.set(results[2].value as KnowledgeDocument[]);
            this.knowledgeError.set('');
        } else if (results[2]?.status === 'rejected')
            this.knowledgeError.set(this.errorText(results[2].reason));
        if (results[3]?.status === 'fulfilled') this.metrics.set(results[3].value as Metrics);
        if (results[4]?.status === 'fulfilled') {
            this.deletedTasks.set(results[4].value as Task[]);
            this.deletedTasksError.set('');
        } else if (results[4]?.status === 'rejected') {
            this.deletedTasksError.set(this.errorText(results[4].reason));
        }
        // Counts are derived from the current persisted task list, never fabricated placeholders.
        if (results[3]?.status !== 'fulfilled') {
            const tasks = this.tasks();
            this.metrics.set({
                total: tasks.length,
                awaiting: tasks.filter((t) => t.status === 'AWAITING_CONFIRMATION').length,
                succeeded: tasks.filter((t) => t.status === 'SUCCEEDED').length,
                failed: tasks.filter((t) => t.status === 'FAILED').length,
                cancelled: tasks.filter((t) => t.status === 'CANCELLED').length,
            });
        }
        this.loading.set(false);
        this.refreshing.set(false);
    }
    navigate(view: View) {
        this.checkedTaskIds.set(new Set());
        this.view.set(view);
        this.showDeletedTasks.set(false);
        this.mobileNav.set(false);
        this.closeDetail();
        if (view === 'knowledge' || view === 'system') void this.refresh(true);
    }
    openCompose(preset?: string) {
        this.question = preset || '';
        this.material = '';
        this.selectedDocumentIds = [];
        this.composeOpen.set(true);
        this.mobileNav.set(false);
        setTimeout(() => document.getElementById('task-question')?.focus(), 0);
    }
    closeCompose() {
        if (!this.submitBusy()) this.composeOpen.set(false);
    }
    toggleDocument(id: string) {
        if (this.material.trim()) return;
        this.selectedDocumentIds = this.selectedDocumentIds.includes(id)
            ? this.selectedDocumentIds.filter((x) => x !== id)
            : [...this.selectedDocumentIds, id];
    }
    setMaterial(value: string) {
        this.material = value;
        if (value.trim() && this.selectedDocumentIds.length) {
            this.selectedDocumentIds = [];
            this.notify('已切换为当前任务补充文本，不再检索选定文档。', 'info');
        }
    }
    async submit() {
        if (this.submitBusy()) return;
        const question = this.question.trim();
        if (!question) {
            this.notify('请先输入你想了解的问题。', 'error');
            return;
        }
        if (!this.connected()) {
            this.notify('后端连接已中断，请恢复服务后再提交。', 'error');
            return;
        }
        this.submitBusy.set(true);
        this.revision++;
        try {
            const body = buildTaskRequest(question, this.material, this.selectedDocumentIds);
            const task = await this.api.create(body);
            this.upsert(task);
            this.composeOpen.set(false);
            this.view.set('tasks');
            this.openTask(task);
            this.notify('任务已创建。检索完成后，请核对依据并审批。', 'success');
            void this.refresh(true);
        } catch (e) {
            this.notify(this.errorText(e), 'error');
        } finally {
            this.submitBusy.set(false);
        }
    }
    openTask(task: Task) {
        if (this.showDeletedTasks()) return;
        this.selectedId.set(task.taskId);
        this.detailTab.set('result');
        this.approvalComment = '';
        this.logs.set([]);
        this.logsError.set('');
        setTimeout(
            () =>
                document
                    .querySelector<HTMLButtonElement>('.detail-header-actions button:last-child')
                    ?.focus(),
            0,
        );
    }
    closeDetail() {
        if (!this.actionBusy()) this.selectedId.set(null);
    }
    async approve(approved: boolean) {
        const task = this.selected();
        if (!task || this.actionBusy() || task.status !== 'AWAITING_CONFIRMATION') return;
        this.actionBusy.set(true);
        this.revision++;
        try {
            const response = await this.api.confirm(
                task.taskId,
                approved,
                this.approvalComment.trim(),
            );
            this.upsert(response);
            this.notify(
                approved ? '审批已通过，正在生成回答。' : '已拒绝，本次任务已取消。',
                'success',
            );
            void this.refresh(true);
        } catch (e) {
            this.notify(this.errorText(e), 'error');
            void this.refresh();
        } finally {
            this.actionBusy.set(false);
        }
    }
    async retry() {
        const task = this.selected();
        if (!task || this.actionBusy()) return;
        this.actionBusy.set(true);
        this.revision++;
        try {
            const next = await this.api.retry(task.taskId);
            this.upsert(next);
            this.openTask(next);
            this.notify('已创建新的重试任务，原任务记录保留。', 'success');
            void this.refresh(true);
        } catch (e) {
            this.notify(this.errorText(e), 'error');
        } finally {
            this.actionBusy.set(false);
        }
    }
    async cancel() {
        const task = this.selected();
        if (!task || this.actionBusy()) return;
        this.actionBusy.set(true);
        this.revision++;
        try {
            this.upsert(await this.api.cancel(task.taskId));
            this.notify('取消请求已处理。', 'success');
            void this.refresh(true);
        } catch (e) {
            this.notify(this.errorText(e), 'error');
        } finally {
            this.actionBusy.set(false);
        }
    }
    async loadLogs(showSpinner = true) {
        const id = this.selectedId();
        if (!id || this.logsPending) return;
        this.logsPending = true;
        if (showSpinner) this.logsLoading.set(true);
        try {
            const logs = await this.api.logs(id);
            if (id === this.selectedId()) {
                this.logs.set(logs);
                this.logsError.set('');
            }
        } catch (e) {
            if (id === this.selectedId()) this.logsError.set(this.errorText(e));
        } finally {
            this.logsLoading.set(false);
            this.logsPending = false;
        }
    }
    changeDetailTab(tab: 'result' | 'activity' | 'logs') {
        this.detailTab.set(tab);
        if (tab === 'logs') void this.loadLogs();
    }
    async upload(event: Event) {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        if (!file || this.uploadBusy()) return;
        if (file.size > 10 * 1024 * 1024) {
            this.notify('请选择不超过 10 MB 的文档。', 'error');
            input.value = '';
            return;
        }
        if (!/\.(md|txt|pdf|docx)$/i.test(file.name)) {
            this.notify('支持 Markdown、TXT、PDF、DOCX 文档。', 'error');
            input.value = '';
            return;
        }
        this.uploadBusy.set(true);
        this.revision++;
        try {
            const uploaded = await this.api.upload(file);
            if (this.documentFailed(uploaded)) {
                this.notify(
                    uploaded.errorMessage ||
                        '文档已保存，但知识索引失败。请修复模型配置后重建索引。',
                    'error',
                );
            } else {
                this.notify('文档已接收，请查看下方的解析与索引状态。', 'success');
            }
            await this.refresh(true);
        } catch (e) {
            this.notify(this.errorText(e), 'error');
        } finally {
            this.uploadBusy.set(false);
            input.value = '';
        }
    }
    async toggleDeletedTasks() {
        if (this.taskDeleteBusy()) return;
        this.checkedTaskIds.set(new Set());
        this.showDeletedTasks.update((value) => !value);
        this.search.set('');
        this.filter.set('ALL');
        this.closeDetail();
        await this.refresh(true);
    }
    requestTaskDeletion(task: Task, permanent = false) {
        if (!this.isTerminal(task) || this.taskDeleteBusy()) return;
        if (permanent && !this.showDeletedTasks()) return;
        this.taskPermanentDelete.set(permanent);
        this.taskDeleteTargets.set([task]);
        this.taskDeleteCandidate.set(task);
        setTimeout(
            () => document.querySelector<HTMLButtonElement>('.task-delete-dialog button')?.focus(),
            0,
        );
    }
    toggleTaskChecked(task: Task) {
        if (this.taskDeleteBusy() || !this.isTerminal(task)) return;
        const ids = new Set(this.checkedTaskIds());
        if (ids.has(task.taskId)) ids.delete(task.taskId); else ids.add(task.taskId);
        this.checkedTaskIds.set(ids);
    }
    toggleAllTasks() {
        if (this.taskDeleteBusy()) return;
        this.checkedTaskIds.set(this.allTasksChecked() ? new Set() : new Set(this.selectableTasks().map((task) => task.taskId)));
    }
    requestSelectedDeletion() {
        const targets = this.checkedTasks();
        if (!targets.length || this.taskDeleteBusy()) return;
        this.requestTaskDeletion(targets[0], this.showDeletedTasks());
        this.taskDeleteTargets.set([...targets]);
    }
    async deleteTask() {
        const targets = [...this.taskDeleteTargets()];
        if (!targets.length || this.taskDeleteBusy() || targets.some((task) => !this.isTerminal(task))) return;
        this.taskDeleteBusy.set(true);
        this.taskDeleteProgress.set(0);
        this.revision++;
        try {
            const permanent = this.taskPermanentDelete();
            const result = await deleteSelected(targets, (task) => permanent ? this.api.permanentlyDeleteTask(task.taskId) : this.api.deleteTask(task.taskId), (done) => this.taskDeleteProgress.set(done));
            const removed = new Set(result.succeeded.map((task) => task.taskId));
            this.tasks.update((tasks) => tasks.filter((item) => !removed.has(item.taskId)));
            this.deletedTasks.update((tasks) =>
                permanent
                    ? tasks.filter((item) => !removed.has(item.taskId))
                    : [...result.succeeded, ...tasks.filter((item) => !removed.has(item.taskId))],
            );
            if (removed.has(this.selectedId() || '')) this.selectedId.set(null);
            this.checkedTaskIds.set(new Set(result.failed.map((item) => item.target.taskId)));
            this.taskDeleteCandidate.set(null);
            this.notify(
                result.failed.length
                    ? `已删除 ${result.succeeded.length} 条，${result.failed.length} 条未完成，请刷新核对后再操作。${this.errorText(result.failed[0].error)}`
                    : permanent ? `已永久删除 ${removed.size} 条任务，无法恢复。` : `已将 ${removed.size} 条任务移入最近删除，可随时恢复。`,
                result.failed.length ? 'error' : 'success',
            );
            await this.refresh(true);
        } catch (error) {
            this.notify(this.errorText(error), 'error');
        } finally {
            this.taskDeleteBusy.set(false);
        }
    }
    async restoreTask(task: Task) {
        if (this.taskDeleteBusy()) return;
        this.taskDeleteBusy.set(true);
        this.revision++;
        try {
            const restored = await this.api.restoreTask(task.taskId);
            this.upsert(restored);
            this.deletedTasks.update((tasks) =>
                tasks.filter((item) => item.taskId !== task.taskId),
            );
            this.notify('任务已恢复，原有回答与执行记录仍然保留。', 'success');
            await this.refresh(true);
        } catch (error) {
            this.notify(this.errorText(error), 'error');
        } finally {
            this.taskDeleteBusy.set(false);
        }
    }
    async deleteDocument() {
        const item = this.deleteCandidate();
        if (!item || this.deleteBusy()) return;
        this.deleteBusy.set(true);
        this.revision++;
        try {
            await this.api.deleteDocument(item.id);
            this.deleteCandidate.set(null);
            this.notify('文档已移出知识空间。历史回答保留原有快照。', 'success');
            await this.refresh(true);
        } catch (e) {
            this.notify(this.errorText(e), 'error');
        } finally {
            this.deleteBusy.set(false);
        }
    }
    async rebuildKnowledge() {
        if (this.rebuildBusy() || this.uploadBusy()) return;
        this.rebuildConfirmOpen.set(false);
        this.rebuildBusy.set(true);
        this.revision++;
        try {
            const rebuilt = await this.api.rebuildKnowledge();
            this.documents.set(rebuilt);
            const failed = rebuilt.find((doc) => this.documentFailed(doc));
            if (failed) {
                this.notify(
                    failed.errorMessage || '仍有文档索引失败，请检查模型配置与网络。',
                    'error',
                );
            } else {
                this.notify('知识索引已重建，可以重新提交检索任务。', 'success');
            }
            await this.refresh(true);
        } catch (error) {
            this.notify(this.errorText(error), 'error');
        } finally {
            this.rebuildBusy.set(false);
        }
    }
    async copy(value: string | null | undefined, label = '内容') {
        if (!value) return;
        try {
            await navigator.clipboard.writeText(value);
            this.notify(`${label}已复制。`, 'success');
        } catch {
            this.notify('复制未完成，请手动选择文字复制。', 'error');
        }
    }
    notify(message: string, kind: 'success' | 'error' | 'info' = 'info') {
        this.toast.set({ message, kind });
        if (this.toastTimer) clearTimeout(this.toastTimer);
        this.toastTimer = setTimeout(() => this.toast.set(null), 6500);
    }
    statusLabel(status: TaskStatus) {
        return statuses[status] || status;
    }
    statusClass(status: TaskStatus) {
        return status.toLowerCase();
    }
    statusIcon(status: TaskStatus) {
        return status === 'SUCCEEDED'
            ? 'check'
            : status === 'AWAITING_CONFIRMATION'
              ? 'shield'
              : status === 'FAILED'
                ? 'warning'
                : status === 'CANCELLED'
                  ? 'stop'
                  : 'clock';
    }
    isTerminal(task: Task) {
        return terminal.has(task.status);
    }
    documentReady(doc: KnowledgeDocument) {
        return ['READY', 'INDEXED', 'SUCCEEDED', 'ACTIVE'].includes(doc.status?.toUpperCase());
    }
    documentFailed(doc: KnowledgeDocument) {
        return ['FAILED', 'ERROR'].includes(doc.status?.toUpperCase());
    }
    documentStatus(doc: KnowledgeDocument) {
        return this.documentReady(doc)
            ? '可检索'
            : ['FAILED', 'ERROR'].includes(doc.status?.toUpperCase())
              ? '索引失败'
              : '正在处理';
    }
    date(value: string | undefined, short = false) {
        if (!value) return '—';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return value;
        return new Intl.DateTimeFormat(
            'zh-CN',
            short
                ? {
                      month: '2-digit',
                      day: '2-digit',
                      hour: '2-digit',
                      minute: '2-digit',
                      hour12: false,
                  }
                : {
                      year: 'numeric',
                      month: '2-digit',
                      day: '2-digit',
                      hour: '2-digit',
                      minute: '2-digit',
                      second: '2-digit',
                      hour12: false,
                  },
        ).format(date);
    }
    time(value: string) {
        const date = new Date(value);
        return Number.isNaN(date.getTime())
            ? ''
            : new Intl.DateTimeFormat('zh-CN', {
                  hour: '2-digit',
                  minute: '2-digit',
                  second: '2-digit',
                  hour12: false,
              }).format(date);
    }
    stepMessage(step: string) {
        const split = step.indexOf(' | ');
        return split < 0 ? step : step.slice(split + 3);
    }
    stepTime(step: string) {
        const split = step.indexOf(' | ');
        return split < 0 ? '' : this.time(step.slice(0, split));
    }
    progress(task: Task) {
        if (task.status === 'SUCCEEDED') return 4;
        if (task.status === 'AWAITING_CONFIRMATION') return 2;
        if (task.context !== null) return 3;
        return 1;
    }
    scopeLabel(task: Task) {
        if (task.textContent?.trim()) return '当前任务补充文本';
        return task.documentIds?.length ? `指定 ${task.documentIds.length} 份文档` : '全部可用知识';
    }
    private errorText(error: unknown) {
        return error instanceof Error ? error.message : '操作未完成，请稍后重试。';
    }
    private upsert(task: Task) {
        this.tasks.update((tasks) =>
            [task, ...tasks.filter((t) => t.taskId !== task.taskId)].sort((a, b) =>
                b.createdAt.localeCompare(a.createdAt),
            ),
        );
    }
}
