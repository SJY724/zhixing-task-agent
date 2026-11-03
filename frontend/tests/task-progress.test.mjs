import test from 'node:test';
import assert from 'node:assert/strict';
import { runningMessage } from '../src/app/task-progress.ts';

const task = { status: 'RUNNING', context: '制度依据', confirmComment: '同意生成' };

test('approved generation phases never imply that approval is still required', () => {
    const queued = runningMessage({ ...task, phase: 'GENERATION_QUEUED' });
    assert.match(queued.title, /审批已通过/);
    assert.match(queued.description, /无需再次审批/);
    const generating = runningMessage({ ...task, phase: 'GENERATE' });
    assert.match(generating.title, /生成回答/);
    assert.doesNotMatch(generating.description, /决定是否生成/);
});

test('phase-aware retries and snapshots without a phase show the correct activity', () => {
    assert.match(
        runningMessage({ ...task, status: 'RETRYING', phase: 'GENERATE' }).title,
        /重试回答生成/,
    );
    assert.match(runningMessage({ ...task, phase: undefined }).title, /生成回答/);
    assert.match(
        runningMessage({ status: 'RUNNING', phase: 'PARSE', context: null, confirmComment: null })
            .title,
        /解析/,
    );
    assert.match(
        runningMessage({
            status: 'RUNNING',
            phase: 'RETRIEVE',
            context: null,
            confirmComment: null,
        }).title,
        /寻找依据/,
    );
});
