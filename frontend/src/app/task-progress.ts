import type { Task } from './models';

type ProgressTask = Pick<Task, 'phase' | 'status' | 'context' | 'confirmComment'>;

/** Prefer the backend phase; older persisted tasks may only contain context and a decision. */
export function runningMessage(task: ProgressTask): { title: string; description: string } {
    const phase = task.phase?.toUpperCase() || '';
    const generating =
        phase.includes('GENERAT') || (!phase && (task.context !== null || !!task.confirmComment));
    if (task.status === 'RETRYING') {
        return {
            title: generating
                ? '正在重试回答生成'
                : phase === 'PARSE'
                  ? '正在重试文档解析'
                  : '正在重试知识检索',
            description: '遇到暂时性错误，系统会按重试策略继续。你可以在执行记录中查看进度。',
        };
    }
    if (phase === 'GENERATION_QUEUED') {
        return {
            title: '审批已通过，回答生成已排队',
            description: '正在等待生成执行。无需再次审批，状态会自动同步。',
        };
    }
    if (generating) {
        return {
            title: '正在根据依据生成回答',
            description: '人工审批已通过。完成后，最终回答与引用来源会显示在下方。',
        };
    }
    if (phase === 'PARSE') {
        return {
            title: '正在解析任务材料',
            description: '正在准备文档内容与知识分块，接下来会检索与问题相关的依据。',
        };
    }
    if (task.status === 'PENDING' || phase === 'QUEUED' || phase === 'SUBMIT') {
        return {
            title: '任务已创建，等待执行',
            description: '任务进入处理队列后会自动开始解析与检索。',
        };
    }
    return {
        title: '正在为问题寻找依据',
        description: '状态会自动同步。检索完成后，你可以查看依据并决定是否生成。',
    };
}
