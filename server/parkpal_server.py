#!/usr/bin/env python3
"""
ParkPal parking server.

Keeps a copy of OpenStreetMap parking (lots, garages, street parking, with height limits and fees) and the
places the app's Explore tab shows (gas, food, coffee, groceries, hotels, rest areas) for the regions you
choose, and serves them to the ParkPal app in milliseconds, so the app doesn't depend on the overloaded
public Overpass servers.

It downloads region extracts from Geofabrik, keeps only those kinds of places with `osmium tags-filter`,
works out a point for each with pyosmium, and stores everything in SQLite with R-tree indexes. It refreshes
on its own every REFRESH_DAYS.

Endpoints:
  GET /health                              -> "ok" (no token needed, for health checks)
  GET /api/status                          -> what's loaded, when it was updated, what it's doing now
  GET /api/parking?lat=..&lon=..&radius=.. -> Overpass-style JSON ({"elements": [...]}) closest first
  GET /api/places?lat=..&lon=..&radius=..&category=gas|food|coffee|groceries|hotels|rest
                                           -> the same format, for Explore (radius up to 15 km; 50 km for rest)

Settings (environment variables):
  REGIONS        Geofabrik regions, comma separated (default "north-america/us").
                 Examples: "north-america/us/ohio,north-america/us/pennsylvania", "north-america/canada".
                 A path to a local .osm.pbf/.osm file also works.
  REFRESH_DAYS   How often to download fresh data (default 7).
  PARKPAL_TOKEN  If set, the app must send this token (header X-ParkPal-Token or ?token=).
  PORT           Port to listen on (default 8080).
  DATA_DIR       Where to keep data (default /data).

Optional Google places (fills gaps where OpenStreetMap has little mapped, for parking and Explore):
  GOOGLE_PLACES_KEY     A Google Maps Platform key with "Places API (New)" enabled.
  GOOGLE_MODE           "gaps" (default: only ask Google when OpenStreetMap has few results),
                        "always", or "off".
  GOOGLE_GAP_THRESHOLD  "Few results" means fewer than this many (default 8).
  GOOGLE_MONTHLY_LIMIT  Stop asking Google after this many searches a month (default 4500; Google's free
                        allowance for this kind of search is 5,000 a month).
  Google results are cached for 30 days, so repeat visits to an area don't count again.

Run once without serving (for testing):  python parkpal_server.py --build-once
"""

import gzip
import hmac
import json
import logging
import math
import os
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

DATA_DIR = os.environ.get("DATA_DIR", "/data")
REGIONS = [r.strip() for r in os.environ.get("REGIONS", "north-america/us").split(",") if r.strip()]
REFRESH_DAYS = float(os.environ.get("REFRESH_DAYS", "7"))
TOKEN = os.environ.get("PARKPAL_TOKEN", "").strip()
PORT = int(os.environ.get("PORT", "8080"))
GEOFABRIK = os.environ.get("GEOFABRIK_URL", "https://download.geofabrik.de").rstrip("/")

GOOGLE_KEY = os.environ.get("GOOGLE_PLACES_KEY", "").strip()
GOOGLE_MODE = os.environ.get("GOOGLE_MODE", "gaps").strip().lower()
GOOGLE_GAP_THRESHOLD = int(os.environ.get("GOOGLE_GAP_THRESHOLD", "8"))
GOOGLE_MONTHLY_LIMIT = int(os.environ.get("GOOGLE_MONTHLY_LIMIT", "4500"))

DB_PATH = os.path.join(DATA_DIR, "parking.db")
GOOGLE_DB = os.path.join(DATA_DIR, "google.db")
WORK_DIR = os.path.join(DATA_DIR, "work")
MAX_RADIUS_M = 5000
MAX_POI_RADIUS_M = 15000
# Rest areas are far apart, so they can be looked for further out.
POI_MAX_RADIUS_M = {"rest": 50000}
MAX_RESULTS = 500
# Bump when the database layout changes; older databases are rebuilt automatically.
SCHEMA_VERSION = "2"
KEEP_TAGS = (
    "name", "operator", "parking", "fee", "charge", "maxheight", "maxheight:physical",
    "capacity", "opening_hours", "access", "building", "covered",
)

# Explore categories: which OpenStreetMap tags count, and which Google place types to ask for.
POI_CATEGORIES = {
    "gas": [("amenity", {"fuel"})],
    "food": [("amenity", {"restaurant", "fast_food"})],
    "coffee": [("amenity", {"cafe"})],
    "groceries": [("shop", {"supermarket", "convenience"})],
    "hotels": [("tourism", {"hotel", "motel"})],
    "rest": [("highway", {"rest_area", "services"})],
}
POI_KEEP_TAGS = (
    "name", "brand", "operator", "opening_hours", "phone", "website", "cuisine", "fuel:diesel", "hgv",
    "addr:housenumber", "addr:street", "addr:city", "addr:state", "amenity", "shop", "tourism", "highway",
    "drive_through", "toilets", "stars",
)
GOOGLE_TYPES = {
    "parking": ["parking", "parking_garage", "parking_lot"],
    "gas": ["gas_station"],
    "food": ["restaurant", "fast_food_restaurant"],
    "coffee": ["cafe", "coffee_shop"],
    "groceries": ["grocery_store", "supermarket", "convenience_store"],
    "hotels": ["lodging"],
    "rest": ["rest_stop"],
}
FILTER_EXPRESSIONS = [
    "nwr/amenity=parking,fuel,restaurant,fast_food,cafe",
    "nwr/shop=supermarket,convenience",
    "nwr/tourism=hotel,motel",
    "nwr/highway=rest_area,services",
]

log = logging.getLogger("parkpal")

_state_lock = threading.Lock()
_activity = {"busy": False, "message": "Starting up"}


def set_activity(message, busy=True):
    with _state_lock:
        _activity["busy"] = busy
        _activity["message"] = message
    log.info(message)


# ---------------------------------------------------------------------------------------------------------
# Building the parking database
# ---------------------------------------------------------------------------------------------------------

def download(region, dest):
    """Downloads a Geofabrik extract, e.g. north-america/us -> .../north-america/us-latest.osm.pbf."""
    url = f"{GEOFABRIK}/{region}-latest.osm.pbf"
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "ParkPal-server/1.0 (self-hosted)"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        total = int(resp.headers.get("Content-Length") or 0)
        free = shutil.disk_usage(os.path.dirname(dest)).free
        if total and free < total * 1.3:
            raise RuntimeError(
                f"Not enough disk space for {region}: need about {total * 1.3 / 1e9:.1f} GB, "
                f"have {free / 1e9:.1f} GB free in {DATA_DIR}"
            )
        done = 0
        last = 0.0
        with open(tmp, "wb") as f:
            while True:
                chunk = resp.read(4 << 20)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if time.time() - last > 15:
                    last = time.time()
                    size = f" of {total / 1e9:.1f} GB" if total else ""
                    set_activity(f"Downloading {region}: {done / 1e9:.2f} GB{size}")
    if total and done != total:
        raise RuntimeError(f"Download of {region} was cut short ({done} of {total} bytes)")
    os.replace(tmp, dest)


def filter_osm(src, dest):
    """Keeps only parking and the Explore categories (plus the points that outline them): a tiny fraction."""
    subprocess.run(["osmium", "tags-filter", src, *FILTER_EXPRESSIONS, "-o", dest, "--overwrite"], check=True)


def wanted_tags(tags):
    if tags.get("amenity") != "parking":
        return None
    if tags.get("access") in ("private", "no"):
        return None
    return {k: tags.get(k) for k in KEEP_TAGS if tags.get(k)}


def classify(tags):
    """("parking", tags) or (explore category, tags) or None."""
    if tags.get("amenity") == "parking":
        t = wanted_tags(tags)
        return ("parking", t) if t is not None else None
    if tags.get("access") in ("private", "no"):
        return None
    for category, rules in POI_CATEGORIES.items():
        for key, values in rules:
            if tags.get(key) in values:
                return category, {k: tags.get(k) for k in POI_KEEP_TAGS if tags.get(k)}
    return None


def load_file(con, path):
    """Reads parking and Explore places from an OSM file. Areas get the center of their outline."""
    import osmium  # imported here so the server can still start if the build tools are missing

    parking_rows = []
    poi_rows = []
    counts = {"parking": 0, "pois": 0}

    def flush():
        con.executemany(
            "INSERT OR IGNORE INTO parking(osm_type, osm_id, lat, lon, tags) VALUES (?, ?, ?, ?, ?)", parking_rows
        )
        con.executemany(
            "INSERT OR IGNORE INTO pois(osm_type, osm_id, category, lat, lon, tags) VALUES (?, ?, ?, ?, ?, ?)",
            poi_rows,
        )
        parking_rows.clear()
        poi_rows.clear()

    def add(osm_type, osm_id, lat, lon, found):
        kind, tags = found
        packed = json.dumps(tags, separators=(",", ":"))
        if kind == "parking":
            parking_rows.append((osm_type, osm_id, lat, lon, packed))
            counts["parking"] += 1
        else:
            poi_rows.append((osm_type, osm_id, kind, lat, lon, packed))
            counts["pois"] += 1
        if len(parking_rows) + len(poi_rows) >= 5000:
            flush()

    class Handler(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.boxes = {}  # way id -> (min_lat, max_lat, min_lon, max_lon), for multipolygons

        def node(self, n):
            found = classify(n.tags)
            if found is None or not n.location.valid():
                return
            add("node", n.id, n.location.lat, n.location.lon, found)

        def way(self, w):
            box = None
            for nd in w.nodes:
                loc = nd.location
                if not loc.valid():
                    continue
                lat, lon = loc.lat, loc.lon
                if box is None:
                    box = [lat, lat, lon, lon]
                else:
                    if lat < box[0]:
                        box[0] = lat
                    if lat > box[1]:
                        box[1] = lat
                    if lon < box[2]:
                        box[2] = lon
                    if lon > box[3]:
                        box[3] = lon
            if box is None:
                return
            self.boxes[w.id] = tuple(box)
            found = classify(w.tags)
            if found is not None:
                add("way", w.id, (box[0] + box[1]) / 2, (box[2] + box[3]) / 2, found)

        def relation(self, r):
            found = classify(r.tags)
            if found is None:
                return
            box = None
            for m in r.members:
                if m.type != "w":
                    continue
                b = self.boxes.get(m.ref)
                if b is None:
                    continue
                if box is None:
                    box = list(b)
                else:
                    box = [min(box[0], b[0]), max(box[1], b[1]), min(box[2], b[2]), max(box[3], b[3])]
            if box is None:
                return
            add("relation", r.id, (box[0] + box[1]) / 2, (box[2] + box[3]) / 2, found)

    handler = Handler()
    handler.apply_file(path, locations=True, idx="sparse_mem_array")
    flush()
    con.commit()
    return counts["parking"], counts["pois"]


def create_schema(con):
    con.executescript(
        """
        CREATE TABLE parking (
            rid INTEGER PRIMARY KEY,
            osm_type TEXT NOT NULL,
            osm_id INTEGER NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            tags TEXT NOT NULL,
            UNIQUE (osm_type, osm_id)
        );
        CREATE TABLE pois (
            rid INTEGER PRIMARY KEY,
            osm_type TEXT NOT NULL,
            osm_id INTEGER NOT NULL,
            category TEXT NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            tags TEXT NOT NULL,
            UNIQUE (osm_type, osm_id)
        );
        CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
        """
    )


def finish_index(con):
    """Spatial indexes for fast "what's near here" lookups. Falls back to plain indexes if R-tree is missing."""
    try:
        con.execute("CREATE VIRTUAL TABLE parking_rtree USING rtree(rid, min_lat, max_lat, min_lon, max_lon)")
        con.execute("INSERT INTO parking_rtree SELECT rid, lat, lat, lon, lon FROM parking")
        con.execute("CREATE VIRTUAL TABLE pois_rtree USING rtree(rid, min_lat, max_lat, min_lon, max_lon)")
        con.execute("INSERT INTO pois_rtree SELECT rid, lat, lat, lon, lon FROM pois")
    except sqlite3.OperationalError:
        con.execute("CREATE INDEX IF NOT EXISTS parking_lat ON parking(lat)")
        con.execute("CREATE INDEX IF NOT EXISTS pois_lat ON pois(lat)")
    con.execute("CREATE INDEX IF NOT EXISTS pois_category ON pois(category)")
    con.commit()


def build():
    os.makedirs(WORK_DIR, exist_ok=True)
    new_db = os.path.join(DATA_DIR, "parking.new.db")
    if os.path.exists(new_db):
        os.remove(new_db)
    con = sqlite3.connect(new_db)
    try:
        con.execute("PRAGMA journal_mode=OFF")
        con.execute("PRAGMA synchronous=OFF")
        create_schema(con)
        total = 0
        total_pois = 0
        for region in REGIONS:
            if region.endswith((".pbf", ".osm")) and os.path.exists(region):
                set_activity(f"Loading places from {region}")
                p, q = load_file(con, region)
                total += p
                total_pois += q
                continue
            region = region.strip("/")
            safe = region.replace("/", "_")
            pbf = os.path.join(WORK_DIR, f"{safe}.osm.pbf")
            filtered = os.path.join(WORK_DIR, f"{safe}.filtered.osm.pbf")
            set_activity(f"Downloading {region}")
            download(region, pbf)
            set_activity(f"Picking out parking and places from {region} (this takes a few minutes for big regions)")
            filter_osm(pbf, filtered)
            os.remove(pbf)
            set_activity(f"Loading places from {region}")
            p, q = load_file(con, filtered)
            total += p
            total_pois += q
            os.remove(filtered)
        set_activity("Indexing for fast lookups")
        finish_index(con)
        now = datetime.now(timezone.utc)
        con.executemany(
            "INSERT OR REPLACE INTO meta(k, v) VALUES (?, ?)",
            [
                ("updated", now.isoformat(timespec="seconds")),
                ("updated_ts", str(now.timestamp())),
                ("regions", json.dumps(REGIONS)),
                ("places", str(total)),
                ("pois", str(total_pois)),
                ("schema", SCHEMA_VERSION),
            ],
        )
        con.commit()
    finally:
        con.close()
    os.replace(new_db, DB_PATH)
    set_activity(
        f"Ready: {total:,} parking places and {total_pois:,} other places for {', '.join(REGIONS)}", busy=False
    )


def read_meta():
    if not os.path.exists(DB_PATH):
        return None
    try:
        con = sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True)
        try:
            meta = dict(con.execute("SELECT k, v FROM meta").fetchall())
        finally:
            con.close()
        return {
            "updated": meta.get("updated"),
            "updated_ts": float(meta.get("updated_ts", "0")),
            "regions": json.loads(meta.get("regions", "[]")),
            "places": int(meta.get("places", "0")),
            "pois": int(meta.get("pois", "0")),
            "schema": meta.get("schema", "1"),
        }
    except Exception:
        return None


def needs_build():
    meta = read_meta()
    if meta is None:
        return True
    if meta["regions"] != REGIONS or meta["schema"] != SCHEMA_VERSION:
        return True
    return (time.time() - meta["updated_ts"]) / 86400 >= REFRESH_DAYS


def has_pois():
    meta = read_meta()
    return meta is not None and meta["schema"] == SCHEMA_VERSION


def refresher():
    while True:
        try:
            if needs_build():
                build()
            else:
                meta = read_meta()
                set_activity(
                    f"Ready: {meta['places']:,} parking places and {meta['pois']:,} other places, "
                    f"updated {meta['updated']}",
                    busy=False,
                )
        except Exception as e:  # keep serving old data; try again later
            log.exception("Update failed")
            set_activity(f"Last update failed: {e}. Trying again in an hour.", busy=False)
            for leftover in ("parking.new.db",):
                path = os.path.join(DATA_DIR, leftover)
                if os.path.exists(path):
                    os.remove(path)
            shutil.rmtree(WORK_DIR, ignore_errors=True)
        time.sleep(3600)


# ---------------------------------------------------------------------------------------------------------
# Answering the app
# ---------------------------------------------------------------------------------------------------------

def distance_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _query(table, lat, lon, radius, category=None):
    """Rows of `table` within `radius` meters, closest first, as Overpass-style elements."""
    dlat = radius / 111320.0
    dlon = radius / (111320.0 * max(0.01, math.cos(math.radians(lat))))
    box = (lat - dlat, lat + dlat, lon - dlon, lon + dlon)
    extra, args = ("", ())
    if category is not None:
        extra, args = (" AND p.category = ?", (category,))
    con = sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True)
    try:
        has_rtree = con.execute(
            "SELECT 1 FROM sqlite_master WHERE name = ?", (f"{table}_rtree",)
        ).fetchone() is not None
        if has_rtree:
            rows = con.execute(
                f"SELECT p.osm_type, p.osm_id, p.lat, p.lon, p.tags FROM {table}_rtree r "
                f"JOIN {table} p ON p.rid = r.rid "
                "WHERE r.max_lat >= ? AND r.min_lat <= ? AND r.max_lon >= ? AND r.min_lon <= ?" + extra,
                box + args,
            ).fetchall()
        else:
            rows = con.execute(
                f"SELECT p.osm_type, p.osm_id, p.lat, p.lon, p.tags FROM {table} p "
                "WHERE p.lat BETWEEN ? AND ? AND p.lon BETWEEN ? AND ?" + extra,
                box + args,
            ).fetchall()
    finally:
        con.close()
    found = []
    for osm_type, osm_id, plat, plon, tags in rows:
        d = distance_m(lat, lon, plat, plon)
        if d <= radius:
            found.append((d, osm_type, osm_id, plat, plon, tags))
    found.sort(key=lambda x: x[0])
    elements = []
    for _, osm_type, osm_id, plat, plon, tags in found[:MAX_RESULTS]:
        e = {"type": osm_type, "id": osm_id, "tags": json.loads(tags)}
        if category is not None:
            e["tags"]["parkpal:category"] = category
        if osm_type == "node":
            e["lat"], e["lon"] = plat, plon
        else:
            e["center"] = {"lat": plat, "lon": plon}
        elements.append(e)
    return {"version": 0.6, "generator": "ParkPal server", "elements": elements}


def nearby(lat, lon, radius):
    return _query("parking", lat, lon, radius)


def nearby_pois(lat, lon, radius, category):
    return _query("pois", lat, lon, radius, category)


# ---------------------------------------------------------------------------------------------------------
# Optional Google parking (fills gaps in OpenStreetMap)
# ---------------------------------------------------------------------------------------------------------

GOOGLE_GRID = 0.005           # ~500 m: nearby searches share one Google lookup
GOOGLE_PADDING_M = 400        # so a snapped search still covers the real point
GOOGLE_CACHE_DAYS = 30        # Google allows caching coordinates for up to 30 days
GOOGLE_DUPLICATE_M = 60       # a Google place this close to an OpenStreetMap one is the same lot
GOOGLE_FIELDS = "places.id,places.displayName,places.location,places.types,places.formattedAddress"
_google_lock = threading.Lock()
_google_types = {k: list(v) for k, v in GOOGLE_TYPES.items()}
# What a Google result looks like in OpenStreetMap terms, so the app can treat both the same way.
GOOGLE_AS_OSM = {
    "gas": {"amenity": "fuel"},
    "food": {"amenity": "restaurant"},
    "coffee": {"amenity": "cafe"},
    "groceries": {"shop": "supermarket"},
    "hotels": {"tourism": "hotel"},
    "rest": {"highway": "rest_area"},
}


def google_enabled():
    return bool(GOOGLE_KEY) and GOOGLE_MODE in ("gaps", "always")


def _google_db():
    con = sqlite3.connect(GOOGLE_DB, timeout=10)
    con.execute("CREATE TABLE IF NOT EXISTS cache (k TEXT PRIMARY KEY, ts REAL NOT NULL, places TEXT NOT NULL)")
    con.execute("CREATE TABLE IF NOT EXISTS usage (month TEXT PRIMARY KEY, n INTEGER NOT NULL)")
    return con


def _this_month():
    return datetime.now(timezone.utc).strftime("%Y-%m")


def google_usage():
    with _google_lock:
        con = _google_db()
        try:
            row = con.execute("SELECT n FROM usage WHERE month = ?", (_this_month(),)).fetchone()
        finally:
            con.close()
    return row[0] if row else 0


def _google_request(lat, lon, radius, kind="parking"):
    """One Nearby Search (New) call. Only "Pro" fields are requested, the cheapest tier."""
    types = _google_types[kind]
    body = {
        "includedTypes": types,
        "maxResultCount": 20,
        "rankPreference": "DISTANCE",
        "locationRestriction": {
            "circle": {"center": {"latitude": lat, "longitude": lon}, "radius": float(radius)}
        },
    }
    req = urllib.request.Request(
        "https://places.googleapis.com/v1/places:searchNearby",
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Goog-Api-Key": GOOGLE_KEY,
            "X-Goog-FieldMask": GOOGLE_FIELDS,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.load(resp)
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        # If Google doesn't accept one of the newer types, fall back to the first, most basic one.
        if e.code == 400 and len(types) > 1 and "type" in detail.lower():
            _google_types[kind] = types[:1]
            return _google_request(lat, lon, radius, kind)
        raise RuntimeError(f"HTTP {e.code}: {detail}")
    places = []
    for p in data.get("places", []):
        loc = p.get("location") or {}
        if "latitude" not in loc or "longitude" not in loc:
            continue
        found_types = p.get("types") or []
        tags = {"parkpal:source": "google"}
        name = (p.get("displayName") or {}).get("text")
        if name:
            tags["name"] = name
        if kind == "parking":
            if "parking_garage" in found_types:
                tags["parking"] = "multi-storey"
            elif "parking_lot" in found_types:
                tags["parking"] = "surface"
        else:
            tags.update(GOOGLE_AS_OSM.get(kind, {}))
            tags["parkpal:category"] = kind
            if kind == "food" and "fast_food_restaurant" in found_types:
                tags["amenity"] = "fast_food"
            elif kind == "groceries" and "convenience_store" in found_types:
                tags["shop"] = "convenience"
        if p.get("formattedAddress"):
            tags["addr:full"] = p["formattedAddress"]
        places.append({"id": p.get("id") or f"{loc['latitude']},{loc['longitude']}",
                       "lat": loc["latitude"], "lon": loc["longitude"], "tags": tags})
    return places


def google_nearby(lat, lon, radius, kind="parking"):
    """Google places around a point: from the 30-day cache if possible, else one search (within the limit)."""
    clat = round(lat / GOOGLE_GRID) * GOOGLE_GRID
    clon = round(lon / GOOGLE_GRID) * GOOGLE_GRID
    query_radius = min(50000, radius + GOOGLE_PADDING_M)
    key = f"{clat:.3f}_{clon:.3f}_{query_radius}"
    if kind != "parking":
        key = f"{kind}:{key}"
    now = time.time()
    with _google_lock:
        con = _google_db()
        try:
            row = con.execute("SELECT ts, places FROM cache WHERE k = ?", (key,)).fetchone()
            if row and now - row[0] < GOOGLE_CACHE_DAYS * 86400:
                return json.loads(row[1])
            month = _this_month()
            used = con.execute("SELECT n FROM usage WHERE month = ?", (month,)).fetchone()
            if (used[0] if used else 0) >= GOOGLE_MONTHLY_LIMIT:
                return []
            con.execute(
                "INSERT INTO usage(month, n) VALUES (?, 1) ON CONFLICT(month) DO UPDATE SET n = n + 1", (month,)
            )
            con.commit()
        finally:
            con.close()
    try:
        places = _google_request(clat, clon, query_radius, kind)
    except Exception as e:
        log.warning("Google %s lookup failed: %s", kind, e)
        return []
    with _google_lock:
        con = _google_db()
        try:
            con.execute("INSERT OR REPLACE INTO cache(k, ts, places) VALUES (?, ?, ?)", (key, now, json.dumps(places)))
            con.execute("DELETE FROM cache WHERE ts < ?", (now - GOOGLE_CACHE_DAYS * 86400,))
            con.commit()
        finally:
            con.close()
    return places


def _coords(e):
    if "lat" in e:
        return e["lat"], e["lon"]
    return e["center"]["lat"], e["center"]["lon"]


def merge_google(elements, google_places, lat, lon, radius):
    """Adds Google places that OpenStreetMap doesn't have; gives unnamed OpenStreetMap lots Google's name."""
    known = [(*_coords(e), e) for e in elements]
    added = 0
    for g in google_places:
        if distance_m(lat, lon, g["lat"], g["lon"]) > radius:
            continue
        same = next(
            (e for plat, plon, e in known if distance_m(plat, plon, g["lat"], g["lon"]) <= GOOGLE_DUPLICATE_M),
            None,
        )
        if same is not None:
            if not same["tags"].get("name") and g["tags"].get("name"):
                same["tags"]["name"] = g["tags"]["name"]
            continue
        element = {"type": "google", "id": g["id"], "lat": g["lat"], "lon": g["lon"], "tags": dict(g["tags"])}
        elements.append(element)
        known.append((g["lat"], g["lon"], element))
        added += 1
    elements.sort(key=lambda e: distance_m(lat, lon, *_coords(e)))
    return added


def status():
    with _state_lock:
        activity = dict(_activity)
    meta = read_meta()
    return {
        "ready": meta is not None,
        "places": meta["places"] if meta else 0,
        "pois": meta["pois"] if meta else 0,
        "explore": sorted(POI_CATEGORIES),
        "updated": meta["updated"] if meta else None,
        "regions": meta["regions"] if meta else REGIONS,
        "busy": activity["busy"],
        "message": activity["message"],
        "google": {
            "enabled": google_enabled(),
            "mode": GOOGLE_MODE if GOOGLE_KEY else "off",
            "used_this_month": google_usage() if GOOGLE_KEY else 0,
            "monthly_limit": GOOGLE_MONTHLY_LIMIT,
        },
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "ParkPal/1.0"

    def log_message(self, fmt, *args):
        log.debug("%s %s", self.address_string(), fmt % args)

    def send_json(self, code, obj):
        body = json.dumps(obj, separators=(",", ":")).encode("utf-8")
        gz = "gzip" in (self.headers.get("Accept-Encoding") or "") and len(body) > 1024
        if gz:
            body = gzip.compress(body, compresslevel=5)
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        if gz:
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def authorized(self, qs):
        if not TOKEN:
            return True
        given = self.headers.get("X-ParkPal-Token") or (qs.get("token") or [""])[0]
        return hmac.compare_digest(given.encode(), TOKEN.encode())

    def do_GET(self):
        url = urlparse(self.path)
        qs = parse_qs(url.query)
        if url.path == "/health":
            body = b"ok"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if not self.authorized(qs):
            return self.send_json(401, {"error": "Wrong or missing token"})
        if url.path in ("/", "/api/status"):
            return self.send_json(200, status())
        if url.path == "/api/parking":
            try:
                lat = float(qs["lat"][0])
                lon = float(qs["lon"][0])
                radius = int(float((qs.get("radius") or ["1000"])[0]))
            except (KeyError, ValueError, IndexError):
                return self.send_json(400, {"error": "Use /api/parking?lat=..&lon=..&radius=.."})
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                return self.send_json(400, {"error": "lat/lon out of range"})
            radius = max(50, min(MAX_RADIUS_M, radius))
            have_osm = os.path.exists(DB_PATH)
            if not have_osm and not google_enabled():
                return self.send_json(503, {"error": "Parking data is still being prepared", **status()})
            started = time.perf_counter()
            if have_osm:
                result = nearby(lat, lon, radius)
            else:
                result = {"version": 0.6, "generator": "ParkPal server", "elements": []}
            osm_count = len(result["elements"])
            google_added = 0
            if google_enabled() and (GOOGLE_MODE == "always" or osm_count < GOOGLE_GAP_THRESHOLD):
                google_added = merge_google(result["elements"], google_nearby(lat, lon, radius), lat, lon, radius)
                del result["elements"][MAX_RESULTS:]
            log.info(
                "parking %.4f,%.4f r=%d -> %d (+%d from Google) in %.0f ms",
                lat, lon, radius, osm_count, google_added, (time.perf_counter() - started) * 1000,
            )
            return self.send_json(200, result)
        if url.path == "/api/places":
            try:
                lat = float(qs["lat"][0])
                lon = float(qs["lon"][0])
                radius = int(float((qs.get("radius") or ["3000"])[0]))
                category = qs["category"][0].strip().lower()
            except (KeyError, ValueError, IndexError):
                return self.send_json(400, {"error": "Use /api/places?lat=..&lon=..&radius=..&category=.."})
            if category not in POI_CATEGORIES:
                return self.send_json(
                    400, {"error": f"Unknown category. Use one of: {', '.join(sorted(POI_CATEGORIES))}"}
                )
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                return self.send_json(400, {"error": "lat/lon out of range"})
            radius = max(50, min(POI_MAX_RADIUS_M.get(category, MAX_POI_RADIUS_M), radius))
            have_osm = has_pois()
            if not have_osm and not google_enabled():
                return self.send_json(503, {"error": "Place data is still being prepared", **status()})
            started = time.perf_counter()
            if have_osm:
                result = nearby_pois(lat, lon, radius, category)
            else:
                result = {"version": 0.6, "generator": "ParkPal server", "elements": []}
            osm_count = len(result["elements"])
            google_added = 0
            if google_enabled() and (GOOGLE_MODE == "always" or osm_count < GOOGLE_GAP_THRESHOLD):
                found = google_nearby(lat, lon, min(radius, 50000), category)
                google_added = merge_google(result["elements"], found, lat, lon, radius)
                del result["elements"][MAX_RESULTS:]
            log.info(
                "%s %.4f,%.4f r=%d -> %d (+%d from Google) in %.0f ms",
                category, lat, lon, radius, osm_count, google_added, (time.perf_counter() - started) * 1000,
            )
            return self.send_json(200, result)
        return self.send_json(404, {"error": "Not found"})


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", stream=sys.stdout)
    os.makedirs(DATA_DIR, exist_ok=True)
    if "--build-once" in sys.argv:
        build()
        print(json.dumps(status()))
        return
    if not TOKEN:
        log.warning("PARKPAL_TOKEN is not set: anyone who finds this server can use it.")
    if google_enabled():
        log.info("Google parking is on (%s mode, up to %d searches a month)", GOOGLE_MODE, GOOGLE_MONTHLY_LIMIT)
    threading.Thread(target=refresher, name="refresher", daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    log.info("ParkPal server listening on port %d (regions: %s)", PORT, ", ".join(REGIONS))
    server.serve_forever()


if __name__ == "__main__":
    main()
