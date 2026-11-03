import { readdir, readFile, copyFile, mkdir, rm } from 'node:fs/promises';
import { resolve, join, dirname } from 'node:path';
const root = resolve(import.meta.dirname, '..');
const built = join(root, 'dist', 'zhixing', 'browser');
const target = resolve(root, '..', 'src', 'main', 'resources', 'static');
const index = await readFile(join(built, 'index.html'), 'utf8');
if (!index.includes('app-root') || !index.includes('main-'))
    throw new Error('请先成功运行生产构建，再同步资源。');
await mkdir(target, { recursive: true });
const prior = await readdir(target, { withFileTypes: true });
for (const entry of prior) {
    const path = resolve(target, entry.name);
    if (dirname(path) !== target) throw new Error('资源路径越界');
    await rm(path, { recursive: entry.isDirectory(), force: true });
}
async function copy(source, destination) {
    await mkdir(destination, { recursive: true });
    for (const entry of await readdir(source, { withFileTypes: true })) {
        if (entry.isDirectory())
            await copy(join(source, entry.name), join(destination, entry.name));
        else await copyFile(join(source, entry.name), join(destination, entry.name));
    }
}
await copy(built, target);
await copyFile(
    join(root, 'dist', 'zhixing', '3rdpartylicenses.txt'),
    join(target, '3rdpartylicenses.txt'),
);
console.log('Angular 生产资源已同步到 Spring Boot static 目录。');
