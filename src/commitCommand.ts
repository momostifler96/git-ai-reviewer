import * as vscode from 'vscode';
import { requestCompletion } from './ai/client';
import { getSettings, resolveApiKey } from './configuration';
import { collectDiff, getRepositoryRoot, UncommittedDiff } from './git';
import { renderSystemPrompt } from './prompts';
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
    });
    if (!changes.diff.trim()) {
        const all = await collectDiff(root, {
            mode: 'all',
            includeUntracked: settings.includeUntracked,
            maxChars: settings.diffMaxChars,
        });
        if (!all.diff.trim()) {
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

    const raw = await vscode.window.withProgress(
        {
            location: vscode.ProgressLocation.Notification,
            title: `Git AI: drafting commit message with ${provider.name} (${provider.model})…`,
        },
        () =>
            requestCompletion({
                provider,
                apiKey,
                timeoutMs: settings.timeoutMs,
                messages: [
                    {
                        role: 'system',
                        content: renderSystemPrompt(settings.commitSystemPrompt, settings.outputLanguage),
                    },
                    { role: 'user', content: buildCommitUserPrompt(changes) },
                ],
            }),
    );

    const message = cleanCommitMessage(raw);
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

function buildCommitUserPrompt(changes: UncommittedDiff): string {
    const sections: string[] = ['Write the commit message for the following changes.'];
    if (changes.stats.trim()) {
        sections.push('Diffstat:', '```', changes.stats.trim(), '```');
    }
    if (changes.truncated) {
        sections.push('WARNING: the diff was truncated.');
    }
    sections.push('Diff:', '```diff', changes.diff, '```');
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
