import * as vscode from 'vscode';

export function errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
}

export async function showErrorWithSettings(error: unknown): Promise<void> {
    const choice = await vscode.window.showErrorMessage(`Git AI: ${errorMessage(error)}`, 'Open Settings');
    if (choice === 'Open Settings') {
        await vscode.commands.executeCommand('workbench.action.openSettings', 'gitAiReview');
    }
}

export async function promptConfigureProvider(): Promise<void> {
    const choice = await vscode.window.showErrorMessage(
        'Git AI: no AI provider is configured. Add an OpenAI-compatible provider (baseUrl + model) in the settings.',
        'Open Settings',
    );
    if (choice === 'Open Settings') {
        await vscode.commands.executeCommand('workbench.action.openSettings', 'gitAiReview');
    }
}
