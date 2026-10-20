# Album

A simple, lightweight Android gallery app, forked from
[Koitharu/Album](https://github.com/Koitharu/Album), which was inspired by the
abandoned Sony® Album app. This fork keeps the original's goals (offline, fast,
no bloat) and adds a few changes for my own daily use.

## Features

- Lightweight and fast, with **no internet permission**
- Modern Material You UI
- All media in one place, plus folder view
- Hidden media, favorites, and a recycle bin (if supported by the OS)
- Built-in image editor and video trimmer, with an option to use an external
  editor instead
- Wallpaper cropper and image picker

## What's different in this fork

- **Video trimmer:** a simple in-app editor that cuts a video to a chosen start
  and end and saves it as a new file. It does not re-encode, so saving is fast
  and the quality is unchanged. The original is never modified
- **Sort media by** date captured (default) or date added, with month headers
  following the selected sort
- **Always-visible compact top bar**; tap it, or the Album tab, to scroll back
  to the top
- **Viewer gestures:** swipe down to close, swipe up to open Information
- **Information sheet:** file size, file path (tap to open its folder, long-press
  to copy), and location with a tap-to-open maps link; the histogram was removed
- **Edit button for videos**, following the "Use external editor" setting: on
  shows a chooser (other apps or the built-in trimmer), off opens the trimmer
  directly
- **Search** matches file names only
- **Recycle bin count** updates after deleting, restoring, or emptying
- **Settings:** the three-dot button opens Settings directly, and the main-screen
  slideshow is off by default

## Permissions

| Permission | Why |
| --- | --- |
| Media access (images and videos) | To show your photos and videos |
| `ACCESS_MEDIA_LOCATION` | Android 10+ hides GPS tags in photos unless this is granted. It only reads location already stored in your own files |
| Storage write access (Android 12 and older only) | To save edited photos and trimmed videos as new files |

The app declares **no internet permission** and has no analytics, ads, or cloud sync.

## Build from source

1. Install the latest stable Android Studio (the project uses Android Gradle
   Plugin 9.4.1, which older versions can't open).
2. Clone the repo and open the root folder (the one containing
   `settings.gradle.kts`).
3. Let Gradle sync, then run the `app` configuration on a device, or build from
   the terminal with `./gradlew assembleDebug`.

Debug builds are noticeably slower than release builds, especially when
scrolling, so judge performance on a release build.

This fork uses the package ID `io.github.farhun.album`, so it installs
alongside the original app and does not replace it.

## Known limitations

- The video trimmer's start point snaps to a keyframe, so a clip may begin
  slightly earlier than the exact time you picked. Frame-accurate cuts would
  need re-encoding, which this app avoids.
- Sorting by date captured builds a sorted index each time an album opens, so
  very large albums may take a moment to load the first time.
- Opening a file's folder depends on the phone's file manager. If none can open
  it, the path is copied instead.
- Name search is case-insensitive for plain letters, but accented letters may
  not match across upper and lower case.

## Credits and license

Album was created by [Koitharu](https://github.com/Koitharu/Album). This fork is
maintained by [farhun](https://github.com/farhun).

Album is free software: you can use, study, share, and improve it. You can
redistribute and modify it under the terms of the
[GNU General Public License](https://www.gnu.org/licenses/gpl.html), version 3
or (at your option) any later version. See [LICENSE](LICENSE).
