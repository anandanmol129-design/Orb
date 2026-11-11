<p align="center">
  <img src="assets/orb-logo.svg" width="160" alt="Orb logo">
</p>

<h1 align="center">Orb</h1>

<p align="center"><b>A living, voice-first Android launcher.</b><br>
An expressive orb companion, animated clocks and interactive live wallpapers, built on a fast search core.</p>

---

Orb started as a fork of [Kvaesitso](https://github.com/MM2-0/Kvaesitso) and has since grown into its own launcher. The search engine underneath is still there; the home screen, assistant and visuals are new.

## Features

- **The Orb** – an animated companion on your home screen with squash-and-stretch body language, blinking, gazing eyes and per-state poses for listening, thinking, success, error and music. Tap, double-tap or long-press it.
- **Voice assistant** – talk to the orb to launch apps and run launcher actions, with an on-device intent parser for common commands.
- **Animated clocks** – Simple, Flip, Particles, Sun arc and more. Tap the time for alarms, the date for your calendar.
- **Interactive live wallpapers** – Aurora (reacts to your voice), Light strings, Tesseract, Reaction-diffusion, Fluid ink, Gravity sand, Flow field, Magnetic field, Topographic, Water, Prism, Solar system, Boids, Mandelbrot, Double pendulum, Games – or let Random pick.
- **Feed reader** – a swipeable feed right from the home screen.
- **Fast search** – apps, contacts, calendar, files, locations and more, tuned for speed (cached pipelines, batched queries, baseline profile).

## Installation

Download the latest APK from the [releases page](https://github.com/anandanmol129-design/Orb/releases) (debug builds also live in `apk/`) and install it manually.

## Building

```bash
./gradlew :app:app:assembleDebug
```

## Report issues

Open an issue in the [issue tracker](https://github.com/anandanmol129-design/Orb/issues). Include steps to reproduce and device info (Settings > Debug > Export debug information).

## Credits

Orb is built on [Kvaesitso](https://github.com/MM2-0/Kvaesitso) by MM2-0 and its contributors. Thank you for the excellent foundation.

## License

This software is free software licensed under the GNU General Public License 3.0.

```

Copyright (C) 2021–2026 MM2-0 and the Kvaesitso contributors
Copyright (C) 2026 the Orb contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

The plugin SDK modules (`plugins/sdk` and `core/shared`) are licensed under the Apache License 2.0.
