import { Component, Input, ChangeDetectionStrategy } from '@angular/core';
const paths: Record<string, string> = {
    grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
    task: 'M9 5h10a2 2 0 0 1 2 2v13H3V7a2 2 0 0 1 2-2h1 M9 3h6v4H9z M7 12h10 M7 16h6',
    book: 'M12 5C9 3 5 3 2 4v15c3-1 7-1 10 1 3-2 7-2 10-1V4c-3-1-7-1-10 1z M12 5v15',
    settings:
        'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M9 3l-1 3-3 1-2 4 2 2 1 4 3 1 1 3h4l1-3 3-1 2-4-2-2-1-4-3-1-1-3z',
    plus: 'M12 5v14 M5 12h14',
    arrow: 'M5 12h14 M13 6l6 6-6 6',
    chevron: 'M9 5l7 7-7 7',
    down: 'm6 9 6 6 6-6',
    search: 'M21 21l-6-6 M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14',
    check: 'm5 12 4 4L19 6',
    clock: 'M12 8v5l3 2 M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20',
    refresh: 'M20 7a9 9 0 1 0 1 9 M20 3v5h-5',
    close: 'm6 6 12 12 M6 18 18 6',
    sparkle: 'm12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5z',
    file: 'M14 2H5a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9z M14 2v7h7 M7 13h10 M7 17h7',
    upload: 'M12 16V3 M7 8l5-5 5 5 M4 16v5h16v-5',
    trash: 'M3 6h18 M9 6V3h6v3 M5 6l1 15h12l1-15 M10 10v7 M14 10v7',
    shield: 'M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6z m-5 10 3 3 7-7',
    copy: 'M8 8h12v13H8z M16 8V3H3v13h5',
    code: 'm8 6-6 6 6 6 m8-12 6 6-6 6 M14 3l-4 18',
    layers: 'm12 2 10 5-10 5L2 7z M2 12l10 5 10-5 M2 17l10 5 10-5',
    warning: 'm12 3 10 18H2z M12 9v5 M12 17h.01',
    stop: 'M6 6h12v12H6z',
    link: 'M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-2 2 M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l2-2',
    database:
        'M21 5c0 2-4 3-9 3S3 7 3 5s4-3 9-3 9 1 9 3z M3 5v14c0 2 4 3 9 3s9-1 9-3V5 M3 12c0 2 4 3 9 3s9-1 9-3',
    info: 'M12 11v6 M12 7h.01 M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20',
    menu: 'M3 6h18 M3 12h18 M3 18h18',
    logout: 'M9 4H3v16h6 M8 12h13 M16 7l5 5-5 5',
};
@Component({
    selector: 'ui-icon',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template:
        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path [attr.d]="path"/></svg>',
    styles: [
        ':host{display:inline-flex;width:20px;height:20px;flex:0 0 auto}svg{width:100%;height:100%}',
    ],
})
export class IconComponent {
    @Input() name = 'grid';
    get path() {
        return paths[this.name] || paths['info'];
    }
}
