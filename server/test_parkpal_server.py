"""
Tests for the parking server, without the internet: a tiny hand-made map, and a fake Google that
returns canned results. Run:  python server/test_parkpal_server.py
"""
import importlib.util
import json
import os
import sys
import tempfile
import threading
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))

TEST_OSM = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="1" lat="38.8970" lon="-77.0070" version="1"/>
  <node id="2" lat="38.8970" lon="-77.0060" version="1"/>
  <node id="3" lat="38.8976" lon="-77.0060" version="1"/>
  <node id="4" lat="38.8976" lon="-77.0070" version="1"/>
  <node id="10" lat="38.8990" lon="-77.0050" version="1">
    <tag k="amenity" v="parking"/><tag k="parking" v="multi-storey"/><tag k="name" v="Union Garage"/>
    <tag k="maxheight" v="6'8&quot;"/><tag k="fee" v="yes"/>
  </node>
  <node id="11" lat="38.8980" lon="-77.0080" version="1">
    <tag k="amenity" v="parking"/><tag k="access" v="private"/><tag k="name" v="Staff Only"/>
  </node>
  <way id="100" version="1">
    <nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="1"/>
    <tag k="amenity" v="parking"/><tag k="fee" v="no"/>
  </way>
</osm>
"""

# What the fake Google returns: one duplicate of the unnamed OSM lot (should only lend its name),
# one new garage nearby (should be added), one far away (outside the radius, should be dropped).
FAKE_GOOGLE = [
    {"id": "g-dup", "lat": 38.89731, "lon": -77.00652,
     "tags": {"parkpal:source": "google", "name": "Station Lot (Google)", "parking": "surface"}},
    {"id": "g-new", "lat": 38.8960, "lon": -77.0040,
     "tags": {"parkpal:source": "google", "name": "New Garage", "parking": "multi-storey",
              "addr:full": "1 Test St, Washington, DC"}},
    {"id": "g-far", "lat": 38.9300, "lon": -77.0500,
     "tags": {"parkpal:source": "google", "name": "Far Garage"}},
]


def load_server(data_dir, **env):
    os.environ.update({"DATA_DIR": data_dir, **env})
    spec = importlib.util.spec_from_file_location(f"pps_{len(env)}_{id(env)}", os.path.join(HERE, "parkpal_server.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def check(cond, what):
    print(("PASS " if cond else "FAIL ") + what)
    if not cond:
        check.failed = True


check.failed = False


def main():
    tmp = tempfile.mkdtemp()
    osm_path = os.path.join(tmp, "test.osm")
    with open(osm_path, "w") as f:
        f.write(TEST_OSM)

    srv = load_server(tmp, REGIONS=osm_path, PARKPAL_TOKEN="secret", GOOGLE_PLACES_KEY="fake-key",
                      GOOGLE_MODE="gaps", GOOGLE_GAP_THRESHOLD="8", GOOGLE_MONTHLY_LIMIT="2")
    calls = []

    def fake_request(lat, lon, radius):
        calls.append((lat, lon, radius))
        return json.loads(json.dumps(FAKE_GOOGLE))

    srv._google_request = fake_request
    srv.build()

    # OpenStreetMap only (Google not needed when we pass a threshold of 0)
    osm = srv.nearby(38.8973, -77.0063, 1000)["elements"]
    check(len(osm) == 2, f"OpenStreetMap finds 2 public lots near the station (got {len(osm)})")

    # Gaps mode: 2 OSM results < 8, so Google is asked and merged
    elements = srv.nearby(38.8973, -77.0063, 1000)["elements"]
    added = srv.merge_google(elements, srv.google_nearby(38.8973, -77.0063, 1000), 38.8973, -77.0063, 1000)
    names = [e["tags"].get("name") for e in elements]
    check(added == 1, f"Google adds exactly 1 new place (got {added})")
    check("New Garage" in names, "the new Google garage is included")
    check("Far Garage" not in names, "Google places outside the radius are dropped")
    check("Station Lot (Google)" in names and len([n for n in names if n and "Station Lot" in n]) == 1,
          "the duplicate lot appears once, with Google's name filled in")
    check(any(e["type"] == "google" and e["tags"].get("addr:full") for e in elements),
          "Google places carry their address")
    from_station = [srv.distance_m(38.8973, -77.0063, *srv._coords(e)) for e in elements]
    check(from_station == sorted(from_station), "results stay closest-first after merging")

    # Cache: the same area again doesn't call Google
    srv.google_nearby(38.8974, -77.0062, 1000)
    check(len(calls) == 1, f"a nearby repeat search uses the 30-day cache (Google calls: {len(calls)})")

    # Monthly limit: limit is 2, so after one more new area the next new area is refused
    srv.google_nearby(40.0, -80.0, 1000)
    third = srv.google_nearby(41.0, -81.0, 1000)
    check(len(calls) == 2 and third == [], f"stops at the monthly limit (calls: {len(calls)})")
    check(srv.google_usage() == 2, f"usage is counted (got {srv.google_usage()})")

    # Through the HTTP API
    from http.server import ThreadingHTTPServer
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), srv.Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}"

    def get(path, token="secret"):
        req = urllib.request.Request(base + path, headers={"X-ParkPal-Token": token} if token else {})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, None

    code, body = get("/api/parking?lat=38.8973&lon=-77.0063&radius=1000")
    check(code == 200 and any(e["type"] == "google" for e in body["elements"]),
          "the API returns merged OpenStreetMap + Google results")
    code, _ = get("/api/parking?lat=38.8973&lon=-77.0063&radius=1000", token=None)
    check(code == 401, "the API still requires the token")
    code, st = get("/api/status")
    check(code == 200 and st["google"]["enabled"] and st["google"]["used_this_month"] == 2,
          f"status reports Google usage ({st and st['google']})")
    httpd.shutdown()

    # Off when no key
    srv2 = load_server(tempfile.mkdtemp(), REGIONS=osm_path, PARKPAL_TOKEN="", GOOGLE_PLACES_KEY="", GOOGLE_MODE="gaps")
    check(not srv2.google_enabled(), "Google stays off without a key")

    print("\nAll tests passed." if not check.failed else "\nSome tests FAILED.")
    sys.exit(1 if check.failed else 0)


if __name__ == "__main__":
    main()
