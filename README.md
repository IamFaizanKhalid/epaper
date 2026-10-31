# Dawn E-Paper for Android

A native Android reader for Dawn's official e-paper site, designed with the Galaxy Tab S5e in mind.

## What it does

- Starts on a dedicated newspaper-selection screen with today's date selected. Changing the date updates Dawn's availability.
- Shows Dawn and Jang in the newspaper list; available Dawn editions are prepared automatically in the background. Jang is shown as not available until it is implemented.
- Opens Dawn on a separate reader screen showing the paper image and page selector/navigation controls, not the newspaper website.
- Prefetches pages in front-page, back-page, editorial, then remaining-page order. Opening a page moves it ahead of queued downloads.
- Makes stories tappable directly on the newspaper page. A tapped story opens its saved content or is fetched and saved on demand; no separate story buttons are shown.
- Refreshes the current paper page as page images or hotspot metadata arrive. Download progress and storage mode are handled internally.
- Uses Dawn's website temporarily to check availability and extract page/story data; full website HTML is not archived.

Downloaded editions live in the app's private storage. Each edition contains page JPEGs, an `images/` folder for full-size story images, `edition.json` for page metadata/clickable areas/image IDs/article text, and a completion marker. Partial downloads are not listed as complete offline editions.

## Build

Open this directory in Android Studio and sync the Gradle project, or run `gradle assembleDebug` with an Android SDK installed. The project targets Android 8.0 (API 26) and newer.
