import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { AppComponent } from './app/app.component';

bootstrapApplication(AppComponent, {
    providers: [provideHttpClient(), provideZonelessChangeDetection()],
}).catch(() => {
    document.body.textContent = '页面加载失败，请刷新后重试。若仍失败，请检查浏览器控制台。';
});
