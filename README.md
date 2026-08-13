# MindTranslator

A chat translation mod for Mindustry that translates every chat message in place, showing the
**original text followed by the translation in parentheses**:

```
yemek yiyeceğim (i will eat food)
```

Everything is configurable **in-game** through a gear button in the top-left corner of the screen —
no file editing required.

## Features

- **Works on any server** — the mod is fully client-side: your client translates your outgoing
  chat before sending it and translates incoming messages before showing them. Nothing needs to
  be installed on servers, and nothing runs on them.
- **Player-based settings** (see [Usage](#usage)):
  1. **Language my messages are translated to** — the language your outgoing messages are shown in.
  2. **Language I write in** — your source language (used for accurate detection); `Auto` lets
     Google detect it.
  3. **Language incoming messages are translated to** — the language every incoming message from
     other players is translated into; `Off` shows everything as written.
- **No double translation** — messages that already carry a `TR→EN` style
  translation tag or the mod's invisible translation marker (see
  [Translation protocol](#translation-protocol)) are never translated a second
  time. Already-translated-looking text from other tools is left untouched
  instead of being re-translated (no more doubled `(translation) (translation)`).
- **Passive mode** — one checkbox tells the mod the *server* already translates
  (e.g. foo-client servers): your messages are sent as written and incoming
  messages are shown untouched, so servers and client never translate the same
  message twice.
- **Works alongside Mindustry Tool** — packet interception coexists and the
  installed `Net` proxy mirrors the vanilla `Net` fields, so Tool features that
  inspect the network by reflection keep working (see
  [Compatibility with Mindustry Tool](#compatibility-with-mindustry-tool)).
- **Resilient** — a failed translation never disables the mod permanently;
  translation pauses for 60 seconds and resumes automatically.
- **In-game settings panel** — instant toasts; all settings are written to
  `config.json`.
- **Rate limited** — Google translation requests are throttled (600 ms apart) so chat stays
  responsive.

## Installation

1. Drop `MindTranslator.jar` into Mindustry's **mods** folder:
   - Windows: `%USERPROFILE%\AppData\Roaming\Mindustry\mods`
   - Linux: `~/.local/share/mindustry/mods`
   - macOS: `~/Library/Application Support/Mindustry/mods`
2. Open the main menu → **Mods → MindTranslator → Enable**.
3. Done — nothing is installed on servers; the mod works on any server, even ones without it.

## Usage

Open the settings panel with the **gear button in the top-left corner** of the screen.

### Settings

| Setting | What it does | Default |
|---|---|---|
| **Translation enabled** | Master switch for translation. | `true` |
| **Language my messages are translated to** | The language your outgoing messages are shown in. `Off` disables translation of your messages. | `Off` |
| **Language I write in** | Your source language, used when translating your messages. `Auto` lets Google detect it. | `Auto` |
| **Language incoming messages are translated to** | The language other players' messages are translated into for you. `Off` shows them as written. | `Off` |
| **Min. message length to translate** | Messages shorter than this are not translated. | `3` |
| **Show language tags (TR→EN)** | Show a colored language tag in front of translated messages. | `true` |
| **Server already translates (mod stays passive)** | The server handles all translation (e.g. foo-client servers): your messages are sent as written and incoming messages are shown untouched. | `false` |

**Example:** you write in Turkish, want your messages to appear in English and incoming messages
translated to Russian — set **Language my messages are translated to = English**,
**Language I write in = Turkish**, **Language incoming messages are translated to = Russian**.

All changes take effect immediately and are saved to `config.json`.

## Configuration (config.json)

The file is auto-generated in the mod's config folder and written by the in-game panel; manual
editing is optional. It stores a single local profile — there are no server settings.

```json
{
  "enabled": true,
  "target": "off",
  "source": "auto",
  "othersTarget": "off",
  "minLength": 3,
  "showDetectedLang": true,
  "serverTranslates": false
}
```

| Field | Description | Default |
|---|---|---|
| `enabled` | Translation on/off | `true` |
| `target` | Language your messages are translated to (`off` = none) | `off` |
| `source` | Language you write in (`auto` = detect) | `auto` |
| `othersTarget` | Language incoming messages are translated to (`off` = none) | `off` |
| `minLength` | Minimum message length to translate | `3` |
| `showDetectedLang` | Show language tags (`TR→EN`) | `true` |
| `serverTranslates` | Passive mode — the server handles all translation | `false` |

Old server-style fields (`targetLang`, `writeLang`, `othersTargetLang`, `minMessageLength`)
are still read for compatibility and mapped onto the new keys.

Supported language codes: `tr, en, ru, de, fr, es, it, pt, nl, pl, uk, el, ar, zh, ja, ko, hi,
sv, cs, fi, no, id, th, ro, bg, vi`.

## Translation protocol

This mod marks every translation it produces with an invisible marker so other
translation mods (and future versions of this one) can tell that a message was
already translated, in which language, and by which mod:

```
original [#XXYYZZ][gray] (translation)[]
```

The marker is a Mindustry color code whose 6 hex digits encode three bytes:

| Byte | Meaning |
|---|---|
| `XX` | Mod id of the translator (see registry below) |
| `YY` | Index of the language the text was translated into (table below) |
| `ZZ` | 8-bit FNV-1a checksum of the cleaned original text, i.e. the
        `TranslatorPlugin.clean` output of the original encoded as UTF-8 |

The marker byte is applied and immediately overridden by `[gray]`, so it is
invisible in-game. There is **no space** between the original text and the
marker, and the checksum covers the original exactly as it appears in the
message (an optional `TR→EN` style tag prefix in front is ignored). Readers
verify the checksum and language index before trusting the marker; on failure
the legacy `[gray] (…)[ ]` handling is used.

**Reader rules:**

- Marker's language equals the reader's desired target → show the message as-is
  (no re-translation); the marker stays so further hops also skip it.
- Marker's language differs → the gray translation is discarded and only the
  original text is re-translated into the reader's language, re-marked.
- No marker → legacy path (`TR→EN` tags, `[gray] (…)[]` suffix).

The checksum is a sanity check, not security — a knowledgeable attacker can
forge markers. Mod ids are assigned by coordination between the participating
mods:

| `XX` | Mod |
|---|---|
| `01` | MindTranslator |

Language indices are frozen to the order above (same list, starting at `00` for
`tr`); reordering them breaks every other protocol participant, so never move
entries — only append.

## Compatibility with Mindustry Tool

Both mods replace client-side chat packets. MindTranslator's `Net` proxy only
intercepts the exact vanilla packet classes; packet subclasses created by other
mods (Mindustry Tool's `SendTranslatedMessageCallPacket2`) are passed through
untouched, so both mods can run at the same time: Mindustry Tool handles
incoming chat translation, MindTranslator keeps handling outgoing messages.
Since MindTranslator is fully client-side, no server-side interaction exists.
If Mindustry Tool adopts this marker protocol, outgoing messages translated by
MindTranslator will not be translated a second time by Tool users and vice
versa.

Additionally, the installed proxy mirrors every field of the vanilla `Net`
class (`provider`, `packetProvs`, `packetQueue`, `clientListeners`, ...) with
values copied at install time and kept in sync, so Tool features that inspect
the network stack by reflection (`Reflect.get(Vars.net, ...)`) keep working
unchanged. (Fixed in 2.0.)

## Changelog

### 2.2
- **Vietnamese (vi) added** — now one of 27 supported languages, available in all three
  language pickers.
- **Settings panel adapts to your screen** — language pickers are now multi-column grids
  (2–3 columns depending on window width) instead of long single-column lists, so every
  language fits without endless scrolling; picker heights share the dialog space and the
  dialog is clamped to the screen with DPI-consistent sizes, working on narrow windows and
  phones.

### 2.1
- **Settings dialog no longer closes accidentally on ESC** — it can only be dismissed with the
  Close button or the Android back key; ESC keeps its in-game meaning while the menu is open.
- **Fixed: settings dialog size was far too small or huge depending on UI scale** — sizes were
  being scaled twice (once by the mod, once by the UI system); the dialog now uses single-scaled
  sizes capped to the screen, so it fits on phones and tablets too.
- **Fixed: configuration race between the settings UI and the translator thread** — config fields
  are now volatile, so toggles take effect reliably.
- **Fixed: chat could stall when the translation queue was full** — overflowing messages are now
  sent immediately and untranslated instead of piling up.
- **Fixed: `Net` reflection mirror failed when `Vars.net` was a subclass** — field lookup now
  walks the whole class hierarchy.

### 2.0
- **Passive mode** (`serverTranslates`): one setting makes the mod fully passive
  on servers that already translate (e.g. foo-client servers) — no double
  translation, no double chat lines.
- **Fixed: commands were being translated** — `/commands` are now always sent as
  written; the outgoing translation eligibility check is actually enforced now.
- **Fixed: `(translation) (translation)` duplication** — already-translated text
  that no longer carries the tag/marker (reformatted by servers or produced by
  other tools) is shown as-is instead of being translated a second time.
- **Fixed: translation permanently disabled after one error** — a failed Google
  request now pauses translation for 60 seconds and resumes automatically.
- **Mindustry Tool compatibility** — the `Net` proxy mirrors the vanilla `Net`
  fields, ending `NoSuchFieldException` crashes in tools that reflect over
  `Vars.net`.

### 1.0
- Initial release: player-based settings (target / source / incoming target),
  in-game settings panel, invisible translation marker protocol, tag parsing.

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
  needed); your client requires an internet connection.