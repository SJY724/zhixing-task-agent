import test from 'node:test';
import assert from 'node:assert/strict';
import { selectedVisible, deleteSelected } from '../src/app/task-selection.ts';

test('a filtered batch excludes hidden selections and missing records', () => {
    assert.deepEqual(selectedVisible([{ taskId: 'visible' }], new Set(['visible', 'hidden', 'removed'])), [{ taskId: 'visible' }]);
    assert.deepEqual(selectedVisible([], new Set(['hidden'])), []);
});

test('a failed deletion preserves its error and does not prevent other selected tasks', async () => {
    const called = [], progress = [], error = new Error('still stopping');
    const targets = ['a', 'b', 'c'];
    const result = await deleteSelected(targets, async (id) => { called.push(id); if (id === 'b') throw error; }, (done) => progress.push(done));
    assert.deepEqual(called, targets);
    assert.deepEqual(result.succeeded, ['a', 'c']);
    assert.deepEqual(result.failed, [{ target: 'b', error }]);
    assert.deepEqual(progress, [1, 2, 3]);
});

test('the confirmed batch is frozen even if the selection changes while deleting', async () => {
    const targets = ['confirmed'], called = [];
    await deleteSelected(targets, async (id) => { called.push(id); targets.push('not-confirmed'); }, () => {});
    assert.deepEqual(called, ['confirmed']);
});
