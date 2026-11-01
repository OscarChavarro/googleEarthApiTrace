# World10: selected groups

`./script/runWorld10.sh` now runs the global northern recapture: 45 rectangles
cover every longitude from -180 to 180, each spanning 8 degrees of longitude
and 59–68 N (9 degrees of latitude). Starting at 59 N overlaps the previous
coverage by one degree. Every rectangle has `force: true`, including the
previously captured Alaska/Canada regions at -140, -132 and -124 longitude.

The frozen job list is `world10EmptyGroups.jsonl`; its provenance is in
`world10EmptyGroups.selection.json`. Recreate this northern-only plan with:

```bash
python3 script/updateWorld10EmptyGroups.py --northern-band-only
```

Without that flag, the generator selects groups with at least 95% missing
level-10 PNGs from the baseline [-60,60) lattice plus the new 59–68 N band.
It counts cell centers and excludes ancestor images from coverage.

The superpipeline overlaps capture into `/media/ramdisk/output_incoming` with
processing from `/media/ramdisk/output`, recycling the slots between jobs.
It uses `/media/ramdisk/matrix` and merges validated level-10 output into
`/samples/datasets/googleEarth/toplevel`. Northern routes use
`180000 3200 90000 9 8`, retaining the prior northern altitude parameter.
Failures are logged and the batch continues.

Inspect the saved list with `./script/runWorld10.sh --emit-jobs`. Emission
and execution accept `--start-from N` and `--limit N`. Forced northern jobs
are repeated even when their level-10 extent is already present. The
controller publishes the list to `scripts/world10PlannedGroups.jsonl` for
the viewer's pending-groups mode.

`WORLD10_LOG_ROOT` selects the log directory. Each detached batch has separate
logs beneath `/media/ramdisk/logs/world10-empty-groups/`; `current-run.txt`
contains the latest directory, with `controller.log` and `controller.pid`.
