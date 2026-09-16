import * as vscode from 'vscode';
import { marked } from 'marked';

export interface ReviewPanelOptions {
    readonly providerLabel: string;
    readonly scopeLabel: string;
    readonly markdown: string;
}

function escapeHtml(text: string): string {
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function sanitizeHtml(html: string): string {
    return html
        .replace(/<script[\s\S]*?<\/script\s*>/gi, '')
        .replace(/<\/?(iframe|object|embed|link|meta|base|form|input|button)\b[^>]*>/gi, '')
        .replace(/\son\w+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '')
        .replace(/javascript:/gi, '');
}

export class ReviewPanel {
    private static current: vscode.WebviewPanel | undefined;

    static show(options: ReviewPanelOptions): void {
        const html = ReviewPanel.renderHtml(options);
        const existing = ReviewPanel.current;
        if (existing) {
            existing.webview.html = html;
            existing.reveal();
            return;
        }
        const panel = vscode.window.createWebviewPanel(
            'gitAiReview.report',
            'AI Code Review',
            vscode.ViewColumn.Beside,
            { enableScripts: false, enableFindWidget: true },
        );
        panel.webview.html = html;
        panel.onDidDispose(() => {
            ReviewPanel.current = undefined;
        });
        ReviewPanel.current = panel;
    }

    private static renderHtml(options: ReviewPanelOptions): string {
        const generated = new Date().toLocaleString();
        const body = sanitizeHtml(marked.parse(options.markdown, { async: false }) as string);
        return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src https: data:;">
<style>
  body {
    margin: 0;
    padding: 0 28px 40px;
    color: var(--vscode-editor-foreground);
    background-color: var(--vscode-editor-background);
    font-family: var(--vscode-font-family);
    font-size: 14px;
    line-height: 1.6;
  }
  header {
    margin: 18px 0 10px;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--vscode-panel-border);
  }
  header h1 { font-size: 18px; font-weight: 600; margin: 0 0 4px; }
  .meta { color: var(--vscode-descriptionForeground); font-size: 12px; }
  main h1 { font-size: 17px; margin: 1.5em 0 0.5em; }
  main h2 { font-size: 15px; margin: 1.4em 0 0.5em; }
  main h3 { font-size: 14px; margin: 1.2em 0 0.4em; }
  main p { margin: 0.6em 0; }
  code {
    font-family: var(--vscode-editor-font-family);
    background-color: var(--vscode-textCodeBlock-background);
    padding: 1px 4px;
    border-radius: 3px;
    font-size: 12.5px;
  }
  pre {
    background-color: var(--vscode-textCodeBlock-background);
    padding: 10px 12px;
    border-radius: 6px;
    overflow-x: auto;
  }
  pre code { background: none; padding: 0; }
  table { border-collapse: collapse; margin: 0.8em 0; }
  td, th { border: 1px solid var(--vscode-panel-border); padding: 4px 10px; }
  blockquote {
    margin: 0.8em 0;
    padding: 2px 14px;
    border-left: 3px solid var(--vscode-panel-border);
    color: var(--vscode-descriptionForeground);
  }
  a { color: var(--vscode-textLink-foreground); }
  ul, ol { padding-left: 1.6em; }
  hr { border: none; border-top: 1px solid var(--vscode-panel-border); }
</style>
</head>
<body>
<header>
  <h1>AI Code Review</h1>
  <div class="meta">${escapeHtml(options.scopeLabel)} &middot; ${escapeHtml(options.providerLabel)} &middot; ${escapeHtml(generated)}</div>
</header>
<main>${body}</main>
</body>
</html>`;
    }
}
