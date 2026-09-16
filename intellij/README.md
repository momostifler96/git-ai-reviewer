# Git AI Reviewer — plugin IntelliJ / JetBrains

Version IntelliJ de l'extension [Git AI Reviewer](../README.md) (VS Code). Mêmes fonctionnalités :

- 🔍 **Review Uncommitted Changes (AI)** — revue IA des changements non commités (staged + unstaged + fichiers non suivis), rapport affiché dans la tool window « Git AI Review » ;
- ✉️ **Generate Commit Message (AI)** — message de commit au format Conventional Commits généré depuis les changements stagés (propose tout le non-commité si rien n'est stagé), présenté dans une boîte de dialogue éditable puis copié dans le presse-papier à coller dans le champ de commit ;
- 🔌 **providers OpenAI-compatibles** (OpenAI, Ollama, LM Studio, OpenRouter, Groq, vLLM…) avec un provider **par défaut**, un dédié aux **revues** et un dédié aux **commits** (vide = hérite du défaut) ;
- 📝 **prompts système personnalisables** pour la revue et le commit, avec le placeholder `{language}` (18 langues au sélecteur) ;
- 🔑 clés API stockées dans le **coffre-fort de l'IDE** (PasswordSafe : Keychain macOS / Credential Manager Windows / KeePassXC Linux).

Compatible IntelliJ IDEA, WebStorm, PyCharm, etc. (produits 2024.3 → 2025.2).

## Utilisation

1. **Settings → Tools → Git AI Reviewer** : renseignez le tableau des providers (`name`, `base URL`, `model` ; double-clic pour éditer — température, max tokens, en-têtes HTTP et **clé API** y sont saisis), choisissez les providers par défaut/revue/commit et la langue.
2. Menu **Git** (en bas) → **Review Uncommitted Changes (AI)** ou **Generate Commit Message (AI)**.

## Construction

```bash
./gradlew buildPlugin     # Linux/macOS
gradlew.bat buildPlugin   # Windows
```

Le plugin est produit dans `build/distributions/git-ai-reviewer-intellij-<version>.zip`.

Installation dans l'IDE : **Settings → Plugins → ⚙ → Install Plugin from Disk…** → sélectionner le zip.

Exécuter dans un IDE de test : `./gradlew runIde`.

Publier sur JetBrains Marketplace : `./gradlew publishPlugin` (token à définir dans `gradle.properties` : `intellijPlatformPublishing.token`).

## Détails techniques

- **Stack** : Gradle 8.10 + [IntelliJ Platform Gradle Plugin 2.x](https://plugins.gradle.org/docs/org.jetbrains.intellij.platform) + Kotlin 2.0, SDK IntelliJ IC 2024.3 (`sinceBuild 243`, `untilBuild 252.*`).
- **Dépendances embarquées** : Gson (JSON), flexmark (Markdown → HTML pour la tool window).
- **Git** : invocations directes du CLI `git` (aucune dépendance aux API internes de Git4Idea) — `git diff HEAD` / `git diff --cached` / `git ls-files --others`, fichiers non suivis transformés en pseudo-diff, troncature à `diff.maxChars`.
- **Réglages persistés** dans `~/.config/JetBrains/<IDE>/options/gitAiReview.xml` (composant d'état applicatif), clés API dans le PasswordSafe.
