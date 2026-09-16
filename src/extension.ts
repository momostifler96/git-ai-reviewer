import * as vscode from 'vscode';
import { generateCommitMessageCommand } from './commitCommand';
import { selectProviderCommand, setApiKeyCommand } from './providerCommands';
import { reviewUncommittedCommand } from './reviewCommand';

export function activate(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.commands.registerCommand('gitAiReview.reviewUncommitted', () =>
            reviewUncommittedCommand(context),
        ),
        vscode.commands.registerCommand('gitAiReview.generateCommitMessage', () =>
            generateCommitMessageCommand(context),
        ),
        vscode.commands.registerCommand('gitAiReview.selectProvider', () =>
            selectProviderCommand(),
        ),
        vscode.commands.registerCommand('gitAiReview.setApiKey', () =>
            setApiKeyCommand(context),
        ),
    );
}

export function deactivate(): void {
    // nothing to clean up
}
