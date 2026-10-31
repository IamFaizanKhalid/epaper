# Dawn E-Paper for Android

A native Android reader for Dawn's official e-paper site, designed with the Galaxy Tab S5e in mind.

## What it does

- Starts with today's date selected and checks Dawn's availability for that date. Changing the date updates the availability check.
- Shows Dawn and Jang in the newspaper list; Dawn editions that are available start downloading automatically. Jang is shown as not available until it is implemented.
- Opens Dawn in an offline-first reader: the page image, visible news-point links, tappable story areas, and page selector/navigation controls are shown instead of the newspaper website.
- Prefetches pages in front-page, back-page, editorial, then remaining-page order. Opening a page moves it ahead of queued downloads.
- Saves page JPEGs, hotspot metadata, full-size images for image stories, and extracted article text. A tapped story opens its saved content or is fetched and saved on demand.
- Uses Dawn's website temporarily to check availability and extract page/story data; full website HTML is not archived.

Downloaded editions live in the app's private storage. Each edition contains page JPEGs, an `images/` folder for full-size story images, `edition.json` for page metadata/clickable areas/image IDs/article text, and a completion marker. Partial downloads are not listed as complete offline editions.

## Build

Open this directory in Android Studio and sync the Gradle project, or run `gradle assembleDebug` with an Android SDK installed. The project targets Android 8.0 (API 26) and newer.
