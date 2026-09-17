import * as vscode from 'vscode';
import { requestCompletion } from './ai/client';
import { getSettings, resolveApiKey } from './configuration';
import { collectDiff, getRepositoryRoot, UncommittedDiff } from './git';
import { DEFAULT_DIFF_SUMMARY_PROMPT, renderSystemPrompt } from './prompts';
import { promptConfigureProvider, showErrorWithSettings } from './ui';

export async function generateCommitMessageCommand(context: vscode.ExtensionContext): Promise<void> {
    try {
        await run(context);
    } catch (error) {
        await showErrorWithSettings(error);
    }
}

async function run(context: vscode.ExtensionContext): Promise<void> {
    const root = await getRepositoryRoot();
    if (!root) {
        vscode.window.showWarningMessage('Git AI: no Git repository found in the open workspace.');
        return;
    }

    const settings = getSettings();
    const provider = settings.commitProvider;
    if (!provider) {
        await promptConfigureProvider();
        return;
    }

    let changes = await collectDiff(root, {
        mode: 'staged',
        includeUntracked: false,
        maxChars: settings.diffMaxChars,
        chunking: settings.chunkingMode,
    });
    if (changes.chunks.length === 0 || changes.chunks.every((chunk) => chunk.trim() === '')) {
        const all = await collectDiff(root, {
            mode: 'all',
            includeUntracked: settings.includeUntracked,
            maxChars: settings.diffMaxChars,
            chunking: settings.chunkingMode,
        });
        if (all.chunks.length === 0 || all.chunks.every((chunk) => chunk.trim() === '')) {
            vscode.window.showInformationMessage('Git AI: nothing to commit — no staged or uncommitted changes.');
            return;
        }
        const useAll = await vscode.window.showWarningMessage(
            'Git AI: nothing is staged yet. Generate the commit message from ALL uncommitted changes (staged + unstaged + new files)?',
            'Yes, use all changes',
        );
        if (useAll !== 'Yes, use all changes') {
            return;
        }
        changes = all;
    }

    const apiKey = await resolveApiKey(provider, context.secrets);
    const systemPrompt = renderSystemPrompt(settings.commitSystemPrompt, settings.outputLanguage);
    let message = '';

    await vscode.window.withProgress(
        {
            location: vscode.ProgressLocation.Notification,
            title:
                `Git AI: drafting commit message with ${provider.name} (${provider.model})` +
                (changes.chunks.length > 1 ? ` from ${changes.chunks.length} parts…` : '…'),
        },
        async (progress) => {
            if (changes.chunks.length === 1) {
                message = cleanCommitMessage(
                    await requestCompletion({
                        provider,
                        apiKey,
                        timeoutMs: settings.timeoutMs,
                        messages: [
                            { role: 'system', content: systemPrompt },
                            { role: 'user', content: buildCommitUserPrompt(changes, 0) },
                        ],
                    }),
                );
                return;
            }

            // Multi-part flow: summarize each part, then write one message from the summaries.
            const summaries: string[] = [];
            for (let index = 0; index < changes.chunks.length; index++) {
                progress.report({ message: `summarizing part ${index + 1}/${changes.chunks.length}` });
                summaries.push(
                    await requestCompletion({
                        provider,
                        apiKey,
                        timeoutMs: settings.timeoutMs,
                        messages: [
                            {
                                role: 'system',
                                content: renderSystemPrompt(
                                    DEFAULT_DIFF_SUMMARY_PROMPT,
                                    settings.outputLanguage,
                                ),
                            },
                            { role: 'user', content: buildSummaryUserPrompt(changes, index) },
                        ],
                    }),
                );
            }

            progress.report({ message: 'drafting commit message' });
            message = cleanCommitMessage(
                await requestCompletion({
                    provider,
                    apiKey,
                    timeoutMs: settings.timeoutMs,
                    messages: [
                        { role: 'system', content: systemPrompt },
                        { role: 'user', content: buildCommitFromSummariesPrompt(changes, summaries) },
                    ],
                }),
            );
        },
    );

    if (!message) {
        vscode.window.showErrorMessage('Git AI: the provider returned an empty commit message.');
        return;
    }

    if (await insertIntoScmInputBox(root, message)) {
        vscode.window.showInformationMessage(
            'Git AI: commit message inserted into the Source Control input box — edit it there if needed.',
        );
    } else {
        await vscode.env.clipboard.writeText(message);
        vscode.window.showInformationMessage('Git AI: commit message copied to the clipboard.');
    }
}

function buildCommitUserPrompt(changes: UncommittedDiff, index: number): string {
    const sections: string[] = ['Write the commit message for the following changes.'];
    if (changes.chunks.length > 1) {
        sections.push(`(This is part ${index + 1} of ${changes.chunks.length} of the diff.)`);
    }
    if (changes.stats.trim()) {
        sections.push('Diffstat:', '```', changes.stats.trim(), '```');
    }
    if (changes.truncated) {
        sections.push('WARNING: the diff was truncated.');
    }
    sections.push('Diff:', '```diff', changes.chunks[index], '```');
    return sections.join('\n');
}

function buildSummaryUserPrompt(changes: UncommittedDiff, index: number): string {
    return [
        `Here is part ${index + 1} of ${changes.chunks.length} of the diff to commit:`,
        '```diff',
        changes.chunks[index],
        '```',
    ].join('\n');
}

function buildCommitFromSummariesPrompt(changes: UncommittedDiff, summaries: string[]): string {
    const parts = summaries.map((summary, index) => `Part ${index + 1}:\n${summary.trim()}`);
    const sections = [
        'The changes to commit were too large for a single request.',
        'Here is a summary of each part of the diff:',
        ...parts.map((part) => `\n${part}`),
    ];
    if (changes.stats.trim()) {
        sections.push('\nDiffstat of the full change set:', '```', changes.stats.trim(), '```');
    }
    sections.push('\nWrite the commit message for these changes.');
    return sections.join('\n');
}

function cleanCommitMessage(raw: string): string {
    const text = raw.trim();
    const fenced = /^```[^\n]*\n([\s\S]*?)\n?```\s*$/.exec(text);
    return (fenced ? fenced[1] : text).trim();
}

interface ScmRepository {
    readonly rootUri: vscode.Uri;
    readonly inputBox?: { value: string };
}

async function insertIntoScmInputBox(root: string, message: string): Promise<boolean> {
    const extension = vscode.extensions.getExtension('vscode.git');
    if (!extension) {
        return false;
    }
    try {
        const exports = extension.isActive ? extension.exports : await extension.activate();
        const api = typeof exports?.getAPI === 'function' ? exports.getAPI(1) : undefined;
        const repositories: ScmRepository[] | undefined = api?.repositories;
        if (!repositories?.length) {
            return false;
        }
        const normalize = (value: string): string => value.replace(/\\/g, '/').toLowerCase();
        const repository =
            repositories.find((repo) => normalize(repo.rootUri.fsPath) === normalize(root)) ??
            repositories[0];
        if (!repository?.inputBox) {
            return false;
        }
        repository.inputBox.value = message;
        return true;
    } catch {
        return false;
    }
}
