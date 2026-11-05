# EXIF Remover

**Post the photo, not your location.**

A small Android app that removes GPS coordinates, camera details and other hidden data from your photos before you share them. Everything happens on your phone. The app has no internet permission, so your photos never go anywhere.

## Why you need it

Every photo your phone takes carries hidden information, called EXIF metadata, inside the file:

- **Where it was taken.** Exact GPS coordinates, often accurate to a few metres. A photo taken at home gives away your home address.
- **When it was taken**, down to the second.
- **What took it.** Phone make and model, lens, camera settings, and sometimes the owner's name or a serial number.

Some apps strip this when you post. Many don't, especially when a photo is sent as a file, by email, in cloud links or in some chat apps. Anyone who receives the original file can read all of it with free tools.

EXIF Remover takes it out before the photo leaves your phone.

## Download

Get the latest APK from the [Releases](../../releases) page and open it on your phone. Requires Android 11 or newer.

Because the app doesn't come from the Play Store, Android may offer to scan it before installing. Tap **Scan app** and it will install normally.

## How to use it

### The quickest way: share through it

1. In your gallery, pick a photo and tap **Share**.
2. Choose **Strip & share**.
3. The share sheet opens again with a clean copy. Pick the app you were going to send it to.

Your original photo is not changed.

To send a photo **at full quality** in WhatsApp or other chat apps, choose **Strip & share as file** instead. The clean copy goes as a document, so the chat app doesn't compress it.

### Clean photos in the app

1. Open EXIF Remover and tap **Choose photos**.
2. Pick one photo to see everything hidden in it before removing anything, or select several to clean them all at once. The **With location** filter shows only photos that reveal where they were taken.
3. Check the results: tap any photo to see what was removed and what was kept.
4. Tap **Share**, or save the clean photos.

### Choose what to remove

On the home screen, tap the chips under **Remove**:

| Option | What it does |
| --- | --- |
| **Location** | Always removed. |
| **Camera & personal** | Phone model, camera settings, owner name and other details. Turn it off to remove only the location. |
| **Date taken** | Keep it so the photo stays in the right place in your gallery's timeline. |
| **Rotation** | Keep it so photos stay the right way up. |

### Choose how to save

- **Save a copy.** Clean copies go to `Pictures/EXIF Remover`. Your originals stay as they are.
- **Replace.** The original is cleaned in place. With **Keep originals in Trash** on, the original goes to the system Trash, where you can restore it for 30 days.

### Shortcuts

- **Long-press the app icon** to clean your latest photo or latest screenshot in one tap.
- Add the **Clean photos** tile to Quick Settings to open the photo picker from anywhere.

## What it handles

- JPEG, PNG, WebP, HEIC, HEIF and AVIF photos are cleaned without re-compressing the image, so quality is unchanged.
- Other formats, such as GIF and BMP, are converted to JPEG.
- Besides EXIF, it also clears XMP data and manufacturer-specific blocks that can hold the same information.

## Privacy

- No internet permission. Nothing is uploaded, tracked or logged.
- Clean copies made for sharing are deleted automatically.
- Photo access is only used to show your gallery and to read and clean the photos you choose.

## Building from source

Open the project in Android Studio, or run:

```sh
./gradlew assembleDebug
```

Pushing a version tag such as `v1.2.1` makes GitHub Actions build a signed APK and publish it as a release. See [`.github/workflows/release.yml`](.github/workflows/release.yml) for the secrets it needs.
