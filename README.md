# Git AI Reviewer — extension VS Code

Extension VS Code qui :

- 🔍 **fait relire vos modifications Git non commitées** (staged + unstaged + fichiers nouveaux) par une IA, avec un rapport Markdown classé par sévérité (critique / majeur / mineur) ;
- ✉️ **génère un message de commit** (format Conventional Commits) à partir des changements stagés, et l'insère directement dans la zone de message du panneau *Source Control* ;
- 🔌 fonctionne avec **n'importe quel provider compatible OpenAI** (route `/chat/completions`) : OpenAI, Ollama, LM Studio, OpenRouter, Groq, vLLM, etc. — plusieurs providers configurables et sélectionnables ;
- 📝 **prompts système entièrement personnalisables**, un pour la revue de code et un pour les messages de commit.

## Commandes

| Commande | Description |
| --- | --- |
| `Git AI: Review Uncommitted Changes` | Envoie le diff non commité (staged + unstaged + fichiers non suivis) à l'IA et affiche le rapport de revue dans un panneau Markdown. |
| `Git AI: Generate Commit Message` | Génère un message de commit à partir des changements **stagés**. Si rien n'est stagé, propose d'utiliser tous les changements non commités. Le résultat est inséré dans la zone de commit du panneau Source Control (copié dans le presse-papier si l'API Git intégrée n'est pas disponible). |
| `Git AI: Select Active Provider` | Change le provider actif (quick pick). |
| `Git AI: Set API Key for Provider` | Stocke la clé API d'un provider dans le **secret storage** de VS Code (recommandé, jamais écrit en clair dans settings.json). |

Les deux premières commandes sont aussi accessibles via des icônes dans la barre de titre du panneau **Source Control**.

## Configuration

### Providers (OpenAI-compatible)

Le réglage `gitAiReview.providers` s'affiche comme un **tableau éditable** dans l'interface Réglages de VS Code : une ligne par provider, une colonne par champ (`name`, `baseUrl`, `model`, `apiKey`, `temperature`, `maxTokens`, `headers`), avec ajout/suppression de lignes directement dans l'interface. Exemple équivalent en JSON :

```jsonc
{
  "gitAiReview.providers": [
    {
      "name": "openai",
      "baseUrl": "https://api.openai.com/v1",
      "model": "gpt-4o-mini",
      "temperature": 0.2,
      "maxTokens": 2048
    },
    {
      "name": "ollama",
      "baseUrl": "http://localhost:11434/v1",
      "model": "qwen2.5-coder:7b"
    },
    {
      "name": "openrouter",
      "baseUrl": "https://openrouter.ai/api/v1",
      "model": "anthropic/claude-3.5-sonnet",
      "headers": "HTTP-Referer: https://localhost; X-Title: Git AI Reviewer"
    }
  ],
  "gitAiReview.activeProvider": "openai",
  "gitAiReview.outputLanguage": "Français"
}
```

Champs d'un provider :

| Champ | Requis | Description |
| --- | --- | --- |
| `name` | ✅ | Nom unique (utilisé par `activeProvider` et le stockage des clés API). |
| `baseUrl` | ✅ | URL de base OpenAI-compatible ; l'extension appelle `{{baseUrl}}/chat/completions`. |
| `model` | ✅ | Identifiant du modèle envoyé dans la requête. |
| `apiKey` | — | Clé API en clair dans les settings (**non recommandé**). Préférez la commande `Git AI: Set API Key for Provider`. |
| `temperature` | — | Température d'échantillonnage. |
| `maxTokens` | — | Limite de tokens de la réponse. |
| `headers` | — | En-têtes HTTP additionnels au format `Nom: Valeur` séparés par `;` (ex. `HTTP-Referer: https://monapp.dev; X-Title: Git AI`). Un objet JSON `{ "Nom": "Valeur" }` est aussi accepté dans `settings.json`. |

Les lignes incomplètes (sans `name`, `baseUrl` ou `model`) sont ignorées.

Clés API : la commande `Git AI: Set API Key for Provider` stocke la clé dans le **SecretStorage** de VS Code (chiffré par l'OS). La clé du settings (`apiKey`) n'est utilisée que si aucune clé n'est présente dans le secret storage. Les providers locaux (Ollama, LM Studio) n'ont pas besoin de clé.

### Prompts système personnalisés

- `gitAiReview.prompts.reviewSystem` — prompt système pour la **revue de code**.
- `gitAiReview.prompts.commitSystem` — prompt système pour les **messages de commit**.

Les deux ont une valeur par défaut complète (visible dans les settings) et supportent le placeholder `{language}`, remplacé par `gitAiReview.outputLanguage`.

### Langue des réponses

`gitAiReview.outputLanguage` est un **sélecteur (liste déroulante)** dans l'interface Réglages : English, Français, Español, Deutsch, Italiano, Português, Nederlands, Polski, Türkçe, Русский, Українська, العربية, हिन्दी, 中文, 日本語, 한국어, Tiếng Việt, Indonesian (défaut : `English`). Pour une autre langue, saisissez la valeur librement dans `settings.json`.

Exemple de prompt de revue personnalisé :

```jsonc
{
  "gitAiReview.prompts.reviewSystem": "Tu es reviewer sur une équipe bancaire. Concentre-toi sur la sécurité, la validation des entrées et la gestion d'erreurs. Format: liste de constats avec sévérité. Réponds en {language}."
}
```

### Autres réglages

| Réglage | Défaut | Description |
| --- | --- | --- |
| `gitAiReview.diff.maxChars` | `60000` | Taille max du diff envoyé à l'IA (au-delà : tronqué, et l'IA en est avertie). |
| `gitAiReview.diff.includeUntracked` | `true` | Inclure les fichiers non suivis dans la revue des changements non commités. |
| `gitAiReview.request.timeoutMs` | `60000` | Timeout HTTP des requêtes IA. |

## Développement

```bash
npm install
npm run compile     # bundle esbuild -> dist/extension.js
npm run watch       # recompilation à la volée
npm run typecheck   # tsc --noEmit
```

Tester : ouvrez le dossier dans VS Code et appuyez sur **F5** (« Run Extension ») — une *Extension Development Host* se lance avec l'extension chargée.

Packager un `.vsix` :

```bash
npm install -g @vscode/vsce
vsce package
```

> Pensez à changer `publisher` dans `package.json` avant de publier.

## Dépannage

- **« no AI provider is configured »** — ajoutez au moins un provider dans `gitAiReview.providers`.
- **HTTP 401/403** — clé API manquante ou invalide : lancez `Git AI: Set API Key for Provider`.
- **Timeout** — augmentez `gitAiReview.request.timeoutMs` (les modèles locaux peuvent être lents).
- **« no Git repository found »** — ouvrez un dossier contenant un dépôt Git (`.git`).
