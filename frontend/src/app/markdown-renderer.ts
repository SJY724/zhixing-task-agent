/** Render a small, predictable Markdown subset without trusting raw HTML or URLs. */
export function renderMarkdown(value: string | null | undefined): string {
    if (!value) return '';
    const escaped = value
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
    const inline = (text: string) =>
        text
            .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
            .replace(/`([^`\n]+)`/g, '<code>$1</code>');
    return escaped
        .split(/\n\s*\n/)
        .map((block) => {
            if (/^#{1,4}\s/.test(block))
                return '<h4>' + inline(block.replace(/^#{1,4}\s/, '')) + '</h4>';
            if (block.split('\n').every((line) => /^\s*[-*]\s/.test(line)))
                return (
                    '<ul>' +
                    block
                        .split('\n')
                        .map((line) => '<li>' + inline(line.replace(/^\s*[-*]\s/, '')) + '</li>')
                        .join('') +
                    '</ul>'
                );
            return '<p>' + inline(block).replace(/\n/g, '<br>') + '</p>';
        })
        .join('');
}
