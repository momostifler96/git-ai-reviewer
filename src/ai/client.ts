import { ProviderConfig } from '../configuration';

export interface ChatMessage {
    readonly role: 'system' | 'user' | 'assistant';
    readonly content: string;
}

export interface CompletionRequest {
    readonly provider: ProviderConfig;
    readonly apiKey?: string;
    readonly messages: readonly ChatMessage[];
    readonly timeoutMs: number;
}

function chatCompletionsUrl(baseUrl: string): string {
    const trimmed = baseUrl.trim().replace(/\/+$/, '');
    return trimmed.endsWith('/chat/completions') ? trimmed : `${trimmed}/chat/completions`;
}

async function readErrorBody(response: Response): Promise<string> {
    try {
        const text = (await response.text()).trim();
        return text.length > 500 ? `${text.slice(0, 500)}…` : text;
    } catch {
        return '';
    }
}

export async function requestCompletion(request: CompletionRequest): Promise<string> {
    const { provider, apiKey } = request;
    if (!provider.baseUrl?.trim()) {
        throw new Error(`Provider "${provider.name}" has no baseUrl configured.`);
    }
    if (!provider.model?.trim()) {
        throw new Error(`Provider "${provider.name}" has no model configured.`);
    }

    const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        ...(apiKey ? { Authorization: `Bearer ${apiKey}` } : {}),
        ...(provider.headers ?? {}),
    };
    const body: Record<string, unknown> = {
        model: provider.model,
        messages: request.messages,
    };
    if (provider.temperature !== undefined) {
        body.temperature = provider.temperature;
    }
    if (provider.maxTokens !== undefined && provider.maxTokens > 0) {
        body.max_tokens = provider.maxTokens;
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), request.timeoutMs);
    try {
        const response = await fetch(chatCompletionsUrl(provider.baseUrl), {
            method: 'POST',
            headers,
            body: JSON.stringify(body),
            signal: controller.signal,
        });
        if (!response.ok) {
            const detail = await readErrorBody(response);
            const hint = response.status === 401 || response.status === 403
                ? ' (check your API key — command "Git AI: Set API Key for Provider")'
                : '';
            throw new Error(
                `Provider "${provider.name}" returned HTTP ${response.status}` +
                `${response.statusText ? ` ${response.statusText}` : ''}${hint}.` +
                `${detail ? ` — ${detail}` : ''}`,
            );
        }
        const payload = (await response.json()) as {
            choices?: Array<{ message?: { content?: unknown } }>;
        };
        const content = payload.choices?.[0]?.message?.content;
        if (typeof content !== 'string' || !content.trim()) {
            throw new Error(`Provider "${provider.name}" returned an empty completion.`);
        }
        return content.trim();
    } catch (error) {
        if (error instanceof Error && error.name === 'AbortError') {
            throw new Error(
                `Request to "${provider.name}" timed out after ${Math.round(request.timeoutMs / 1000)}s` +
                    ' (increase the setting "gitAiReview.request.timeoutMs" if your model is slow).',
            );
        }
        throw error;
    } finally {
        clearTimeout(timer);
    }
}
