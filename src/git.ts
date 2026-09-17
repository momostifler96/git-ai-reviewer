import { execFile } from 'child_process';
import { promises as fsp } from 'fs';
import * as path from 'path';
import { promisify } from 'util';
import * as vscode from 'vscode';
import { buildChunks, splitIntoFileBlocks } from './chunking';

const execFileAsync = promisify(execFile);

const MAX_BUFFER = 1024 * 1024 * 100;
const MAX_UNTRACKED_FILES = 100;
const MAX_UNTRACKED_LINES_PER_FILE = 1500;

interface GitResult {
    readonly stdout: string;
    readonly code: number;
}

async function runGit(root: string, args: string[]): Promise<GitResult> {
    try {
        const { stdout } = await execFileAsync('git', args, {
            cwd: root,
            maxBuffer: MAX_BUFFER,
            encoding: 'utf8',
        });
        return { stdout, code: 0 };
    } catch (error) {
        const failure = error as { code?: unknown; stdout?: unknown; message?: unknown };
        if (failure.code === 'ENOENT') {
            throw new Error('Git executable not found. Install Git and make sure it is in the PATH.');
        }
        // git exits with 1 for "no result" cases we treat as empty output.
        const code = typeof failure.code === 'number' ? failure.code : 1;
        if (typeof failure.stdout === 'string') {
            return { stdout: failure.stdout, code };
        }
        throw new Error(`Failed to run "git ${args.join(' ')}": ${String(failure.message ?? failure)}`);
    }
}

export async function getRepositoryRoot(): Promise<string | undefined> {
    for (const folder of vscode.workspace.workspaceFolders ?? []) {
        const result = await runGit(folder.uri.fsPath, ['rev-parse', '--show-toplevel']);
        if (result.code === 0 && result.stdout.trim()) {
            return result.stdout.trim();
        }
    }
    return undefined;
}

async function hasHead(root: string): Promise<boolean> {
    const result = await runGit(root, ['rev-parse', '--verify', '--quiet', 'HEAD']);
    return result.code === 0;
}

async function currentBranch(root: string): Promise<string> {
    const result = await runGit(root, ['rev-parse', '--abbrev-ref', 'HEAD']);
    return result.code === 0 ? result.stdout.trim() : '';
}

function isBinary(buffer: Buffer): boolean {
    return buffer.subarray(0, 8000).includes(0);
}

async function untrackedDiff(root: string, relativePath: string): Promise<string> {
    let buffer: Buffer;
    try {
        buffer = await fsp.readFile(path.join(root, relativePath));
    } catch {
        return '';
    }
    const header =
        `diff --git a/${relativePath} b/${relativePath}\n` +
        'new file mode 100644\n' +
        '--- /dev/null\n' +
        `+++ b/${relativePath}\n`;
    if (isBinary(buffer)) {
        return `${header}@@ -0,0 +1 @@\n+[binary file not shown]\n`;
    }
    const lines = buffer.toString('utf8').split(/\r?\n/);
    if (lines.length > 0 && lines[lines.length - 1] === '') {
        lines.pop();
    }
    const truncated = lines.length > MAX_UNTRACKED_LINES_PER_FILE;
    const shown = truncated ? lines.slice(0, MAX_UNTRACKED_LINES_PER_FILE) : lines;
    const body = shown.map((line) => `+${line}`).join('\n');
    const footer = truncated ? `\n+... [file truncated after ${MAX_UNTRACKED_LINES_PER_FILE} lines]` : '';
    return `${header}@@ -0,0 +1,${shown.length} @@\n${body}\n${footer}\n`;
}

interface DiffPart {
    readonly patch: string;
    readonly names: readonly string[];
    readonly stat: string;
}

async function diffInfo(root: string, baseArgs: string[]): Promise<DiffPart> {
    const [patch, names, stat] = await Promise.all([
        runGit(root, ['diff', ...baseArgs]),
        runGit(root, ['diff', ...baseArgs, '--name-only']),
        runGit(root, ['diff', ...baseArgs, '--stat']),
    ]);
    return {
        patch: patch.stdout,
        names: names.stdout.split(/\r?\n/).map((line) => line.trim()).filter(Boolean),
        stat: stat.stdout.trim(),
    };
}

export type ChunkingMode = 'chunk' | 'truncate';

export interface UncommittedDiff {
    /** Coherent parts of the diff — one entry per AI request. */
    readonly chunks: readonly string[];
    readonly files: readonly string[];
    readonly stats: string;
    readonly branch: string;
    /** True only when the content was actually cut (chunking mode 'truncate'). */
    readonly truncated: boolean;
}

export interface CollectDiffOptions {
    /** 'staged' = index only, 'all' = everything not committed yet. */
    readonly mode: 'staged' | 'all';
    readonly includeUntracked: boolean;
    readonly maxChars: number;
    readonly chunking: ChunkingMode;
}

export async function collectDiff(root: string, options: CollectDiffOptions): Promise<UncommittedDiff> {
    const withHead = await hasHead(root);
    const branch = await currentBranch(root);

    const outputs: string[] = [];
    const stats: string[] = [];
    const files = new Set<string>();

    const push = (part: DiffPart): void => {
        if (part.patch.trim()) {
            outputs.push(part.patch);
        }
        part.names.forEach((name) => files.add(name));
        if (part.stat) {
            stats.push(part.stat);
        }
    };

    if (options.mode === 'all' && withHead) {
        push(await diffInfo(root, ['HEAD']));
    } else if (options.mode === 'all') {
        push(await diffInfo(root, ['--cached']));
        push(await diffInfo(root, []));
    } else {
        push(await diffInfo(root, ['--cached']));
    }

    if (options.mode === 'all' && options.includeUntracked) {
        const listed = await runGit(root, ['ls-files', '--others', '--exclude-standard']);
        const untracked = listed.stdout.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
        for (const file of untracked.slice(0, MAX_UNTRACKED_FILES)) {
            const patch = await untrackedDiff(root, file);
            if (patch) {
                outputs.push(patch);
                files.add(file);
            }
        }
    }

    const blocks = splitIntoFileBlocks(outputs);
    const statsText = stats.join('\n');

    if (options.chunking === 'truncate') {
        const joined = blocks.join('\n');
        if (joined.length <= options.maxChars) {
            return {
                chunks: [joined],
                files: [...files].sort(),
                stats: statsText,
                branch: branch || '(no commits yet)',
                truncated: false,
            };
        }
        return {
            chunks: [
                `${joined.slice(0, options.maxChars)}\n\n[... diff truncated at ${options.maxChars} characters ...]`,
            ],
            files: [...files].sort(),
            stats: statsText,
            branch: branch || '(no commits yet)',
            truncated: true,
        };
    }

    return {
        chunks: buildChunks(blocks, options.maxChars),
        files: [...files].sort(),
        stats: statsText,
        branch: branch || '(no commits yet)',
        truncated: false,
    };
}
