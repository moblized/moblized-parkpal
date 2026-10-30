# ParkPal parking server

Your own copy of OpenStreetMap parking (lots, garages, street parking, plus height limits and fees where they're
mapped), served to the ParkPal app in milliseconds. It replaces the free public Overpass servers, which are often
overloaded and can take 30+ seconds or fail outright.

- Downloads the US (or the regions you choose) from Geofabrik, keeps only parking, and refreshes itself weekly.
- Answers "parking near here" in about 1 ms.
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
   `Downloading north-america/us: …`, `Picking out parking…`, `Loading parking…`, and finally
   `Ready: N parking places`.
5. **Give it an HTTPS address** the same way as your other apps (reverse proxy, Cloudflare Tunnel, etc.):
   point something like `parkpal.yourdomain.org` at `http://<TrueNAS IP>:8095`.
   To try it at home first, `http://<TrueNAS IP>:8095` works too.
6. **In ParkPal:** Settings → **Parking server** → enter the address and token → **Test connection**.
   You should see "Connected: … parking places".

You can also check it from a browser: `https://parkpal.yourdomain.org/api/status?token=YOUR_TOKEN`.

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
- `GET /api/status` → `{"ready", "places", "updated", "regions", "busy", "message"}`
- `GET /api/parking?lat=…&lon=…&radius=…` → Overpass-style JSON (`{"elements": [...]}`), closest first,
  up to 500 places, radius up to 5000 m.

Send the token as the `X-ParkPal-Token` header or `?token=`.

Parking data © OpenStreetMap contributors (ODbL), via Geofabrik.
