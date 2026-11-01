# Dawn E-Paper for Android

A native Android reader for Dawn and Jang Lahore e-paper, designed with the Galaxy Tab S5e in mind.

## What it does

- Starts on a dedicated newspaper-selection screen with today's date selected. Changing the date updates both newspapers' availability.
- Shows Dawn and Jang Lahore in the newspaper list. Today's availability is checked no earlier than 9:00 AM device-local time; before then, today's edition is shown as unavailable. Historical editions can be checked at any time, and saved editions remain available offline. Dawn and Jang have independent persisted 15-minute Android background jobs and can be fetched concurrently, each with its own WebView, verification retries, and saved progress. Once availability is confirmed, each current-day download scans pages in front-page, back-page, opinions/editorials, then remaining-page order; saves all discovered story text first; and downloads linked image stories, Jang article photos, and supported Dawn article-detail images afterward. This order is used by both background and in-app full-edition downloads. Android may defer jobs based on system conditions.
- Opens either paper on a separate reader screen showing the downloaded page image and page selector/navigation controls. Jang pages and clickable story areas are fetched as the reader opens them. If a Jang scan request is redirected for verification, the app loads the corresponding edition page in its hidden WebView, lets the site's normal verification scripts run, and saves the scan from that page automatically. The background download job uses the same flow; no browser or manual verification step is required.
- Shows a right-aligned offline completion percentage in each newspaper row once any edition content is saved. The percentage includes page scans, story text, tappable image areas, and Jang article photos. Optional Dawn story-detail images, fetched only on request, are excluded. The current day's in-app edition download can be paused and resumed from that row.
- Prefetches pages in front-page, back-page, editorial, then remaining-page order. Opening a page moves it ahead of queued downloads.
- Makes stories and image areas tappable directly on either newspaper page. A tapped story opens its saved text or is fetched and saved on demand; Jang story text retains images embedded in the article, which can be tapped to view enlarged. Jang page images marked with `window.open` are also tappable and cached for offline viewing; older Jang editions refresh their clickable-area metadata to include these image links. No separate story buttons are shown.
- Briefly highlights a tapped story area before opening it. Text stories include an optional “Display as image” action only for supported newspapers (currently Dawn); the Dawn story image is fetched only when requested and then stored with the edition for offline viewing.
- Keeps the current page and zoom position intact while new story hotspots arrive. Opening a page in a partially saved edition prefetches that page's missing story text first, then its tappable page images and Jang article photos; full-edition downloads handle the same content across all pages.
- Makes the “Opening story…” dialog dismissible with Back, returning to the current paper page.
- Download progress and storage mode are handled internally.
- Notifies when a supported newspaper's current-day edition is detected and starts downloading its pages. Previously unsaved historical editions download only the page the user opens, and saved edition metadata avoids another availability request.
- Uses the newspaper websites temporarily to check availability and extract page/story data; full website HTML is not archived.

Android permits cleartext traffic only to `e.jang.com.pk` because Jang's e-paper flow uses HTTP; all other hosts retain the platform's default cleartext policy.

Downloaded editions live in the app's private storage. Dawn editions use `editions/<date>/`; Jang Lahore editions use `editions/jang/<date>/`. Each stores downloaded page images (Dawn JPEG; Jang JPEG or PNG) and `edition.json` for page metadata, clickable areas, and article text. Full-size images are stored under `images/`, including Jang page images opened from clickable areas. Jang article photos are stored under `article-images/` and displayed from local storage when available. Jang story text uses the bundled Ali Nastaliq font from `app/src/main/assets/fonts/`.

## Paper architecture

`Paper` defines provider operations and capabilities; `DawnPaper` and `JangLahorePaper` implement the source-specific URLs, extraction scripts, parsing, edition directories, and background-job registration. `Papers.all` is the registry used to populate the home-screen list, start availability checks, route editions, and schedule independent background jobs. Saved editions include a paper ID; old metadata without one is migrated from its legacy Jang flag.

`MainActivity` coordinates user actions and WebViews. `PaperViewer` renders scanned pages, interactive hotspots, loading states, and story views while querying paper capabilities for optional image actions. Dawn and Jang still have some specialized reader interactions (for example, Jang verification and inline-photo routes), and the shared edition image model retains provider-specific file validation. To add a paper, implement `Paper`, register it in `Papers.all`, assign its background-job registration and Android job service, and add its `NewspaperId`; provider-specific model validation or reader interactions may also need extending when its formats differ.

## Build

Open this directory in Android Studio and sync the Gradle project, or run `gradle assembleDebug` with an Android SDK installed. The project targets Android 8.0 (API 26) and newer.
