#!/usr/bin/env python3
"""
ParkPal parking server.

Keeps a copy of OpenStreetMap parking (lots, garages, street parking, with height limits and fees) for the
regions you choose and serves it to the ParkPal app in milliseconds, so the app doesn't depend on the
overloaded public Overpass servers.

It downloads region extracts from Geofabrik, keeps only amenity=parking with `osmium tags-filter`, works out
a point for each lot with pyosmium, and stores everything in SQLite with an R-tree index. It refreshes on
its own every REFRESH_DAYS.

Endpoints:
  GET /health                              -> "ok" (no token needed, for health checks)
  GET /api/status                          -> what's loaded, when it was updated, what it's doing now
  GET /api/parking?lat=..&lon=..&radius=.. -> Overpass-style JSON ({"elements": [...]}) closest first

Settings (environment variables):
  REGIONS        Geofabrik regions, comma separated (default "north-america/us").
                 Examples: "north-america/us/ohio,north-america/us/pennsylvania", "north-america/canada".
                 A path to a local .osm.pbf/.osm file also works.
  REFRESH_DAYS   How often to download fresh data (default 7).
  PARKPAL_TOKEN  If set, the app must send this token (header X-ParkPal-Token or ?token=).
  PORT           Port to listen on (default 8080).
  DATA_DIR       Where to keep data (default /data).

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

DB_PATH = os.path.join(DATA_DIR, "parking.db")
WORK_DIR = os.path.join(DATA_DIR, "work")
MAX_RADIUS_M = 5000
MAX_RESULTS = 500
KEEP_TAGS = (
    "name", "operator", "parking", "fee", "charge", "maxheight", "maxheight:physical",
    "capacity", "opening_hours", "access", "building", "covered",
)

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


def filter_parking(src, dest):
    """Keeps only parking (plus the points that outline the lots), which shrinks the data enormously."""
    subprocess.run(["osmium", "tags-filter", src, "nwr/amenity=parking", "-o", dest, "--overwrite"], check=True)


def wanted_tags(tags):
    if tags.get("amenity") != "parking":
        return None
    if tags.get("access") in ("private", "no"):
        return None
    return {k: tags.get(k) for k in KEEP_TAGS if tags.get(k)}


def load_file(con, path):
    """Reads parking from an OSM file into the database. Lots get the center of their outline."""
    import osmium  # imported here so the server can still start if the build tools are missing

    rows = []

    def flush():
        con.executemany(
            "INSERT OR IGNORE INTO parking(osm_type, osm_id, lat, lon, tags) VALUES (?, ?, ?, ?, ?)", rows
        )
        rows.clear()

    def add(osm_type, osm_id, lat, lon, tags):
        rows.append((osm_type, osm_id, lat, lon, json.dumps(tags, separators=(",", ":"))))
        if len(rows) >= 5000:
            flush()

    class Handler(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.boxes = {}  # way id -> (min_lat, max_lat, min_lon, max_lon), for multipolygon lots
            self.count = 0

        def node(self, n):
            tags = wanted_tags(n.tags)
            if tags is None or not n.location.valid():
                return
            add("node", n.id, n.location.lat, n.location.lon, tags)
            self.count += 1

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
            tags = wanted_tags(w.tags)
            if tags is not None:
                add("way", w.id, (box[0] + box[1]) / 2, (box[2] + box[3]) / 2, tags)
                self.count += 1

        def relation(self, r):
            tags = wanted_tags(r.tags)
            if tags is None:
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
            add("relation", r.id, (box[0] + box[1]) / 2, (box[2] + box[3]) / 2, tags)
            self.count += 1

    handler = Handler()
    handler.apply_file(path, locations=True, idx="sparse_mem_array")
    flush()
    con.commit()
    return handler.count


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
        CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
        """
    )


def finish_index(con):
    """Spatial index for fast "what's near here" lookups. Falls back to a plain index if R-tree is missing."""
    try:
        con.execute("CREATE VIRTUAL TABLE parking_rtree USING rtree(rid, min_lat, max_lat, min_lon, max_lon)")
        con.execute("INSERT INTO parking_rtree SELECT rid, lat, lat, lon, lon FROM parking")
    except sqlite3.OperationalError:
        con.execute("CREATE INDEX parking_lat ON parking(lat)")
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
        for region in REGIONS:
            if region.endswith((".pbf", ".osm")) and os.path.exists(region):
                set_activity(f"Loading parking from {region}")
                total += load_file(con, region)
                continue
            region = region.strip("/")
            safe = region.replace("/", "_")
            pbf = os.path.join(WORK_DIR, f"{safe}.osm.pbf")
            only_parking = os.path.join(WORK_DIR, f"{safe}.parking.osm.pbf")
            set_activity(f"Downloading {region}")
            download(region, pbf)
            set_activity(f"Picking out parking from {region} (this takes a few minutes for big regions)")
            filter_parking(pbf, only_parking)
            os.remove(pbf)
            set_activity(f"Loading parking from {region}")
            total += load_file(con, only_parking)
            os.remove(only_parking)
        set_activity("Indexing parking for fast lookups")
        finish_index(con)
        now = datetime.now(timezone.utc)
        con.executemany(
            "INSERT OR REPLACE INTO meta(k, v) VALUES (?, ?)",
            [
                ("updated", now.isoformat(timespec="seconds")),
                ("updated_ts", str(now.timestamp())),
                ("regions", json.dumps(REGIONS)),
                ("places", str(total)),
            ],
        )
        con.commit()
    finally:
        con.close()
    os.replace(new_db, DB_PATH)
    set_activity(f"Ready: {total:,} parking places for {', '.join(REGIONS)}", busy=False)


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
        }
    except Exception:
        return None


def needs_build():
    meta = read_meta()
    if meta is None:
        return True
    if meta["regions"] != REGIONS:
        return True
    return (time.time() - meta["updated_ts"]) / 86400 >= REFRESH_DAYS


def refresher():
    while True:
        try:
            if needs_build():
                build()
            else:
                meta = read_meta()
                set_activity(f"Ready: {meta['places']:,} parking places, updated {meta['updated']}", busy=False)
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


def nearby(lat, lon, radius):
    dlat = radius / 111320.0
    dlon = radius / (111320.0 * max(0.01, math.cos(math.radians(lat))))
    con = sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True)
    try:
        has_rtree = con.execute(
            "SELECT 1 FROM sqlite_master WHERE name = 'parking_rtree'"
        ).fetchone() is not None
        if has_rtree:
            rows = con.execute(
                "SELECT p.osm_type, p.osm_id, p.lat, p.lon, p.tags FROM parking_rtree r "
                "JOIN parking p ON p.rid = r.rid "
                "WHERE r.max_lat >= ? AND r.min_lat <= ? AND r.max_lon >= ? AND r.min_lon <= ?",
                (lat - dlat, lat + dlat, lon - dlon, lon + dlon),
            ).fetchall()
        else:
            rows = con.execute(
                "SELECT osm_type, osm_id, lat, lon, tags FROM parking "
                "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                (lat - dlat, lat + dlat, lon - dlon, lon + dlon),
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
        if osm_type == "node":
            e["lat"], e["lon"] = plat, plon
        else:
            e["center"] = {"lat": plat, "lon": plon}
        elements.append(e)
    return {"version": 0.6, "generator": "ParkPal server", "elements": elements}


def status():
    with _state_lock:
        activity = dict(_activity)
    meta = read_meta()
    return {
        "ready": meta is not None,
        "places": meta["places"] if meta else 0,
        "updated": meta["updated"] if meta else None,
        "regions": meta["regions"] if meta else REGIONS,
        "busy": activity["busy"],
        "message": activity["message"],
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
            if not os.path.exists(DB_PATH):
                return self.send_json(503, {"error": "Parking data is still being prepared", **status()})
            started = time.perf_counter()
            result = nearby(lat, lon, radius)
            log.info(
                "parking %.4f,%.4f r=%d -> %d in %.0f ms",
                lat, lon, radius, len(result["elements"]), (time.perf_counter() - started) * 1000,
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
    threading.Thread(target=refresher, name="refresher", daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    log.info("ParkPal server listening on port %d (regions: %s)", PORT, ", ".join(REGIONS))
    server.serve_forever()


if __name__ == "__main__":
    main()
