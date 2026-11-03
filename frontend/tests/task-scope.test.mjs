import test from 'node:test';
import assert from 'node:assert/strict';
import { buildTaskRequest } from '../src/app/task-request.ts';

test('task-local material never sends an incompatible document scope', () => {
    const body = buildTaskRequest(' 问题 ', ' 当前任务材料 ', ['shared-doc']);
    assert.deepEqual(body, { question: '问题', textContent: '当前任务材料' });
    assert.equal('documentIds' in body, false);
});

test('selected document scope remains available when local material is empty', () => {
    assert.deepEqual(buildTaskRequest('问题', '  ', ['a', 'b', 'a']), {
        question: '问题',
        documentIds: ['a', 'b'],
    });
});

test('empty scope delegates all available knowledge selection to the backend', () => {
    assert.deepEqual(buildTaskRequest('问题', '', []), { question: '问题' });
});
