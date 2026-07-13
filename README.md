# Tiny Grok - Android Client

A lightweight native Android app for chatting with xAI's Grok models.

## Features

- Secure API key entry and storage in Settings
- Three themes: Light, Dark, Tokyo Night
- Chat models: **Grok 4.3** (default) or **Grok 4.5** — selectable in Settings
- Uses xAI Agent Tools / Responses API (`https://api.x.ai/v1/responses`)
- **Live web search**: when Grok is unsure or a question depends on recent/factual information, it automatically uses the `web_search` tool instead of guessing. Source links are appended to answers (tap to open, long-press to copy)
- **UK transit lookups**: prefers official National Rail / TOC sites for live times and disruptions (see [UK transit web sources](#uk-transit-web-sources)); open web for everything else
- **GPS location** (on by default; optional — turn off in Settings): so you can ask things like *“find me transport from my current location to X”* without naming a station. Approximate coordinates/place are attached to the prompt when permission is granted; the model uses them with web search (National Rail, Thameslink, TfL, etc.) to plan from nearest stations/stops. Not required for general chat
- Clean Jetpack Compose UI with MVVM architecture
- Chat with message history (in-memory for now) plus response history screen
- Pinch-to-zoom on the whole chat screen
- Image attach on prompts
- Voice translator (optional, Grok Voice API)
- Debug mode with API request/response logs
- Show estimated cost per query (optional)
- Streaming support planned

## UK transit web sources

When answering rail and transit questions, the app steers `web_search` toward these official sources (implemented in chat instructions). Links are preferred over guessing times or disruptions.

### Why GPS is used

Location is **for journey-style questions that start from “here”**, not for tracking or ads. Typical use:

- *“Find me transport from my current location to King’s Cross”*
- *“Next trains from nearest station to Brighton”*
- *“How do I get from here to the airport?”*

With **Use GPS location** enabled (default) and Android location permission granted, the app adds an approximate position to the model instructions. Combined with the sources below, Grok can resolve nearby stations/stops and search live times. Disable the setting (or deny permission) anytime — chat still works; you just name the origin yourself.

**Accuracy notes:** location uses **platform GPS only** for place naming (no Google Play Services). Strategy is **cache with timeout** (not continuous tracking): optional one-shot warm lookup at app open, then reuse for the configured TTL (**Settings → GPS cache timeout**, default **10 minutes**) without touching the GPS chip; after TTL expires the next send does one fresh session and stops again. A town/postcode is reverse-geocoded only from a GPS fix ≤50 m.

### Why live answers use National Rail (and why they sometimes didn’t)

The app does **not** call National Rail’s APIs directly. It uses xAI’s server-side **`web_search`** tool: the model chooses search queries, xAI runs them, and citations come back.

Earlier builds only said “prefer official sites.” That is a soft hint — Grok could still answer from memory, or search random blogs, and never hit **www.nationalrail.co.uk** / **realtime.nationalrail.co.uk**.

Current behaviour for rail/transit enquiries:

1. Detect train/journey-style prompts (e.g. trains, departures, “from here to…”, station names).
2. **Require** `web_search` before inventing times.
3. **Require first searches** to use `site:nationalrail.co.uk` / `site:realtime.nationalrail.co.uk` (journey planner / live departures).
4. Then allow operator sites (Thameslink, TfL, etc.) and cite URLs.

Limitations (inherent to agent web search, not a local bug):

- Live boards are often **JavaScript-heavy**; the tool may get search snippets or planner pages rather than a perfect real-time board scrape.
- The model still executes the tool on xAI’s side — if search returns weak results, the app will say so and should still link National Rail.
- Turn on **Debug mode** in Settings to see whether `web_search` ran and which citation URLs came back.

### Hubs

| Source | URL |
| --- | --- |
| National Rail | https://www.nationalrail.co.uk |
| Live departure boards | https://realtime.nationalrail.co.uk |
| Network Rail (engineering / line status) | https://www.networkrail.co.uk |

### St Albans and nearby (Herts / Midland Main Line OHLE / Abbey Line)

| Operator / service | Covers | URL |
| --- | --- | --- |
| **Thameslink** | St Albans City, Luton, Bedford, St Pancras, Blackfriars, through-London Thameslink | https://www.thameslinkrailway.com |
| **London Northwestern Railway** | St Albans Abbey, Abbey Line ↔ Watford Junction | https://www.londonnorthwesternrailway.co.uk |
| **Great Northern** | Nearby ECML / Herts (e.g. Welwyn, Hatfield, Hertford, Stevenage) | https://www.greatnorthernrail.com |
| **East Midlands Railway** | Midland Main Line long-distance past the area | https://www.eastmidlandsrailway.co.uk |
| **Intalink** | Herts buses / Abbey ↔ City interchange | https://www.intalink.org.uk |

### London

| Source | Covers | URL |
| --- | --- | --- |
| Transport for London (TfL) | Tube, Overground, Elizabeth line, DLR, buses, trams, status | https://tfl.gov.uk |
| Citymapper | Journey planning / status | https://citymapper.com |

### Other major UK operators

| Operator | URL |
| --- | --- |
| Greater Anglia | https://www.greateranglia.co.uk |
| Southeastern | https://www.southeasternrailway.co.uk |
| Southern | https://www.southernrailway.com |
| Gatwick Express | https://www.gatwickexpress.com |
| South Western Railway | https://www.southwesternrailway.com |
| c2c | https://www.c2c-online.co.uk |
| Chiltern Railways | https://www.chilternrailways.co.uk |
| Great Western Railway | https://www.gwr.com |
| Avanti West Coast | https://www.avantiwestcoast.co.uk |
| LNER | https://www.lner.co.uk |
| CrossCountry | https://www.crosscountrytrains.co.uk |
| TransPennine Express | https://www.tpexpress.co.uk |
| Northern | https://www.northernrailway.co.uk |
| West Midlands Railway | https://www.westmidlandsrailway.co.uk |
| ScotRail | https://www.scotrail.co.uk |
| Transport for Wales | https://tfw.wales |
| Hull Trains (open access) | https://www.hulltrains.co.uk |
| Grand Central (open access) | https://www.grandcentralrail.com |
| Lumo (open access) | https://www.lumo.co.uk |
| Caledonian Sleeper | https://www.sleeper.scot |

Non-UK transit uses the best official operator site or general web search.

## Supported Android versions

| | |
| --- | --- |
| **Minimum** | Android 7.0 (API **24**) |
| **Target** | Android 15 (API **35**) |
| **Compile SDK** | 35 |

Devices and emulators on API 24 and above are supported. Location features need the usual runtime permission (optional; the app works without it).

## Requirements

- Android Studio or Gradle
- Android SDK 24+ (device or emulator)
- xAI API key (get from https://x.ai/)

## Building and Running

### Build the APK
```bash
./build.sh
```

### Build and Run in Emulator
```bash
./build.sh --emulate
```
This will build the debug APK, start the emulator (assumes `Pixel_4_API_34` AVD exists), install and launch the app.

Note: If `./gradlew` is missing, the script falls back to `gradle` command. Run `gradle wrapper` first if needed to generate the wrapper.

## Setup
1. Open in Android Studio
2. Sync Gradle
3. In Settings screen, enter your xAI API key (it will be securely stored)
4. Select theme
5. Start chatting!

## Architecture
Follows the plan in PLAN.md: Hilt DI, Retrofit for API, DataStore for settings, Compose for UI.

## License
MIT - For personal/educational use.

## Notes
- Package renamed from com.aicoder to com.tinygrok.client
- API key never logged or exposed
- Tokyo Night theme uses authentic colors from the popular VSCode theme
