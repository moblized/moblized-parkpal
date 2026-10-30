# ParkPal

A personal Android parking app: find parking that fits your truck, save where you parked, and get reminded before the meter runs out.

## Features

- **Where to?**: search like Google Maps, with suggestions as you type, recent destinations, or long-press the map to drop a pin. ParkPal then finds parking around your destination on its own: closest walk first, widened automatically if there's little that fits, with "has its own parking" and "close enough to walk" hints. Tap a spot to see the drive route and time, then **Go** to navigate. Your saved spot remembers the destination, with a **Walk to** button.
- **Find parking map**: garages, lots and street parking from OpenStreetMap, colored by price and height fit. "Search this area" works anywhere.
- **Truck clearance filter**: set your vehicle height and a safety margin; garages that are too low are flagged or hidden, and covered garages with no listed height say "check the sign".
- **Save my spot**: "I parked here" (GPS) or "Park here" on a garage, plus level/row, a note and a photo. Walk-back directions through Google Maps, and share your spot.
- **Meter timer**: countdown in the notification shade, a reminder before it ends (5–30 min, your choice) and another when it runs out. Survives phone restarts.
- **Pay hand-off**: one tap opens ParkMobile, SpotHero, PayByPhone or ParkWhiz (or the Play Store if it isn't installed).
- **Home-screen widget**: where you parked, when the meter ends, and a Walk back button.
- **Android Auto**: nearby parking that fits your vehicle on the car screen, plus **Search** for a destination (recents listed) to see parking near it, with Navigate and Park here.
- **Favorites and history**.
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

Parking data © OpenStreetMap contributors (Overpass API, Photon, Nominatim). The map uses standard OpenStreetMap tiles; with an optional free CARTO key (Settings → Map style) it uses CARTO's Voyager and Dark Matter styles instead. Driving routes from the OSRM demo server.

## Speed

- The installed APK is an optimized release build (R8 + startup profile).
- Parking results are saved on the phone for a few days, so areas you've seen load instantly; three Overpass servers are tried in parallel when one is slow.
- Map tiles are kept for two weeks, so places you've seen draw instantly. The map opens where you left it. Data can be missing or out of date, so always check posted signs and height bars.
