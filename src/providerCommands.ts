import * as vscode from 'vscode';
import { AiSettings, getSettings, ProviderConfig, secretKey } from './configuration';
import { promptConfigureProvider } from './ui';

type ProviderSlot = 'activeProvider' | 'reviewProvider' | 'commitProvider';

const SLOT_LABELS: Record<ProviderSlot, string> = {
    activeProvider: 'Default (used for everything)',
    reviewProvider: 'Code review',
    commitProvider: 'Commit messages',
};

interface SlotPickItem extends vscode.QuickPickItem {
    readonly slot: ProviderSlot;
}

interface ProviderPickItem extends vscode.QuickPickItem {
    /** undefined = inherit from the default provider. */
    readonly provider?: ProviderConfig;
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

    const current = (slot: ProviderSlot): string => {
        switch (slot) {
            case 'activeProvider':
                return settings.activeProvider?.name ?? 'none';
            case 'reviewProvider':
                return settings.reviewProvider?.name ?? 'none';
            case 'commitProvider':
                return settings.commitProvider?.name ?? 'none';
            default:
                return 'none';
        }
    };

    const slotPick = await vscode.window.showQuickPick<SlotPickItem>(
        (Object.keys(SLOT_LABELS) as ProviderSlot[]).map((slot) => ({
            label: SLOT_LABELS[slot],
            description: `currently: ${current(slot)}`,
            slot,
        })),
        { placeHolder: 'Which provider slot do you want to configure?', ignoreFocusOut: true },
    );
    if (!slotPick) {
        return;
    }

    const items: ProviderPickItem[] = settings.providers.map((provider) => ({
        label: provider.name,
        description: `${provider.model} · ${provider.baseUrl}`,
        provider,
    }));
    if (slotPick.slot !== 'activeProvider') {
        items.unshift({
            label: '$(link) Same as the default provider',
            description: `inherit from "${current('activeProvider')}"`,
            provider: undefined,
        });
    }

    const picked = await vscode.window.showQuickPick<ProviderPickItem>(items, {
        placeHolder: `Provider for: ${slotPick.label}`,
        ignoreFocusOut: true,
    });
    if (!picked) {
        return;
    }

    await vscode.workspace
        .getConfiguration('gitAiReview')
        .update(slotPick.slot, picked.provider?.name ?? '', vscode.ConfigurationTarget.Global);

    const summary = picked.provider
        ? `${slotPick.label} → "${picked.provider.name}" (${picked.provider.model})`
        : `${slotPick.label} → inherits from "${current('activeProvider')}"`;
    vscode.window.showInformationMessage(`Git AI: ${summary}.`);
}

async function pickProvider(settings: AiSettings): Promise<ProviderConfig | undefined> {
    const items: ProviderPickItem[] = settings.providers.map((provider) => ({
        label: provider.name,
        description: `${provider.model} · ${provider.baseUrl}`,
        provider,
    }));
    // The active provider is placed first so it is preselected in the quick pick.
    items.sort((a, b) =>
        a.provider?.name === settings.activeProviderName
            ? -1
            : b.provider?.name === settings.activeProviderName
              ? 1
              : 0,
    );
    const picked = await vscode.window.showQuickPick<ProviderPickItem>(items, {
        placeHolder: 'Select an AI provider',
        ignoreFocusOut: true,
    });
    return picked?.provider;
}
