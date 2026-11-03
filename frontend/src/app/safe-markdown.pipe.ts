import { Pipe, PipeTransform } from '@angular/core';
import { renderMarkdown } from './markdown-renderer';
/** A deliberately small renderer: escape all raw HTML before adding known formatting. */
@Pipe({ name: 'safeMarkdown', standalone: true })
export class SafeMarkdownPipe implements PipeTransform {
    transform(value: string | null | undefined): string {
        return renderMarkdown(value);
    }
}
