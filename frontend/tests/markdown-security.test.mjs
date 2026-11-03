import test from 'node:test';
import assert from 'node:assert/strict';
import { renderMarkdown } from '../src/app/markdown-renderer.ts';

test('model-controlled raw HTML cannot become executable markup', () => {
    const result = renderMarkdown(
        '**<img src=x onerror="alert(1)">**\n\n<script>alert(2)</script>',
    );
    assert.doesNotMatch(result, /<(?:img|script|iframe)\b/i);
    assert.match(result, /&lt;img/);
    assert.match(result, /<strong>/);
});

test('model-controlled URLs do not become active links', () => {
    const result = renderMarkdown(
        '[点击](javascript:alert(1))\n\n<a href="javascript:alert(2)">x</a>',
    );
    assert.doesNotMatch(result, /<a\b/i);
    assert.match(result, /&lt;a/);
});

test('sources, Chinese text and supported structure remain readable', () => {
    const result = renderMarkdown(
        '## 制度依据\n\n一线城市 **550 元/晚**，来源 `sample-policy.md`。\n\n- 需事先批准\n- 超标自理',
    );
    assert.match(result, /<h4>制度依据<\/h4>/);
    assert.match(result, /<strong>550 元\/晚<\/strong>/);
    assert.match(result, /<code>sample-policy.md<\/code>/);
    assert.equal((result.match(/<li>/g) || []).length, 2);
    assert.equal(renderMarkdown(null), '');
});
