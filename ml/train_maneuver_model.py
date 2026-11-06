#!/usr/bin/env python3
"""
Train an INDEPENDENT maneuver-icon classifier for Navigator.

Input:  ml/dataset/*.png  — Google Maps' own labeled maneuver icons, rendered
        white-on-transparent by Navigator's debug exporter (MapsIconExporter).
        Each filename IS its label, e.g. maneuver_turn_sharp_left.png.
Output: maneuver_model.tflite  + maneuver_labels.txt
        Drop both into app/src/main/res/raw/ ; Navigator loads them at runtime.

The model matches Navigator's on-device preprocessing exactly:
  composite on black -> 96x96 -> RGB float [0,1] -> CNN -> softmax over classes.
Each output class is a KTM dash TurnIcon *binary code* (as a string), so the app
maps a prediction straight through TurnIcon.fromBinary(). This is a clean-room
replacement for the proprietary model — trained only on Google's public,
self-labeled drawable set, so Navigator can ship free forever.

Run locally (pip install "tensorflow>=2.14" pillow numpy) or on Google Colab
(free GPU): upload the ml/ folder, then `python train_maneuver_model.py`.
"""
import glob, os, random
import numpy as np
from PIL import Image
import tensorflow as tf

HERE = os.path.dirname(os.path.abspath(__file__))
DATASET = os.path.join(HERE, "dataset")
INPUT = 96
PER_CLASS = 600          # augmented samples per CODE (balanced)
EPOCHS = 30

# --- Google Maps maneuver name -> KTM dash TurnIcon binary code -------------
# Dash codes (BccuProtocol.TurnIcon.binary): 2=GO_STRAIGHT 3/4=UTURN_R/L
# 5=KEEP_R 6=LIGHT_R 7=QUITE_R 8=HEAVY_R 10=KEEP_L 11=LIGHT_L 12=QUITE_L
# 13=HEAVY_L 14/15=ENTER_HWY_R/L 16/17=LEAVE_HWY_R/L 21=END
# 26..41=RAB_SECT_1..16_RH  42..57=RAB_SECT_1..16_LH
def dash_code(name: str) -> int:
    n = name.replace("maneuver_", "")
    L, R = "left" in n, "right" in n
    if n.startswith("roundabout"):
        rh = "_cw" in n                     # clockwise = right-hand traffic
        base = 26 if rh else 42             # RAB_SECT_1_RH / _LH
        # exit direction -> section index (1..16), matching the RH/LH bank
        if "u_turn" in n:  sect = 16
        elif "straight" in n: sect = 8
        elif "slight_right" in n: sect = 2
        elif "normal_right" in n: sect = 4
        elif "sharp_right" in n:  sect = 6
        elif "slight_left" in n:  sect = 10
        elif "normal_left" in n:  sect = 12
        elif "sharp_left" in n:   sect = 14
        else: sect = 8                       # generic enter/exit
        return base + (sect - 1)
    # IMPORTANT: Google Maps reuses the SAME bitmap for a turn and its
    # on-ramp / off-ramp / fork equivalent (verified byte-identical), so those
    # MUST share a dash code - a classifier can't give identical images
    # different labels. We therefore map by the icon's visual SHAPE, not its
    # highway semantics. (Only "merge" is a visually distinct symbol.)
    if "u_turn" in n:  return 3 if R else 4
    if "merge" in n:   return 14 if R else 15          # distinct merge symbol
    if "keep" in n or "fork" in n: return 5 if R else 10
    if "slight" in n:  return 6 if R else 11           # LIGHT (turn/on_ramp/off_ramp)
    if "normal" in n:  return 7 if R else 12           # QUITE
    if "sharp" in n:   return 8 if R else 13           # HEAVY
    if n.startswith("destination"): return 21          # END
    if n in ("depart", "straight", "name_change"): return 2  # GO_STRAIGHT
    return 2  # sensible default

# --- Augmentation: only 64 base icons, so synthesize realistic variants -----
def white_on_black(img: Image.Image) -> np.ndarray:
    # The exported icons are alpha masks (shape in the alpha channel, RGB=0), and
    # the real notification icon is a white glyph composited on black. Both reduce
    # to the SAME thing: the alpha channel AS a white intensity on black. Using
    # alpha here makes training match what the app feeds the model at runtime
    # (a real white icon drawn on black yields exactly this grayscale).
    alpha = np.asarray(img.split()[3], dtype=np.float32) / 255.0
    return np.stack([alpha, alpha, alpha], axis=-1)

def augment(img: Image.Image) -> Image.Image:
    # Gentle augmentation: the real notification icon is centred and upright, so
    # only mild scale/rotation/translation occurs in practice. Over-rotating
    # morphs a turn into a ramp-like shape and hurts fine distinctions.
    s = random.uniform(0.85, 1.1)
    w, h = img.size
    im = img.resize((max(8, int(w * s)), max(8, int(h * s))), Image.BILINEAR)
    im = im.rotate(random.uniform(-4, 4), expand=True, resample=Image.BILINEAR)
    # canvas is always at least as big as the (scaled+rotated) icon plus margin
    cw = int(max(w, im.size[0]) * 1.25)
    ch = int(max(h, im.size[1]) * 1.25)
    canvas = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
    ox = random.randint(0, cw - im.size[0])
    oy = random.randint(0, ch - im.size[1])
    canvas.alpha_composite(im, (ox, oy))
    arr = white_on_black(canvas.resize((INPUT, INPUT), Image.BILINEAR))
    # brightness/contrast jitter + mild noise (anti-aliasing / compression)
    arr = np.clip(arr * random.uniform(0.75, 1.0) + random.uniform(-0.03, 0.03), 0, 1)
    arr = np.clip(arr + np.random.normal(0, 0.02, arr.shape).astype(np.float32), 0, 1)
    return arr

def build_dataset():
    import collections
    files = sorted(glob.glob(os.path.join(DATASET, "*.png")))
    assert files, f"no icons in {DATASET} (run the EXPORT_MANEUVERS debug broadcast first)"
    # Group source icons by their dash code and generate an EQUAL number of
    # augmented samples per code (balanced classes) - otherwise codes that many
    # Maps icons map to (e.g. on-ramp variants) swamp single-icon codes like a
    # plain left turn, and the model mislabels turns as ramps.
    code_to_bases = collections.defaultdict(list)
    for f in files:
        code_to_bases[dash_code(os.path.basename(f)[:-4])].append(Image.open(f).convert("RGBA"))
    codes = sorted(code_to_bases)
    code_to_idx = {c: i for i, c in enumerate(codes)}
    X, y = [], []
    for code, bases in code_to_bases.items():
        for k in range(PER_CLASS):
            X.append(augment(bases[k % len(bases)]))
            y.append(code_to_idx[code])
    return np.array(X), np.array(y), codes

def main():
    X, y, codes = build_dataset()
    print(f"{len(X)} samples, {len(codes)} classes: {codes}")
    idx = np.random.permutation(len(X)); X, y = X[idx], y[idx]
    n_val = len(X) // 10

    model = tf.keras.Sequential([
        tf.keras.layers.Input((INPUT, INPUT, 3)),
        tf.keras.layers.Conv2D(16, 3, activation="relu"), tf.keras.layers.MaxPool2D(),
        tf.keras.layers.Conv2D(32, 3, activation="relu"), tf.keras.layers.MaxPool2D(),
        tf.keras.layers.Conv2D(64, 3, activation="relu"), tf.keras.layers.MaxPool2D(),
        # Flatten (NOT GlobalAveragePooling): spatial position is what tells a
        # left arrow from its mirror-image right arrow. GAP averages that away.
        tf.keras.layers.Flatten(),
        tf.keras.layers.Dense(128, activation="relu"),
        tf.keras.layers.Dropout(0.3),
        tf.keras.layers.Dense(len(codes), activation="softmax"),
    ])
    model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    model.fit(X[n_val:], y[n_val:], validation_data=(X[:n_val], y[:n_val]),
              epochs=EPOCHS, batch_size=64)

    # Export via SavedModel then convert — the from_keras_model path crashes the
    # MLIR converter on TF 2.16 / Keras 3.
    sm_dir = os.path.join(HERE, "_saved_model")
    model.export(sm_dir)
    conv = tf.lite.TFLiteConverter.from_saved_model(sm_dir)
    tflite = conv.convert()
    out_model = os.path.join(HERE, "maneuver_model.tflite")
    out_labels = os.path.join(HERE, "maneuver_labels.txt")
    with open(out_model, "wb") as fp:
        fp.write(tflite)
    with open(out_labels, "w") as fp:
        fp.write("\n".join(str(c) for c in codes) + "\n")
    print(f"wrote {out_model} ({len(tflite)} bytes) and {out_labels}")
    print("Copy both into app/src/main/res/raw/ (maneuver_model.tflite, maneuver_labels.txt)")

if __name__ == "__main__":
    main()
