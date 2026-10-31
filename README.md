# Dawn E-Paper for Android

A native Android reader for Dawn and Jang Lahore e-paper, designed with the Galaxy Tab S5e in mind.

## What it does

- Starts on a dedicated newspaper-selection screen with today's date selected. Changing the date updates Dawn's availability.
- Shows Dawn and Jang Lahore in the newspaper list. Today's Dawn is checked on a persisted 15-minute Android background-job interval and downloaded once available; Jang availability is checked when its row is tapped. Android may defer periodic jobs based on system conditions.
- Opens either paper on a separate reader screen showing the downloaded page image and page selector/navigation controls, not the newspaper website. Jang pages and clickable story areas are fetched as the reader opens them.
- Prefetches pages in front-page, back-page, editorial, then remaining-page order. Opening a page moves it ahead of queued downloads.
- Makes stories tappable directly on either newspaper page. A tapped story opens its saved text or is fetched and saved on demand; Jang story text retains images embedded in the article. No separate story buttons are shown.
- Briefly highlights a tapped story area before opening it. Text stories include an optional “Display as image” action only for supported newspapers (currently Dawn); the Dawn story image is fetched only when requested and then stored with the edition for offline viewing.
- Keeps the current page and zoom position intact while new story hotspots arrive. Opening a page in today's edition starts prefetching its story text and full-size images; background activity is indicated by a spinner beside Dawn.
- Makes the “Opening story…” dialog dismissible with Back, returning to the current paper page.
- Download progress and storage mode are handled internally.
- Notifies when today's Dawn is detected and starts downloading its pages. Previously unsaved historical editions download only the page the user opens, and saved edition metadata avoids another availability request.
- Uses the newspaper websites temporarily to check availability and extract page/story data; full website HTML is not archived.

Downloaded editions live in the app's private storage. Dawn editions use `editions/<date>/`; Jang Lahore editions use `editions/jang/<date>/`. Each stores downloaded page JPEGs and `edition.json` for page metadata, clickable areas, and article text. Dawn full-size story images are stored under `images/`. Jang article text preserves links to any images included on the story page.

## Build

Open this directory in Android Studio and sync the Gradle project, or run `gradle assembleDebug` with an Android SDK installed. The project targets Android 8.0 (API 26) and newer.
