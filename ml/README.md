# Independent maneuver-icon model for Navigator

This trains Navigator's turn-icon recogniser from scratch, so the app can ship
**free forever** with no proprietary model. It recognises the nav app's maneuver
icon (Google Maps, etc.) and outputs the KTM dash turn-icon code — including
every roundabout exit — which a pixel heuristic can't do.

## Why this is clean-room / publishable
Google Maps ships its maneuver icons as **named** drawables
(`maneuver_turn_sharp_left`, `maneuver_roundabout_enter_and_exit_cw_slight_right`,
…). The filename *is* the ground-truth label, so we get a perfectly-labeled
dataset with zero hand-labeling and never touch anyone's trained weights.

## 1. Build the dataset (already done once, committed in `dataset/`)
On a phone/emulator with Google Maps installed and a debug Navigator build:

```
adb shell am start -n com.navigator.app/.ui.MainActivity
adb shell am broadcast -a com.navigator.app.EXPORT_MANEUVERS
adb exec-out run-as com.navigator.app tar c files/maneuver_dataset | tar x -C ml/
```

`MapsIconExporter` renders each `maneuver_*` drawable from the installed Maps
package, tinted white (matching the notification), to `dataset/<name>.png`.
Re-run after a Google Maps update to refresh the icons. To cover more nav apps,
extend `MANEUVER_NAMES` / the package in `MapsIconExporter` and re-export.

## 2. Train (local or free Google Colab)
```
pip install "tensorflow>=2.14" pillow numpy
python train_maneuver_model.py
```
Colab: upload the `ml/` folder, run the same command (free GPU). Produces:
- `maneuver_model.tflite` — 96×96×3 input, softmax over the dash-code classes
- `maneuver_labels.txt`   — class order (one dash TurnIcon binary code per line)

The script maps each Maps maneuver name → dash code (`dash_code()`), then
augments each icon (scale/rotate/translate/brightness/noise, composited on
black — identical to the app's preprocessing) into ~400 samples/class.

## 3. Drop into the app
Copy both files into `app/src/main/res/raw/`:
```
cp maneuver_model.tflite  ../app/src/main/res/raw/maneuver_model.tflite
cp maneuver_labels.txt    ../app/src/main/res/raw/maneuver_labels.txt
```
`ManeuverClassifier` loads the labels from `res/raw/maneuver_labels.txt` at
runtime (falling back to an embedded list), so no code change is needed —
rebuild and the app uses your model. A numeric label maps straight through
`TurnIcon.fromBinary()`.

## OCR (complementary, optional)
The icon model handles direction. Two places OCR helps as a *secondary* signal:
- **Roundabout exit number**: Maps sometimes prints the exit digit inside the
  roundabout icon. On-device ML Kit text recognition on the icon crop could read
  it to pick the exact `RAB_SECT_n` rather than a generic roundabout.
- **Instruction text**: when a nav app *does* put words in the notification
  ("At the roundabout, take the 2nd exit", "Sharp left"), Navigator already parses
  that in `TurnIconHeuristic.parseManeuverFromText` — no OCR needed there.
OCR of the icon glyph itself is not useful (the arrows carry no text). Keep the
CNN as the primary path; add ML Kit text recognition only for the exit-number
refinement if real logs show Maps rendering the digit.

## Resolution order in the app (`TurnIconHeuristic.guessDirection`)
1. maneuver text parse (Waze/OsmAnd/etc. that emit words)
2. user manual calibration (exact per-bitmap override)
3. **this TFLite model** (primary for Google Maps' bitmap-only icons)
4. ink heuristic (last resort)
