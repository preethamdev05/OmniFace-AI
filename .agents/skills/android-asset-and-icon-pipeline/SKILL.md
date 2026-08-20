---
name: android-asset-and-icon-pipeline
description: Systematic toolchain for converting master brand images into Android launcher mipmap suites (MDPI through XXXHDPI), safe-zone padded adaptive icon layers (432x432), and high-resolution Compose in-app assets via ffmpeg Lanczos filtering.
---

# 🎨 Android Asset & Icon Pipeline

This skill provides deterministic toolchain commands for converting high-resolution brand artwork into complete Android launcher suites, adaptive icons, and in-app graphics.

---

## 📱 1. Standard Launcher Mipmap Generation (MDPI through XXXHDPI)

Given a square master icon image `master_logo.png` (e.g. 1024x1024 or 1254x1254):

```bash
mkdir -p app/src/main/res/mipmap-mdpi \
         app/src/main/res/mipmap-hdpi \
         app/src/main/res/mipmap-xhdpi \
         app/src/main/res/mipmap-xxhdpi \
         app/src/main/res/mipmap-xxxhdpi \
         app/src/main/res/drawable

# Standard Icons
ffmpeg -y -i "master_logo.png" -vf scale=48:48:flags=lanczos app/src/main/res/mipmap-mdpi/ic_launcher.png
ffmpeg -y -i "master_logo.png" -vf scale=72:72:flags=lanczos app/src/main/res/mipmap-hdpi/ic_launcher.png
ffmpeg -y -i "master_logo.png" -vf scale=96:96:flags=lanczos app/src/main/res/mipmap-xhdpi/ic_launcher.png
ffmpeg -y -i "master_logo.png" -vf scale=144:144:flags=lanczos app/src/main/res/mipmap-xxhdpi/ic_launcher.png
ffmpeg -y -i "master_logo.png" -vf scale=192:192:flags=lanczos app/src/main/res/mipmap-xxxhdpi/ic_launcher.png

# Round Icons (for legacy circular launchers)
ffmpeg -y -i "master_logo.png" -vf scale=48:48:flags=lanczos app/src/main/res/mipmap-mdpi/ic_launcher_round.png
ffmpeg -y -i "master_logo.png" -vf scale=72:72:flags=lanczos app/src/main/res/mipmap-hdpi/ic_launcher_round.png
ffmpeg -y -i "master_logo.png" -vf scale=96:96:flags=lanczos app/src/main/res/mipmap-xhdpi/ic_launcher_round.png
ffmpeg -y -i "master_logo.png" -vf scale=144:144:flags=lanczos app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png
ffmpeg -y -i "master_logo.png" -vf scale=192:192:flags=lanczos app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png

# High-Resolution In-App Graphic (512x512)
ffmpeg -y -i "master_logo.png" -vf scale=512:512:flags=lanczos app/src/main/res/drawable/app_logo.png
```

---

## 🎯 2. Android 8.0+ Adaptive Icons (API 26+)

Adaptive icons enforce a **66dp circular safe zone** inside a **108dp viewport** (~72% scale). Scaling the logo to 320x320 and padding it to 432x432 guarantees that device OEM masks (circle, squircle, rounded rectangle) will never clip icon graphics:

```bash
ffmpeg -y -i "master_logo.png" -vf "scale=320:320:flags=lanczos,pad=432:432:(ow-iw)/2:(oh-ih)/2:color=black@0" app/src/main/res/drawable/ic_launcher_foreground.png
```

### Adaptive XML Definitions (`app/src/main/res/mipmap-anydpi-v26/`):

`ic_launcher.xml` & `ic_launcher_round.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

---

## 💎 3. In-App Brand Rendering with Specular Glass Borders

```kotlin
Image(
    painter = painterResource(id = R.drawable.app_logo),
    contentDescription = "App Logo",
    modifier = Modifier
        .size(46.dp)
        .clip(RoundedCornerShape(14.dp))
        .border(1.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(14.dp))
)
```
