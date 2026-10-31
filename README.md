# Dawn E-Paper for Android

A native Android reader for Dawn's official e-paper site, designed with the Galaxy Tab S5e in mind.

## What it does

- Opens Dawn's live e-paper website in an embedded browser, including its date picker, page names, page navigation, and clickable story areas.
- Checks the latest edition date Dawn publishes against today's date to report whether today's paper is live.
- Downloads the page images using the page IDs discovered from Dawn's own page list.
- Saves each rendered Dawn page as an MHTML archive, retaining its page layout and clickable story regions.
- Follows each page's `StoryText` links to Dawn's `DetailImage.php?StoryImage=…` text view and saves each story as an MHTML text archive instead of downloading a story JPEG.
- Lists downloaded editions, pages, and text stories. Clicking a saved page's story hotspot opens the matching local text archive when available.

Edition files are stored in the app's private storage. A failed or interrupted download is reported and is not listed as a complete offline edition.

## Build

Open this directory in Android Studio and sync the Gradle project, or run `gradle assembleDebug` with an Android SDK installed. The project targets Android 8.0 (API 26) and newer.
