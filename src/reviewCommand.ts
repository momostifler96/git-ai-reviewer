import * as vscode from 'vscode';
import { requestCompletion } from './ai/client';
import { AiSettings, getSettings, ProviderConfig, resolveApiKey } from './configuration';
import { collectDiff, getRepositoryRoot, UncommittedDiff } from './git';
import { renderSystemPrompt } from './prompts';
import { ReviewPanel } from './reviewPanel';
import { promptConfigureProvider, showErrorWithSettings } from './ui';

export async function reviewUncommittedCommand(context: vscode.ExtensionContext): Promise<void> {
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
    const provider = settings.reviewProvider;
    if (!provider) {
        await promptConfigureProvider();
        return;
    }

    const changes = await collectDiff(root, {
        mode: 'all',
        includeUntracked: settings.includeUntracked,
        maxChars: settings.diffMaxChars,
        chunking: settings.chunkingMode,
    });
    if (changes.chunks.length === 0 || changes.chunks.every((chunk) => chunk.trim() === '')) {
        vscode.window.showInformationMessage('Git AI: no uncommitted changes to review.');
        return;
    }

    const apiKey = await resolveApiKey(provider, context.secrets);
    const reports: string[] = [];

    await vscode.window.withProgress(
        {
            location: vscode.ProgressLocation.Notification,
            title:
                `Git AI: reviewing ${changes.files.length} file(s) with ${provider.name} (${provider.model})` +
                (changes.chunks.length > 1 ? ` in ${changes.chunks.length} parts…` : '…'),
        },
        async (progress) => {
            for (let index = 0; index < changes.chunks.length; index++) {
                progress.report({ message: `part ${index + 1}/${changes.chunks.length}` });
                reports.push(
                    await requestCompletion({
                        provider,
                        apiKey,
                        timeoutMs: settings.timeoutMs,
                        messages: [
                            {
                                role: 'system',
                                content: renderSystemPrompt(
                                    settings.reviewSystemPrompt,
                                    settings.outputLanguage,
                                ),
                            },
                            { role: 'user', content: buildReviewUserPrompt(changes, index) },
                        ],
                    }),
                );
            }
        },
    );

    ReviewPanel.show({
        providerLabel: `${provider.name} · ${provider.model}`,
        scopeLabel: `${changes.files.length} file(s) · branch ${changes.branch}`,
        markdown: reports.join('\n\n---\n\n'),
    });
}

function buildReviewUserPrompt(changes: UncommittedDiff, index: number): string {
    const total = changes.chunks.length;
    const sections: string[] = [];
    if (total > 1) {
        sections.push(
            `Review the following uncommitted changes (working tree of branch "${changes.branch}"). ` +
                `This is part ${index + 1} of ${total} of the full diff — the other parts are sent in ` +
                'separate requests; review only what is visible here.',
        );
    } else {
        sections.push(
            `Review the following uncommitted changes (working tree of branch "${changes.branch}").`,
        );
    }
    if (changes.stats.trim()) {
        sections.push('Diffstat of the full change set:', '```', changes.stats.trim(), '```');
    }
    if (changes.truncated) {
        sections.push('WARNING: the diff below was truncated; review only what is visible.');
    }
    sections.push('Diff:', '```diff', changes.chunks[index], '```');
    return sections.join('\n');
}
