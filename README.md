# ParkPal

A personal Google Maps alternative built around your truck and your own [Dawarich](https://dawarich.app) location history: search and Explore, turn-by-turn directions, parking that fits, your timeline, and your saved places.

## Features

- **Explore**: Gas, Food, Coffee, Groceries, Hotels and Rest areas buttons under the search bar, like Google Maps. Results show on the map and in a list; each place has Go, Parking nearby, Call, Website, and a bookmark to save it to Dawarich. Gas stations show diesel and truck access when they're mapped.
- **Turn-by-turn directions**: spoken turns (music gets quieter while it talks), a heading-up map that follows you, the next-turn banner, time and distance left, rerouting when you miss a turn, and it keeps going with the screen off. When you drive to a garage it offers to save your spot and walk you to your destination; walking back, it offers to end parking. Prefer Google Maps? Settings → Directions.
- **Timeline**: your Dawarich days on a map, visits numbered with times and drives with distance, plus a day summary. Pick any date.
- **Record location to Dawarich** (Settings → Dawarich → Location history): records in the background like OwnTracks, in Battery saver, Balanced (light while still, detailed while driving) or Precise mode. Points wait on the phone with no signal and upload later.
- **Saved**: your Dawarich places (tap for parking around one, or get directions), starred garages, and parking history.
- **Where to?**: search like Google Maps, with suggestions as you type, recent destinations, or long-press the map to drop a pin. ParkPal then finds parking around your destination on its own: closest walk first, widened automatically if there's little that fits, with "has its own parking" and "close enough to walk" hints. Tap a spot to see the drive route and time, then **Go**. Your saved spot remembers the destination, with a **Walk to** button.
- **Find parking map**: garages, lots and street parking from OpenStreetMap, colored by price and height fit. "Search this area" works anywhere.
- **Truck clearance filter**: set your vehicle height and a safety margin; garages that are too low are flagged or hidden, and covered garages with no listed height say "check the sign".
- **Save my spot**: "I parked here" (GPS) or "Park here" on a garage, plus level/row, a note and a photo. Walking directions back to it, and share your spot.
- **Meter timer**: countdown in the notification shade, a reminder before it ends (5–30 min, your choice) and another when it runs out. Survives phone restarts.
- **Pay hand-off**: one tap opens ParkMobile, SpotHero, PayByPhone or ParkWhiz (or the Play Store if it isn't installed).
- **Home-screen widget**: where you parked, when the meter ends, and a Walk back button.
- **Android Auto**: nearby parking that fits your vehicle on the car screen, plus **Search** for a destination (recents listed) to see parking near it, with Navigate and Park here.
- **Dawarich** (Settings → Dawarich): finished parking stops are added to your Dawarich timeline as "Parked · …" visits, your Dawarich places show up in Where to?, Saved and Android Auto search, and starred garages are saved as Dawarich places. Sending waits for a connection, so stops saved without signal arrive later.
- **Your own server** (optional, see [server/README.md](server/README.md)): a small server on your TrueNAS keeps parking and Explore places for the US (or the regions you pick) and answers in milliseconds, optionally filling gaps from Google.
- **Dark mode** (system, light, dark, pure black). The launcher icon switches to a dark version too.

ParkPal doesn't sell or reserve spots itself; booking and paying still happen in the parking company's own app.

## Install

Every push to `main` builds an APK with GitHub Actions. Open the repo's **Releases** page on your phone, download `ParkPal.apk` from the latest build and open it. New builds install over the old one.

After that, ParkPal checks for new builds each time it opens and offers to download and install them (Settings → About → Check for updates does it on demand). The first time, Android asks you to allow ParkPal to install apps. The release notes in the prompt come from the commit message.

## Android Auto

Sideloaded apps are hidden in Android Auto until you allow them:

1. Open Android Auto settings on your phone.
2. Tap **Version** about 10 times to unlock developer settings.
3. Use the ⋮ menu → **Developer settings** → turn on **Unknown sources**.

## Data

Parking and places © OpenStreetMap contributors (Overpass API, Photon, Nominatim, or your own server). The map uses standard OpenStreetMap tiles; with an optional free CARTO key (Settings → Map style) it uses CARTO's Voyager and Dark Matter styles instead. Directions from the OSRM demo server and FOSSGIS (routing.openstreetmap.de). These are regular car routes: they don't know your truck's height, so watch for low-bridge signs.

## Speed

- The installed APK is an optimized release build (R8 + startup profile).
- Parking results are saved on the phone for a few days, so areas you've seen load instantly; three Overpass servers are tried in parallel when one is slow.
- Map tiles are kept for two weeks, so places you've seen draw instantly. The map opens where you left it. Data can be missing or out of date, so always check posted signs and height bars.
