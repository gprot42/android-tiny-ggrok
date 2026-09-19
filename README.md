# Tiny Grok - Android Client

A lightweight native Android app for chatting with xAI's Grok models.

<p align="center">
  <img src="docs/screenshot.png" alt="Tiny Ggrok chat" width="280">
</p>

## Features

- Secure API key entry and storage in Settings
- **Credits & usage** screen (chat top bar **Credits**): live prepaid API balance, postpaid limits, model rate quotas via Management API; SuperGrok plan reference (consumer quotas are not exposed by API)
- Three themes: Light, Dark, Tokyo Night
- Chat models: **Grok 4.6** (default) or **Grok 4.5** (backup) — only these two, selectable in Settings; 4.5 is used automatically if 4.6 is unavailable
- Uses xAI Agent Tools / Responses API (`https://api.x.ai/v1/responses`)
- **Live web search**: when Grok is unsure or a question depends on recent/factual information, it automatically uses the `web_search` tool instead of guessing. Source links are appended to answers (tap to open, long-press to copy)
- **UK transit lookups**: prefers official National Rail / TOC sites for live times and disruptions (see [UK transit web sources](#uk-transit-web-sources)); open web for everything else
- **GPS location** (on by default; optional — turn off in Settings): so you can ask things like *“find me transport from my current location to X”* without naming a station. Approximate coordinates/place are attached to the prompt when permission is granted; the model uses them with web search (National Rail, Thameslink, TfL, etc.) to plan from nearest stations/stops. Not required for general chat
- Clean Jetpack Compose UI with MVVM architecture
- Chat with message history (in-memory for now) plus response history screen
- **Share to Tiny Ggrok**: from any app, share text, links, or images into the chat prompt (also in the text-selection menu as **Ask Tiny Ggrok**). Share a Grok reply or the whole conversation back out via the system share sheet
- Pinch-to-zoom on the whole chat screen
- Image attach on prompts
- Voice translator (optional, Grok Voice API)
- Debug mode with API request/response logs
- Show estimated cost per query (optional)
- **Streaming** Responses API (SSE) for chat + `web_search`, with HTTP/2 pings, so long reasoning/search does not hit “Timed out contacting api.x.ai”
- **Network resilience** (see [Network resilience](#network-resilience)): DNS-over-HTTPS fallback when the network's resolver can't find `api.x.ai`, IPv4-first connects, short connect timeout with up to 3 attempts, and an instant “offline” message instead of a long stall
- **Faster sends**: the TLS connection to `api.x.ai` is opened when the app starts / you begin typing, GPS waits at most 3 s at send time (falls back to the cached fix), and chat history sent as context is capped by size
- **Document scanner, no Google Play services**: tap the scan icon beside the prompt box, photograph a page with your phone's own camera app, and the page is found, squared up and attached. Grok's vision locates the page; the app then tightens the corners onto the real paper edges on-device and flattens it with Android's built-in perspective transform. Corners stay draggable, and **Snap** re-aligns them to the nearest paper edge. See [Document scanner](#document-scanner)
- **Never stuck waiting**: **Stop** cancels a reply that is taking too long, typing while a reply arrives queues your next prompt (it sends itself when the current one lands), and **Clear** also stops anything in flight
- **Live replies**: the answer streams onto the screen as Grok writes it, with a **Searching the web** status while it looks things up, instead of a motionless indicator until the whole reply lands
- **Reasoning effort tuned per question**: ordinary questions use `low` effort (the API default is `high`, which spends tens of seconds thinking before the first token); rail/transit questions keep `high`. Agentic tool turns are capped so a vague question cannot loop through searches indefinitely

## Document scanner

Built to work without Google Play services, ML Kit or OpenCV.

1. **Capture** uses a plain camera intent, so any camera app works (including on de-Googled phones) and Tiny Ggrok needs no camera permission.
2. **Find the page.** A reduced copy of the photo (about 1024 px) goes to Grok, which returns the four corners. This is the part classic edge detection is bad at: telling a page from a cluttered desk.
3. **Make it straight.** A vision model is only accurate to a percent or two, which shows up as a tilted scan. So the app then walks along each rough edge on-device, finds the real paper boundary at dozens of points, fits a straight line through them (ignoring outliers such as printed lines), and uses the line intersections as the corners.
4. **Flatten.** Android's own four-point perspective transform maps the page to an upright rectangle, up to 2048 px on the long side so small print stays legible.

Once the page is aligned there are two places it can go. **To prompt** attaches it to your message to Grok. **Share** sends the straightened JPEG through Android's share sheet to any app that accepts an image, such as Signal, Telegram or your email app (the file name becomes the email subject). Share leaves the scanner open, so one scan can go to another app and into the prompt without scanning twice.

You can drag any corner, tap **Snap** to pull the current corners onto the nearest paper edges, or **Auto** to ask Grok again. If there is no API key or no network, steps 1, 3 and 4 still work with hand-placed corners.

Privacy and cost: finding the page sends that one reduced photo to xAI, billed like any small image prompt. Everything else happens on the phone.

## Network resilience

“Can't reach api.x.ai” on Android is almost always DNS or a dead route, not xAI being down. The app now handles the common cases itself:

| Failure | What the app does |
| --- | --- |
| System DNS returns nothing (carrier/hotel Wi-Fi resolver, Private DNS misconfigured, VPN hijacking port 53) | Retries the lookup over **DNS-over-HTTPS** (Cloudflare `1.1.1.1`, then Google `8.8.8.8`, bootstrapped by IP so they work without port-53 DNS). If both fail, reuses the last address set that worked in this session |
| IPv6 route advertised but blackholed (common on mobile data) | Resolved addresses are ordered **IPv4 first**; connect timeout is 12 s instead of 60 s so a dead address is skipped quickly |
| Connect/TLS reset, HTTP/2 stream reset before any data, `408/429/5xx/529` | Up to **3 attempts** with 0.5 s → 1.5 s back-off (honours `Retry-After` up to 10 s). A reply that already started streaming is never retried, so nothing is double-billed |
| No active network | Fails immediately with “No internet connection” — no 60 s hang |
| Model id rejected | Falls back from Grok 4.6 to Grok 4.5 (unchanged) |

Turn on **Debug mode** in Settings to see each retry (`RETRY 2/3 in 500ms …`) and the reason in the log screen.

If it still fails: toggle Wi-Fi ↔ mobile data, check **Settings → Network → Private DNS** on the phone, or disable any VPN/ad-blocker that filters DNS.

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
- xAI **API key** with prepaid **API credits** from [console.x.ai](https://console.x.ai/)

### SuperGrok vs API credits (common confusion)

**Tiny Grok is not the official Grok app.** It talks to `https://api.x.ai` with the API key you paste in Settings.

| Product | Where | What it pays for |
| --- | --- | --- |
| **SuperGrok / SuperGrok Heavy** | grok.com / X apps | Consumer chat limits on those apps |
| **xAI API credits** | [console.x.ai → Billing](https://console.x.ai/team/default/billing) | Every request this app makes (text, images, web search, voice, etc.) |

A SuperGrok Heavy subscription **does not** fund this app. If you see “out of credits” after attaching photos, top up **API** credits (or enable auto top-up) on the console billing page. Image prompts cost more tokens than text-only ones, so a low balance often fails first when you attach photos.

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

### Credits & usage (optional)

To show live **API prepaid balance** and rate quotas:

1. In [console.x.ai](https://console.x.ai) → **Settings → Management Keys**, create a management key (separate from the chat API key).
2. Paste it under **Settings → API credits / Management** (Team ID is optional; the app auto-detects it).
3. Open **Credits** on the chat top bar (or **Credits & usage** in Settings) and tap **Refresh**.

**SuperGrok Heavy** utilisation cannot be read programmatically — mark your consumer plan on that screen for reference, and check remaining consumer limits in the official Grok app / [grok.com](https://grok.com).

## Architecture
Follows the plan in PLAN.md: Hilt DI, Retrofit for API, DataStore for settings, Compose for UI.

## License
MIT - For personal/educational use.

## Notes
- Package renamed from com.aicoder to com.tinygrok.client
- API key never logged or exposed
- Tokyo Night theme uses authentic colors from the popular VSCode theme
