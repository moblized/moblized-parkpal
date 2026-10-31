# ParkPal parking server

Your own copy of OpenStreetMap parking (lots, garages, street parking, plus height limits and fees where they're
mapped) and of the places the app's **Explore** buttons show (gas, food, coffee, groceries, hotels, rest areas),
served to the ParkPal app in milliseconds. It replaces the free public Overpass servers, which are often
overloaded and can take 30+ seconds or fail outright.

- Downloads the US (or the regions you choose) from Geofabrik, keeps only those kinds of places, and refreshes
  itself weekly.
- Answers "parking near here" or "gas near here" in about 1 ms.
- Protected by a token, so only your app can use it.
- If the server can't be reached, the app quietly falls back to the public servers.

## What you need

- TrueNAS SCALE with Apps (Docker) enabled.
- About **15 GB free** on a dataset. The US download is ~11 GB; it's deleted after processing and the finished
  parking database is much smaller.
- First start takes roughly **20–40 minutes** for the whole US, mostly the download.

## Set up on TrueNAS

1. **Make a dataset** for the data, for example `apps/parkpal` on your pool. Its path will look like
   `/mnt/tank/apps/parkpal`.
2. Go to **Apps → Discover Apps**, open the **⋮** menu next to *Custom App*, and choose **Install via YAML**.
3. Name it `parkpal-server` and paste in [`docker-compose.yml`](docker-compose.yml). Change two lines:
   - `PARKPAL_TOKEN`: a long random password (you'll paste the same text into the app).
   - `/mnt/POOL/apps/parkpal`: the path of the dataset from step 1.
4. **Save.** Open the app's **Logs**. You'll see it install its tools, then
   `Downloading north-america/us: …`, `Picking out parking and places…`, `Loading places…`, and finally
   `Ready: N parking places and M other places`.
5. **Give it an HTTPS address** the same way as your other apps (reverse proxy, Cloudflare Tunnel, etc.):
   point something like `parkpal.yourdomain.org` at `http://<TrueNAS IP>:8095`.
   To try it at home first, `http://<TrueNAS IP>:8095` works too.
6. **In ParkPal:** Settings → **Parking server** → enter the address and token → **Test connection**.
   You should see "Connected: … parking places".

You can also check it from a browser: `https://parkpal.yourdomain.org/api/status?token=YOUR_TOKEN`.

## Optional: Google places to fill gaps

OpenStreetMap is the main source because it has height limits and fees. Where it has little mapped, the server
can also ask Google, and adds any lots and garages (or, for Explore, gas stations, restaurants and so on)
OpenStreetMap is missing, and fills in names for unnamed lots. In the app these show "From Google Maps". Google
doesn't list height limits, so its parking is marked "check for a height bar".

**Cost:** Google gives **5,000 of these searches free each month** (Nearby Search, "Pro" tier; the server only
asks for name, location, type and address, which keeps it on that cheapest tier). By default the server
**stops at 4,500 a month**, only asks Google when OpenStreetMap has fewer than 8 results, and remembers each
area for 30 days. Each Explore category counts as its own search for an area (gas near home and food near home
are two). For personal use you should stay well under the free amount. The free allowance is per Google billing
account, so other Google Maps projects on the same account share it.

**Get a key:**

1. Go to the [Google Cloud console](https://console.cloud.google.com/), create a project (e.g. "ParkPal"), and
   add a billing account. Google requires a card even for free use.
2. **APIs & Services → Library** → enable **Places API (New)**.
3. **APIs & Services → Credentials → Create credentials → API key.** Edit the key and, under *API restrictions*,
   restrict it to **Places API (New)**. If your home IP address doesn't change, you can also restrict it to that
   IP under *Application restrictions*.
4. **Billing → Budgets & alerts**: create a budget of $1 with email alerts, so you'd hear about any charge.
5. In the TrueNAS app's YAML, set `GOOGLE_PLACES_KEY: "your key"` and redeploy.

**Settings:** `GOOGLE_MODE` is `gaps` (default), `always` (ask Google for every new area, which uses more of
the allowance), or `off`. `GOOGLE_GAP_THRESHOLD` is the "few results" cutoff (default 8), and
`GOOGLE_MONTHLY_LIMIT` is the monthly stop (default 4500). The app's **Test connection** button shows how many
Google searches have been used this month.

## Options

Set these under `environment:` in the YAML.

| Setting | Default | What it does |
| --- | --- | --- |
| `REGIONS` | `north-america/us` | Geofabrik regions, comma separated. Smaller is faster, e.g. `north-america/us/ohio,north-america/us/pennsylvania`. Canada is `north-america/canada`. Names are on [download.geofabrik.de](https://download.geofabrik.de/north-america.html). |
| `REFRESH_DAYS` | `7` | How often to download fresh data. |
| `PARKPAL_TOKEN` | (none) | The password the app must send. Strongly recommended. |
| `PORT` | `8080` | Port inside the container (the YAML maps it to 8095 on TrueNAS). |

Changing `REGIONS` triggers a fresh download on the next start. The server keeps answering from the old data
while it updates.

## Updating

The container downloads the latest `parkpal_server.py` from this repo every time it starts, so restarting the
app on TrueNAS updates it.

## API

- `GET /health` → `ok` (no token, for health checks)
- `GET /api/status` → `{"ready", "places", "pois", "explore", "updated", "regions", "busy", "message", "google"}`
- `GET /api/parking?lat=…&lon=…&radius=…` → Overpass-style JSON (`{"elements": [...]}`), closest first,
  up to 500 places, radius up to 5000 m.
- `GET /api/places?lat=…&lon=…&radius=…&category=…` → the same, for Explore. `category` is one of `gas`,
  `food`, `coffee`, `groceries`, `hotels`, `rest`; radius up to 15000 m (50000 m for `rest`). Each place's tags include
  `parkpal:category`.

After this update the server rebuilds its data once on its next start (to add the Explore places); parking
keeps working from the old data meanwhile.

Send the token as the `X-ParkPal-Token` header or `?token=`.

Parking data © OpenStreetMap contributors (ODbL), via Geofabrik. Optional extra places from Google Maps.
