# Sweet Home 3D — Lasercut Export Plugin

A Sweet Home 3D plugin that exports a level as an SVG file ready for laser
cutting. Walls become rectangular pieces with **box-joint fingers** on the
bottom edge that slot into the floor plate. Optionally, walls also receive
finger joints where they meet other walls.

## Features

- Adds **Tools → Export to lasercut SVG…** to Sweet Home 3D.
- Exports the currently selected level (or all walls if there are no levels).
- Consolidates all walls to a single user-specified material thickness.
- Generates one wall piece per wall and one combined floor plate.
- Floor plate has rectangular slots aligned with each wall's bottom fingers.
- Doors and windows are cut out of their host wall as rectangular openings
  sized and positioned to match the model. Doors that reach the floor also
  remove any bottom fingers that would dangle in the opening.
- Optional **smooth wall connections**: walls don't interlock with each other,
  only with the floor (useful when corners will be glued or trimmed).
- Live preview of the cut layout in the options dialog with adjustable scale,
  configurable cut stroke color (default `#FF0000`), and on-the-fly size
  estimate.

## Building

You need a JDK (8 or newer) and Apache Ant.

1. Drop a `SweetHome3D.jar` into the `lib/` directory, **or** pass its path on
   the command line. On macOS the jar lives inside the app bundle:

   ```
   /Applications/Sweet Home 3D.app/Contents/Java/SweetHome3D.jar
   ```

2. Build:

   ```sh
   ant -Dsh3d.jar="/Applications/Sweet Home 3D.app/Contents/Java/SweetHome3D.jar"
   ```

3. The plugin file is written to `dist/LasercutExport.sh3p`.

## CI/CD

GitHub Actions builds this plugin automatically:

- On pull requests and pushes to `main`, CI compiles and uploads a workflow
  artifact named `LasercutExport-sh3p`.
- On tags matching `v*` (for example `v1.0.0`), CD rebuilds the plugin and
  publishes `dist/LasercutExport.sh3p` as a GitHub release asset.

To cut a release:

```sh
git tag v1.0.0
git push origin v1.0.0
```

## Installing

In Sweet Home 3D, open **File → Preferences → Plug-ins → Import…** and pick
`dist/LasercutExport.sh3p`. Restart Sweet Home 3D. The new entry appears
under the Tools menu.

Alternatively, drop the `.sh3p` file into Sweet Home 3D's `plugins` folder:

- macOS: `~/Library/Application Support/eTeks/Sweet Home 3D/plugins/`
- Linux: `~/.eteks/sweethome3d/plugins/`
- Windows: `%APPDATA%\eTeks\Sweet Home 3D\plugins\`

## Using

1. Select the level you want to export.
2. **Tools → Export to lasercut SVG…**
3. In the dialog, set:
   - **Scale 1:N** — divides the model down to a sheet-sized cut. A 5 m wall
     at 1:50 becomes 100 mm. The dialog shows a live "Estimated output:
     W × H mm" preview that updates as you type.
   - **Material thickness (mm)** — the consolidated thickness of every wall
     and the floor. All wall thicknesses in the model are ignored; the
     exporter assumes a uniform sheet. *Not affected by scale* — this is the
     real thickness of the sheet you're cutting.
   - **Box-joint finger width (mm)** — target width of each finger / slot.
     The actual width is rounded so an odd number of equal segments fits the
     edge exactly.
   - **Floor margin (mm)** — extra space around the wall footprints in the
     floor outline.
   - **Layout spacing (mm)** — gap between pieces in the SVG layout.
   - **SVG stroke width (mm)** — line weight; use a small value like `0.1`
     for hairline cuts.
   - **Smooth wall-to-wall connections** — when checked, walls have straight
     vertical edges and only the floor holds them in place.
4. Pick an output `.svg` file.

## How the geometry works

Sweet Home 3D walls are consolidated to one thickness `T` and laid flat:

- Each wall is a rectangle of `length × height` with **fingers protruding
  downward** by `T` at regular intervals along the bottom edge.
- The floor is a single rectangle covering all walls, with **rectangular slots
  cut through it** in the world-coordinate position of each wall's footprint.
  The slots match the wall's bottom-finger pattern exactly, so each finger
  passes through the floor.
- When two walls share an endpoint and *Smooth wall-to-wall connections* is
  off, both walls receive complementary finger patterns on their meeting
  edges. The finger of one wall fits into the gap of the other.
- Polarity (which wall gets gap-first vs. tab-first) is decided by
  `System.identityHashCode` order, which is stable within a session.

## Limitations

- Only straight walls are supported (arc walls are treated as straight from
  start to end).
- All walls are assumed to share the level height. Walls with explicit per-
  wall heights are emitted at that height, but joints assume matching
  heights at corners.
- The floor outline is a bounding rectangle of all wall footprints. Curved
  or stepped floor outlines aren't generated.
- Non-90° corners are not specially handled; finger joints assume
  perpendicular meetings. Acute / obtuse corners may need post-processing.
- Kerf compensation is not applied. Shrink / expand fingers in your CAM
  workflow if your laser has noticeable kerf.

## License

GPL v2 or later, matching the Sweet Home 3D plugin SDK.
