import * as vscode from 'vscode';
import { DEFAULT_COMMIT_SYSTEM_PROMPT, DEFAULT_REVIEW_SYSTEM_PROMPT } from './prompts';

export interface ProviderConfig {
    readonly name: string;
    readonly baseUrl: string;
    readonly model: string;
    readonly apiKey?: string;
    readonly headers?: Record<string, string>;
    readonly temperature?: number;
    readonly maxTokens?: number;
}

export interface AiSettings {
    readonly providers: readonly ProviderConfig[];
    readonly activeProviderName: string;
    /** Default provider, used when no specific one is set. */
    readonly activeProvider: ProviderConfig | undefined;
    /** Provider used for code review (falls back to activeProvider). */
    readonly reviewProvider: ProviderConfig | undefined;
    /** Provider used for commit messages (falls back to activeProvider). */
    readonly commitProvider: ProviderConfig | undefined;
    readonly timeoutMs: number;
    readonly outputLanguage: string;
    readonly diffMaxChars: number;
    readonly includeUntracked: boolean;
    readonly reviewSystemPrompt: string;
    readonly commitSystemPrompt: string;
}

/** Raw provider shape as read from settings.json (headers may be a string or an object). */
interface RawProvider {
    name?: unknown;
    baseUrl?: unknown;
    model?: unknown;
    apiKey?: unknown;
    headers?: unknown;
    temperature?: unknown;
    maxTokens?: unknown;
}

/**
 * Parses extra HTTP headers. Accepted formats:
 * - string: "Name: Value; Other: Value" (editable in the settings table)
 * - object: { "Name": "Value" } (convenient in settings.json)
 */
function parseHeaders(raw: unknown): Record<string, string> | undefined {
    if (typeof raw === 'string') {
        const headers: Record<string, string> = {};
        for (const part of raw.split(';')) {
            const separator = part.indexOf(':');
            const name = part.slice(0, separator).trim();
            const value = part.slice(separator + 1).trim();
            if (separator >= 0 && name && value) {
                headers[name] = value;
            }
        }
        return Object.keys(headers).length > 0 ? headers : undefined;
    }
    if (raw && typeof raw === 'object') {
        const headers: Record<string, string> = {};
        for (const [name, value] of Object.entries(raw)) {
            if (typeof value === 'string' && name.trim() && value.trim()) {
                headers[name.trim()] = value.trim();
            }
        }
        return Object.keys(headers).length > 0 ? headers : undefined;
    }
    return undefined;
}

function normalizeProvider(raw: RawProvider): ProviderConfig | undefined {
    const name = typeof raw.name === 'string' ? raw.name.trim() : '';
    const baseUrl = typeof raw.baseUrl === 'string' ? raw.baseUrl.trim() : '';
    const model = typeof raw.model === 'string' ? raw.model.trim() : '';
    if (!name || !baseUrl || !model) {
        return undefined;
    }
    return {
        name,
        baseUrl,
        model,
        apiKey:
            typeof raw.apiKey === 'string' && raw.apiKey.trim() ? raw.apiKey.trim() : undefined,
        headers: parseHeaders(raw.headers),
        temperature: typeof raw.temperature === 'number' ? raw.temperature : undefined,
        maxTokens: typeof raw.maxTokens === 'number' ? raw.maxTokens : undefined,
    };
}

export function getSettings(): AiSettings {
    const config = vscode.workspace.getConfiguration('gitAiReview');
    const providers = (config.get<unknown[]>('providers', []) ?? [])
        .filter((raw): raw is RawProvider => !!raw && typeof raw === 'object')
        .map(normalizeProvider)
        .filter((provider): provider is ProviderConfig => provider !== undefined);
    const requested = config.get<string>('activeProvider', 'openai');
    const activeProvider = providers.find((provider) => provider.name === requested) ?? providers[0];
    const reviewOverride = config.get<string>('reviewProvider', '').trim();
    const commitOverride = config.get<string>('commitProvider', '').trim();
    const reviewProvider =
        providers.find((provider) => provider.name === reviewOverride) ?? activeProvider;
    const commitProvider =
        providers.find((provider) => provider.name === commitOverride) ?? activeProvider;
    return {
        providers,
        activeProviderName: activeProvider?.name ?? requested,
        activeProvider,
        reviewProvider,
        commitProvider,
        timeoutMs: config.get<number>('request.timeoutMs', 300_000),
        outputLanguage: config.get<string>('outputLanguage', 'English'),
        diffMaxChars: Math.max(1_000, config.get<number>('diff.maxChars', 60_000)),
        includeUntracked: config.get<boolean>('diff.includeUntracked', true),
        reviewSystemPrompt:
            config.get<string>('prompts.reviewSystem', '').trim() || DEFAULT_REVIEW_SYSTEM_PROMPT,
        commitSystemPrompt:
            config.get<string>('prompts.commitSystem', '').trim() || DEFAULT_COMMIT_SYSTEM_PROMPT,
    };
}

export function secretKey(providerName: string): string {
    return `gitAiReview.apiKey.${providerName}`;
}

export async function resolveApiKey(
    provider: ProviderConfig,
    secrets: vscode.SecretStorage,
): Promise<string | undefined> {
    return (await secrets.get(secretKey(provider.name))) ?? provider.apiKey;
}
