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
    });
    if (!changes.diff.trim()) {
        vscode.window.showInformationMessage('Git AI: no uncommitted changes to review.');
        return;
    }

    const apiKey = await resolveApiKey(provider, context.secrets);

    const report = await vscode.window.withProgress(
        {
            location: vscode.ProgressLocation.Notification,
            title: `Git AI: reviewing ${changes.files.length} file(s) with ${provider.name} (${provider.model})…`,
        },
        () => requestReview(provider, apiKey, settings, changes),
    );

    ReviewPanel.show({
        providerLabel: `${provider.name} · ${provider.model}`,
        scopeLabel: `${changes.files.length} file(s) · branch ${changes.branch}`,
        markdown: report,
    });
}

async function requestReview(
    provider: ProviderConfig,
    apiKey: string | undefined,
    settings: AiSettings,
    changes: UncommittedDiff,
): Promise<string> {
    return requestCompletion({
        provider,
        apiKey,
        timeoutMs: settings.timeoutMs,
        messages: [
            {
                role: 'system',
                content: renderSystemPrompt(settings.reviewSystemPrompt, settings.outputLanguage),
            },
            { role: 'user', content: buildReviewUserPrompt(changes) },
        ],
    });
}

function buildReviewUserPrompt(changes: UncommittedDiff): string {
    const sections: string[] = [
        `Review the following uncommitted changes (working tree of branch "${changes.branch}").`,
    ];
    if (changes.stats.trim()) {
        sections.push('Diffstat:', '```', changes.stats.trim(), '```');
    }
    if (changes.truncated) {
        sections.push('WARNING: the diff below was truncated; review only what is visible.');
    }
    sections.push('Diff:', '```diff', changes.diff, '```');
    return sections.join('\n');
}
