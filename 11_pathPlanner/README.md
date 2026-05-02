# pathPlanner

`pathPlanner` is a Java 17 + Gradle tool that generates geographic traversal paths for Google Earth capture sessions.

## What it does

- Builds route curves from a start latitude/longitude.
- Supports curve generators:
  - `spiral`
  - `zigzag`
- Uses geodesic calculations to produce point sequences.
- Writes routes and sampled marker points into Google Earth KML (`myplaces.kml`).

## Purpose in the pipeline

This project provides scan paths used before active tracing, so the controller can follow a planned geographic traversal.

## Run

Example:

```bash
gradle run --args="spiral <lat> <lon> <step_distance_m> <max_distance_m>"
```

Or:

```bash
gradle run --args="zigzag <lat> <lon> <step_distance_m> <max_distance_m>"
```
