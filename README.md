# Dawn E-Paper for Android

A native Android reader for Dawn and Jang Lahore e-paper, designed with the Galaxy Tab S5e in mind.

## What it does

- Starts on a dedicated newspaper-selection screen with today's date selected. Changing the date updates both newspapers' availability.
- Shows Dawn and Jang Lahore in the newspaper list. Today's availability is checked no earlier than 9:00 AM device-local time; before then, today's edition is shown as unavailable. Historical editions can be checked at any time, and saved editions remain available offline. Every registered newspaper is checked on a persisted 15-minute Android background-job interval. Once availability is confirmed, current-day downloads scan pages in front-page, back-page, opinions/editorials, then remaining-page order; save all discovered story text first; and download linked image stories, Jang article photos, and supported Dawn article-detail images afterward. This order is used by both background and in-app full-edition downloads. Saved progress lets later jobs resume without rechecking availability; Android may defer jobs based on system conditions.
- Opens either paper on a separate reader screen showing the downloaded page image and page selector/navigation controls, not the newspaper website. Jang pages and clickable story areas are fetched as the reader opens them.
- Prefetches pages in front-page, back-page, editorial, then remaining-page order. Opening a page moves it ahead of queued downloads.
- Makes stories and image areas tappable directly on either newspaper page. A tapped story opens its saved text or is fetched and saved on demand; Jang story text retains images embedded in the article, which can be tapped to view enlarged. Jang page images marked with `window.open` are also tappable and cached for offline viewing; older Jang editions refresh their clickable-area metadata to include these image links. No separate story buttons are shown.
- Briefly highlights a tapped story area before opening it. Text stories include an optional “Display as image” action only for supported newspapers (currently Dawn); the Dawn story image is fetched only when requested and then stored with the edition for offline viewing.
- Keeps the current page and zoom position intact while new story hotspots arrive. Opening a page in today's edition starts prefetching its story text and full-size images; background activity is indicated by a spinner beside Dawn.
- Makes the “Opening story…” dialog dismissible with Back, returning to the current paper page.
- Download progress and storage mode are handled internally.
- Notifies when a supported newspaper's current-day edition is detected and starts downloading its pages. Previously unsaved historical editions download only the page the user opens, and saved edition metadata avoids another availability request.
- Uses the newspaper websites temporarily to check availability and extract page/story data; full website HTML is not archived.

Downloaded editions live in the app's private storage. Dawn editions use `editions/<date>/`; Jang Lahore editions use `editions/jang/<date>/`. Each stores downloaded page images (Dawn JPEG; Jang JPEG or PNG) and `edition.json` for page metadata, clickable areas, and article text. Full-size images are stored under `images/`, including Jang page images opened from clickable areas. Jang article photos are stored under `article-images/` and displayed from local storage when available.

## Newspaper sources

`NewspaperFetcher` keeps source-specific edition paths, availability/page/article URLs, page URL matching, hotspot extraction/parsing, offline origins, and image-detail capability in registered Dawn and Jang Lahore fetchers. Both sources use the shared `NewspaperEdition`, `NewspaperPage`, `NewspaperHotspot`, and `NewspaperArticle` models in `NewspaperEdition.kt`; there is intentionally no separate Jang edition model because an edition's page, story, and hotspot data has the same shared shape.

The shared reader uses the registry for page/story loading and clickable-area processing. The periodic background job iterates all registered fetchers for availability discovery, notification, and current-day edition downloads. Each fetcher supplies its own availability-page discovery and parsing; the Jang fetcher also signals request verification so the job can wait and retry. Edition metadata remains backward-compatible with previously saved JSON. Image downloading and metadata validation still include source-specific behavior in the shared edition model. Availability discovery in `MainActivity` and UI entries remain source-specific, so adding another source still requires integrating those app-open availability and UI paths.

## Build

Open this directory in Android Studio and sync the Gradle project, or run `gradle assembleDebug` with an Android SDK installed. The project targets Android 8.0 (API 26) and newer.
