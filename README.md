# Sweet Home 3D Laser Cut Export Plugin

A Sweet Home 3D plugin that exports a level as an SVG file ready for laser
cutting. Walls become interlocking pieces with box-joint fingers on the
bottom edge that slot into the floor plate, with optional finger joints where
walls meet each other.

You go from this:

![Floor Plan](./images/sw3d_floor.png)

To this:

![Export](./images/export.png)

## Features

- Adds **Tools → Export to lasercut SVG…** to Sweet Home 3D.
- Exports the currently selected level (or all walls if there are no levels).
- Consolidates all walls to a single user-specified material thickness.
- Generates one wall piece per wall and one combined floor plate.
- Floor plate has rectangular slots aligned with each wall's bottom fingers.
- Doors and windows are cut out of their host wall as rectangular openings
  sized and positioned to match the model. Doors that reach the floor also
  remove any bottom fingers that would dangle in the opening.
- **Sloping wall support**: walls with different start and end heights get a
  diagonal top edge. Where a sloping wall meets another wall you can choose
  between **Compensate** (tabs clipped to the shorter height so they always
  fit) and **Smooth** (no tabs on the sloping end, glue/trim instead).
- **T-junction notches**: when a wall butts into the face of another wall the
  receiving wall gets matching slots so both pieces interlock.
- **Wall placement guides**: gray reference outlines on the floor plate show
  exactly where each wall sits, making assembly easier.
- Optional **smooth wall connections**: walls have straight vertical edges and
  only the floor holds them in place (useful when corners will be glued or
  trimmed rather than interlocked).
- **Board size layout**: set a physical board size and the exporter
  automatically packs all pieces onto as many boards as needed using a
  MaxRects bin-packing algorithm. Pieces are rotated 90° when that produces
  a better fit. Each board is written to its own SVG file
  (`<name>-board1.svg`, `<name>-board2.svg`, …). When no board size is set
  all pieces land in a single file.
- **Locking seam tabs**: when the floor plate spans multiple boards the seam
  between tiles uses a dovetail puzzle-piece profile so assembled tiles can't
  be pulled apart sideways.
- Live preview of the cut layout in the options dialog, including board
  boundaries, piece count, and board dimensions.
- Configurable cut stroke color (default `#FF0000`) and SVG stroke width.

## Install

Grab the latest pre-built plugin here: [Download `LasercutExport.sh3p`](https://github.com/DrSkunk/sh3d-lasercut-export/releases/latest/download/LasercutExport.sh3p)

Then in Sweet Home 3D, open **File → Preferences → Plug-ins → Import…** and
pick the downloaded `LasercutExport.sh3p`. Restart Sweet Home 3D. The new
entry appears under the Tools menu.

Alternatively, drop the `.sh3p` file into Sweet Home 3D's `plugins` folder:

- macOS: `~/Library/Application Support/eTeks/Sweet Home 3D/plugins/`
- Linux: `~/.eteks/sweethome3d/plugins/`
- Windows: `%APPDATA%\eTeks\Sweet Home 3D\plugins\`

## Using

1. Select the level you want to export.
2. **Tools → Export to lasercut SVG…**
3. In the dialog, set:
   - **Scale 1:N**: divides the model down to sheet size. A 5 m wall at 1:50
     becomes 100 mm. The live preview updates as you type.
   - **Material thickness (mm)**: the consolidated thickness of every wall
     and the floor. All wall thicknesses in the model are ignored; the
     exporter assumes a uniform sheet. _Not affected by scale_: this is the
     real physical thickness of the sheet you're cutting.
   - **Box-joint finger width (mm)**: target width of each finger / slot.
     The actual width is rounded so an odd number of equal segments fits the
     edge exactly.
   - **Floor margin (mm)**: extra space around the wall footprints in the
     floor outline.
   - **Layout spacing (mm)**: gap between pieces in the SVG layout.
   - **SVG stroke width (mm)**: line weight; use a small value like `0.1`
     for hairline cuts.
   - **Stroke color**: the cut line color written into the SVG (default red).
   - **Board width / Board height (mm)**: physical dimensions of your laser
     bed. Leave blank to output all pieces in a single unbounded file.
   - **Separate file per board**: when board dimensions are set, write one
     SVG per board instead of one combined file.
   - **Sloping wall mode** _(Compensate / Smooth)_: controls how tab height
     is handled where a sloping wall meets another wall.
   - **Smooth wall-to-wall connections**: when checked, walls have straight
     vertical edges and only the floor holds them in place.
4. Pick an output `.svg` file and click **Export**.

## How the geometry works

Sweet Home 3D walls are consolidated to one thickness `T` and laid flat:

- Each wall is a rectangle of `length × height` with **fingers protruding
  downward** by `T` at regular intervals along the bottom edge.
- Sloping walls (different start and end heights) get a trapezoidal outline
  with a diagonal top edge.
- The floor plate covers all wall footprints. **Rectangular slots** are cut
  through it at each wall's world-coordinate position; the slots match the
  wall finger pattern exactly so each finger passes through the floor.
- When two walls share an endpoint and _Smooth wall-to-wall connections_ is
  off, both walls receive complementary finger patterns on their meeting
  edges. The finger of one wall fits into the gap of the other.
- At T-junctions (one wall butting into the face of another) the receiving
  wall gets notch slots that accept the butting wall's fingers.
- Polarity (which wall gets gap-first vs. tab-first at a shared corner) is
  decided by `System.identityHashCode` order, which is stable within a
  session.
- When board dimensions are set, pieces are arranged with **MaxRects BSSF**
  (Best Short Side Fit) bin packing. Each piece is tried in its natural
  orientation and rotated 90°; whichever fits more tightly is used. When
  the floor plate spans a board boundary it is split into tiles joined by
  dovetail puzzle-piece tabs.

## Limitations

- Only straight walls are supported (arc walls are treated as straight from
  start to end).
- Non-90° corners are not specially handled; finger joints assume
  perpendicular meetings. Acute / obtuse corners may need post-processing.
- Kerf compensation is not applied. Shrink / expand fingers in your CAM
  workflow if your laser has noticeable kerf.
- The floor outline follows the wall footprints; complex concave room shapes
  may produce an oversized floor plate.

## License

GPL v2 or later, matching the Sweet Home 3D plugin SDK.

---

## Build from source

You need a JDK (8 or newer) and Apache Ant (or use the bundled `Makefile`).

1. Drop a `SweetHome3D.jar` into the `lib/` directory, **or** pass its path on
   the command line. On macOS the jar lives inside the app bundle:

   ```
   /Applications/Sweet Home 3D.app/Contents/Java/SweetHome3D.jar
   ```

2. Build:

   ```sh
   make # uses lib/SweetHome3D.jar if present
   # or
   ant -Dsh3d.jar="/Applications/Sweet Home 3D.app/Contents/Java/SweetHome3D.jar"
   ```

3. The plugin file is written to `dist/LasercutExport.sh3p`, which you can install with `make install`.

Other Makefile targets:

| Target              | Description                                                                                                      |
| ------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `make clean`        | Remove `build/` and `dist/`.                                                                                     |
| `make install`      | Build (if needed) and copy the `.sh3p` into your local Sweet Home 3D plugins folder (path auto-detected per OS). |
| `make reinstall`    | Shorthand for `make clean install`.                                                                              |
| `make run`          | Launch Sweet Home 3D (macOS only).                                                                               |
| `make print-config` | Show the resolved values of `SH3D_JAR`, `SH3D_PLUGIN_DIR`, and the output `.sh3p` path                           |
