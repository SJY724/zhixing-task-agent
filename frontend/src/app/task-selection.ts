export function selectedVisible<T extends { taskId: string }>(visible: readonly T[], selected: ReadonlySet<string>): T[] {
    return visible.filter((task) => selected.has(task.taskId));
}

/** Each request keeps the existing server-side guards and transaction. Failures remain retryable. */
export async function deleteSelected<T>(targets: readonly T[], remove: (target: T) => Promise<unknown>, progress: (done: number) => void) {
    const succeeded: T[] = [];
    const failed: { target: T; error: unknown }[] = [];
    for (const target of [...targets]) {
        try { await remove(target); succeeded.push(target); }
        catch (error) { failed.push({ target, error }); }
        progress(succeeded.length + failed.length);
    }
    return { succeeded, failed };
}
