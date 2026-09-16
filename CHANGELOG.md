# Changelog

## 0.1.0 — 2026-09-16

- Initial release.
- Review uncommitted changes (staged + unstaged + untracked files) and display a Markdown report in a webview panel.
- Generate Conventional Commits messages from staged changes (fallback: all uncommitted changes) and insert them into the Source Control input box.
- Configurable OpenAI-compatible providers (baseUrl, model, extra headers, temperature, maxTokens) with quick-pick provider selection.
- API keys stored in VS Code SecretStorage via the `Git AI: Set API Key for Provider` command.
- Fully custom system prompts for review and commit message generation, with a `{language}` placeholder.
