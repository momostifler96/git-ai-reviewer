import * as vscode from 'vscode';
import { AiSettings, getSettings, ProviderConfig, secretKey } from './configuration';
import { promptConfigureProvider } from './ui';

interface ProviderPickItem extends vscode.QuickPickItem {
    readonly provider: ProviderConfig;
}

export async function setApiKeyCommand(context: vscode.ExtensionContext): Promise<void> {
    const settings = getSettings();
    if (settings.providers.length === 0) {
        await promptConfigureProvider();
        return;
    }
    const provider =
        settings.providers.length === 1 ? settings.providers[0] : await pickProvider(settings);
    if (!provider) {
        return;
    }
    const key = await vscode.window.showInputBox({
        prompt: `API key for "${provider.name}" (${provider.baseUrl}). Leave empty to remove the stored key.`,
        password: true,
        ignoreFocusOut: true,
    });
    if (key === undefined) {
        return;
    }
    const storageKey = secretKey(provider.name);
    if (key.trim() === '') {
        await context.secrets.delete(storageKey);
        vscode.window.showInformationMessage(`Git AI: stored API key for "${provider.name}" removed.`);
    } else {
        await context.secrets.store(storageKey, key.trim());
        vscode.window.showInformationMessage(`Git AI: API key for "${provider.name}" stored securely.`);
    }
}

export async function selectProviderCommand(): Promise<void> {
    const settings = getSettings();
    if (settings.providers.length === 0) {
        await promptConfigureProvider();
        return;
    }
    const provider = await pickProvider(settings);
    if (!provider) {
        return;
    }
    await vscode.workspace
        .getConfiguration('gitAiReview')
        .update('activeProvider', provider.name, vscode.ConfigurationTarget.Global);
    vscode.window.showInformationMessage(
        `Git AI: active provider is now "${provider.name}" (${provider.model}).`,
    );
}

async function pickProvider(settings: AiSettings): Promise<ProviderConfig | undefined> {
    const items: ProviderPickItem[] = settings.providers.map((provider) => ({
        label: provider.name,
        description: `${provider.model} · ${provider.baseUrl}`,
        provider,
    }));
    // The active provider is placed first so it is preselected in the quick pick.
    items.sort((a, b) =>
        a.provider.name === settings.activeProviderName
            ? -1
            : b.provider.name === settings.activeProviderName
              ? 1
              : 0,
    );
    const picked = await vscode.window.showQuickPick<ProviderPickItem>(items, {
        placeHolder: 'Select an AI provider',
        ignoreFocusOut: true,
    });
    return picked?.provider;
}
