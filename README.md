# Dawn E-Paper for Android

A native Android reader for Dawn's official e-paper site, designed with the Galaxy Tab S5e in mind.

## What it does

- Opens Dawn's live e-paper website in an embedded browser, including its date picker, page names, page navigation, and clickable story areas.
- Checks the latest edition date Dawn publishes against today's date to report whether today's paper is live.
- Downloads the edition's page JPEGs and reads page hotspots to preserve their types, labels, shapes, and clickable coordinates.
- Downloads full-size JPEGs for image hotspots and extracts title, byline, and readable text for text-story hotspots.
- Provides offline page, image, and text-story browsing without archiving full HTML pages.

Downloaded editions live in the app's private storage. Each edition contains page JPEGs, an `images/` folder for full-size story images, and `edition.json` for page metadata, clickable areas, image IDs, and article text. Website HTML is used temporarily to collect content and is not saved. An edition is listed as complete only after its content and metadata have been saved successfully.

## Build

Open this directory in Android Studio and sync the Gradle project, or run `gradle assembleDebug` with an Android SDK installed. The project targets Android 8.0 (API 26) and newer.
