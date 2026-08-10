# MindTranslator

A chat translation mod for Mindustry that translates every chat message in place, showing the
**original text followed by the translation in parentheses**:

```
yemek yiyeceğim (i will eat food)
```

Everything is configurable **in-game** through a gear button in the top-left corner of the screen —
no file editing required.

## Features

- **Works on any server** — on servers without the mod, your client translates your outgoing chat
  before sending it and translates incoming messages before showing them. On servers with the mod,
  the same experience works for every player.
- **Three personal language settings** (see [Usage](#usage)):
  1. **Language my messages are translated to** — the language your outgoing messages are shown in.
  2. **Language I write in** — your source language (used for accurate detection); `Auto` lets
     Google detect it.
  3. **Language incoming messages are translated to** — the language every incoming message from
     other players is translated into; `Off` shows everything as written.
- **No double translation** — messages that already carry a `TR→EN` style translation tag are
  never translated a second time.
- **Per-player settings, synced** — personal settings are sent to the server over the mod's binary
  channel (when the server runs the mod) and are applied client-side on any other server.
- **In-game settings panel** — live previews and instant toasts; all settings are written to
  `config.json`.
- **Rate limited** — Google translation requests are throttled (600 ms apart) so chat stays
  responsive.
- **Admin-only server defaults** — global fallback languages are only editable by the host/admin.

## Installation

1. Drop `MindTranslator.jar` into Mindustry's **mods** folder:
   - Windows: `%USERPROFILE%\AppData\Roaming\Mindustry\mods`
   - Linux: `~/.local/share/mindustry/mods`
   - macOS: `~/Library/Application Support/Mindustry/mods`
2. Open the main menu → **Mods → MindTranslator → Enable**.
3. To translate chat for everyone on your server, also place the jar into the server's `config/mods`
   folder. Clients with the mod keep working on any server — even ones without it.

## Usage

Open the settings panel with the **gear button in the top-left corner** of the screen.

### Personal Settings (every player)

| Setting | What it does | Default |
|---|---|---|
| **Language my messages are translated to** | The language your outgoing messages are shown in. `Off` disables translation of your messages. | `Off` |
| **Language I write in** | Your source language, used when translating your messages. `Auto` lets Google detect it. | `Auto` |
| **Language incoming messages are translated to** | The language other players' messages are translated into for you. `Off` shows them as written. | `Off` |
| **Min. message length to translate** | Messages shorter than this are not translated; `0` uses the server default. | `0` |

**Example:** you write in Turkish, want your messages to appear in English and incoming messages
translated to Russian — set **1. Language my messages are translated to = English**,
**2. Language I write in = Turkish**, **3. Language incoming messages are translated to = Russian**.

### Server Settings (host / admin only)

| Setting | What it does | Default |
|---|---|---|
| **Translation enabled** | Master switch for translation. | `true` |
| **Default language players' messages are translated to** | Fallback target for players without a personal setting. | `English` |
| **Default language players write in** | Fallback source language; `Auto` = automatic detection. | `Auto` |
| **Default language incoming messages are translated to** | Fallback for players without a personal incoming setting. `Off` = no translation. | `Off` |
| **Show language tags (TR→EN)** | Show a colored language tag in front of translated messages. | `true` |
| **Min. message length** | Messages shorter than this are not translated. | `3` |

All changes take effect immediately and are saved to `config.json`.

## Configuration (config.json)

The file is auto-generated in the mod's config folder and written by the in-game panel; manual
editing is optional.

```json
{
  "enabled": true,
  "targetLang": "en",
  "othersTargetLang": "off",
  "writeLang": "auto",
  "minMessageLength": 3,
  "showDetectedLang": true,
  "players": {
    "player-uuid": { "disabled": false, "target": "tr", "othersTarget": "ru", "source": "tr", "minLength": 0 }
  }
}
```

| Field | Description | Default |
|---|---|---|
| `enabled` | Translation on/off | `true` |
| `targetLang` | Default language players' messages are translated to | `en` |
| `othersTargetLang` | Default language incoming messages are translated to (`off` = none) | `off` |
| `writeLang` | Default source language (`auto` = detect) | `auto` |
| `minMessageLength` | Minimum message length to translate | `3` |
| `showDetectedLang` | Show language tags (`TR→EN`) | `true` |
| `players` | Per-player settings (uuid → settings) | `{}` |

Supported language codes: `tr, en, ru, de, fr, es, it, pt, nl, pl, uk, el, ar, zh, ja, ko, hi,
sv, cs, fi, no, id, th, ro, bg`.

## Building

Requirements: **Java 17**.

```bash
./gradlew jar
```

Output: `build/libs/MindTranslatorDesktop.jar` — desktop/PC testing only, it will **not** work on Android
(mobile needs the dexed version, see below).

## Building with GitHub Actions

This repository is set up with GitHub Actions CI to automatically build the mod on every commit.
To get a jar that works on **every platform** (PC + Android):

1. Push to the repository — the "Build Mod" workflow runs automatically.
2. Open the **Actions** tab on your repository page and select the most recent run in the list.
   If it completed successfully, there is a download link under the **Artifacts** section.
3. Download the artifact (named after the repository) and import the `MindTranslator.jar`
   contained within into Mindustry — this version works both on Android and Desktop.

### Building locally with Android support

1. Download the Android SDK, unzip it and set the `ANDROID_HOME` environment variable to its location.
2. Install a recent platform (e.g. API level 30+) — the build picks the newest `android.jar` available.
3. Add a `build-tools` folder to your PATH (e.g. `$ANDROID_HOME/build-tools/34.0.0`) so `d8` is available.
4. Run `./gradlew deploy`. If everything is set up correctly, this creates `MindTranslator.jar`
   in the `build/libs` directory that runs on both Android and desktop.

## Dependencies

- [Mindustry v159.7](https://github.com/Anuken/Mindustry/releases) — `compileOnly`
- Translation is provided by Google Translate's free `translate_a/single` endpoint (no API key
  needed); the host/server requires an internet connection.