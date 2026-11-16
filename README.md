# TaskMan

A tiny, controller-first "recent apps" card carousel for Android handhelds.
The middle card is always centered and highlighted, the previous and next ones peek
in from both sides — switch with the D-pad, open with A, close with X.

## Why I made this

Android's built-in recents screen is not comfortable with a gamepad. Closing an app or
switching what is in the foreground usually ends up needing a touch on the screen, which
feels clunky — and if you dock your handheld to a TV and play with an external controller,
touch is not an option at all. TaskMan is a small app that fixes exactly that: a list that
matches the system task manager, navigated entirely with buttons.

## How to use

| Input | Action |
| --- | --- |
| `←` `→` | previous / next card |
| `A` (or Enter, D-pad center) | open the selected app |
| `X` | close the selected app (slides up and fades out) |
| `Y` | toggle "tasks only" / "tasks + background apps" |
| `START` | turn the screen off and exit |
| swipe left / right | previous / next, with inertia and snap |
| swipe up | close the selected app (same animation as `X`) |
| tap the middle card | open it |
| tap a side card | scroll to it |
| long press a card | open the system app info page |

Notes:

- The list comes from Shizuku, so it matches the real system task manager. Without
  Shizuku it falls back to Usage Access stats (approximate).
- Closing an app both force-stops it and removes its task from recents.
- TaskMan closes itself as soon as it loses focus, so it never sits in your recents.
- The screen-off shortcut needs Shizuku; if it is not connected the app says so instead
  of pretending it worked.

## Requirements

- Android 6.0+ (minSdk 23), landscape.
- [Shizuku](https://shizuku.rikka.app/) running in shell mode for the real task list,
  closing apps and screen-off. Start it again after every reboot (Shizuku's own
  wireless-debugging flow works).
- Usage Access permission only for the no-Shizuku fallback.

## Tips

**Use iiSU as the frontend.** TaskMan is built to live inside a console-style launcher,
and [iiSU](https://github.com/iisu-network/iiSU) is the one I use and recommend — it is a
visuals-first launcher for handhelds and simply feels great. I studied its visual language
(I personally guided my AI assistant through it, so the credit is honest) and reused it
here, so hopping between iiSU and TaskMan stays immersive.

- iiSU: <https://github.com/iisu-network/iiSU> · <https://iisu.network>

**Remap a button with KeyMapper.** [KeyMapper](https://github.com/keymapperorg/KeyMapper)
lets you bind a spare key (or a long press / chord) so TaskMan launches straight from the
frontend without leaving the controller. Handy when your emulator already uses every button.

- KeyMapper: <https://github.com/keymapperorg/KeyMapper>

## Build

No Gradle needed (the `build.gradle` is only for opening the project in Android Studio).

```bash
./build.sh          # Linux / aarch64 proot
```

```powershell
powershell -File build.ps1   # Windows, needs Android build-tools (e.g. scoop android-clt)
```

The APK lands at `build/TaskMan-debug.apk`.

## Credits

Icon, UI and code are mine; Shizuku API by Rikka is the only runtime dependency.
