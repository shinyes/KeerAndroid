from __future__ import annotations

import json
import pathlib
import tempfile
import urllib.request
import zipfile

import shapefile


ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSET_PATH = ROOT / "app" / "src" / "main" / "assets" / "global_basemap_110m.json"

DATASETS = {
    "land": "https://naciscdn.org/naturalearth/110m/physical/ne_110m_land.zip",
    "borders": "https://naciscdn.org/naturalearth/110m/cultural/ne_110m_admin_0_boundary_lines_land.zip",
    "lakes": "https://naciscdn.org/naturalearth/110m/physical/ne_110m_lakes.zip",
}


def download_and_extract(url: str, target_dir: pathlib.Path) -> pathlib.Path:
    target_dir.mkdir(parents=True, exist_ok=True)
    archive_path = target_dir / pathlib.Path(url).name
    urllib.request.urlretrieve(url, archive_path)
    with zipfile.ZipFile(archive_path, "r") as archive:
        archive.extractall(target_dir)
    shapefiles = list(target_dir.glob("*.shp"))
    if not shapefiles:
        raise RuntimeError(f"No shapefile found in {archive_path}")
    return shapefiles[0]


def round_point(point: tuple[float, float]) -> list[float]:
    longitude, latitude = point
    return [round(latitude, 4), round(longitude, 4)]


def read_polygon_rings(shp_path: pathlib.Path) -> list[list[list[float]]]:
    reader = shapefile.Reader(str(shp_path))
    rings: list[list[list[float]]] = []
    for shape in reader.shapes():
        points = shape.points
        if not points:
            continue
        part_indices = list(shape.parts) + [len(points)]
        for start, end in zip(part_indices[:-1], part_indices[1:]):
            ring_points = points[start:end]
            if len(ring_points) < 4:
                continue
            rings.append([round_point(point) for point in ring_points])
    return rings


def read_polyline_paths(shp_path: pathlib.Path) -> list[list[list[float]]]:
    reader = shapefile.Reader(str(shp_path))
    paths: list[list[list[float]]] = []
    for shape in reader.shapes():
        points = shape.points
        if not points:
            continue
        part_indices = list(shape.parts) + [len(points)]
        for start, end in zip(part_indices[:-1], part_indices[1:]):
            path_points = points[start:end]
            if len(path_points) < 2:
                continue
            paths.append([round_point(point) for point in path_points])
    return paths


def main() -> None:
    with tempfile.TemporaryDirectory() as temp_dir_str:
        temp_dir = pathlib.Path(temp_dir_str)
        land_shp = download_and_extract(DATASETS["land"], temp_dir / "land")
        borders_shp = download_and_extract(DATASETS["borders"], temp_dir / "borders")
        lakes_shp = download_and_extract(DATASETS["lakes"], temp_dir / "lakes")

        payload = {
            "landRings": read_polygon_rings(land_shp),
            "borderLines": read_polyline_paths(borders_shp),
            "lakeRings": read_polygon_rings(lakes_shp),
        }

    ASSET_PATH.parent.mkdir(parents=True, exist_ok=True)
    with ASSET_PATH.open("w", encoding="utf-8") as output:
        json.dump(payload, output, ensure_ascii=False, separators=(",", ":"))

    print(f"Wrote {ASSET_PATH}")


if __name__ == "__main__":
    main()
