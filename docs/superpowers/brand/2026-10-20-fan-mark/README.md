# Granger rebrand package

Drop-in files for `nealt9000/Granger`. Paths below are relative to `app/src/main/`.

## 1. Launcher icon (adaptive + themed)
- `res/drawable/ic_launcher_foreground.xml` — Fan mark, parchment pages + flame ember
- `res/drawable/ic_launcher_monochrome.xml` — Android 13+ themed icon layer
- `res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`
- `res/values/colors_brand.xml` — `ic_launcher_background` (#0E0E0E) + brand colours
- Delete the old `mipmap-*/ic_launcher*.png|webp` rasters, or regenerate them from `brand/granger-icon-dark.svg` with Image Asset Studio for API < 26.
- Keep `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"` in the manifest.

## 2. In-app mark
- `res/drawable/ic_granger_mark.xml` (on dark) and `ic_granger_mark_ink.xml` (on parchment). 48dp default; scale freely.
- `kotlin/app/granger/ui/GrangerLockup.kt` — mark + wordmark + tagline composable.

## 3. Type
- Download OFL TTFs and place in `res/font/` with these exact names:
  - Spectral (https://fonts.google.com/specimen/Spectral): `spectral_regular.ttf`, `spectral_italic.ttf`, `spectral_medium.ttf`, `spectral_semibold.ttf`
  - Courier Prime (https://fonts.google.com/specimen/Courier+Prime): `courier_prime_regular.ttf`, `courier_prime_bold.ttf`
  - Bundle them (the app is offline); do not use downloadable fonts. Both are SIL OFL, compatible with GPL-3.0 distribution.
- `res/font/spectral.xml`, `res/font/courier_prime.xml` — XML font families for non-Compose use (widget, splash).
- `kotlin/app/granger/theme/Type.kt` — `GrangerTypography`, `Wordmark`, `MonoLabel`.
- Pass `typography = GrangerTypography` in `GrangerTheme` (see `GrangerTheme.diff.kt`).

## 4. Splash
- `res/values/themes_splash.xml` — Android 12 SplashScreen theme using the launcher foreground on #0E0E0E.
- Add `androidx.core:core-splashscreen`, set `Theme.Granger.Splash` on the launcher activity, call `installSplashScreen()` before `setContent`.

## 5. Strings
- `res/values/strings_brand.xml` — `app_name`, `brand_tagline`. Replace the old tagline "Civilization, backed up." in README.md and the design spec.

## Palette (unchanged in GrangerTheme.kt)
Flame #E8601C · Amber #E8B66B · Parchment #ECE6DA · Ink #140A04 · Background #0E0E0E

## Mark geometry (for anyone redrawing)
200-unit canvas. Ember: circle r=17 at (100,37). Five capsules 9 wide, rx 4.5, pivot (100,180), rotations -40/-20/0/20/40, heights 110/117/120/117/110. Clear space = ember diameter on all sides.
