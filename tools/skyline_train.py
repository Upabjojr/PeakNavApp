#!/usr/bin/env python3
"""Trains the sky/ground pixel classifier the photo skyline extractor uses.

The rows come from the app's own feature code::

    ./gradlew :core:skylineTrainingDump --args="geopose3k_manual/manifest.json rows.csv.gz"
    tools/skyline_train.py rows.csv.gz [more.csv.gz ...] -o core/src/main/resources/com/peaknav/skyline/sky_model.bin

A gradient-boosted forest of shallow trees (scikit-learn's histogram booster) is fitted,
validated on photos it never saw, and written in the little binary format
``SkyClassifier`` reads. ``--check check.csv`` also writes rows with the trainer's own
probabilities, which ``skylineTrainingDump --check`` compares with the Java evaluation.

``--exclude REGEX`` keeps matching photos out of training (for an honest benchmark on
them); ``--fold 0|1`` trains on half the photos (by a hash of their name), the other half
being the benchmark's; ``--fold-manual 0|1`` does that to the last file only.
"""
import argparse
import hashlib
import re
import struct
import sys

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier

MAGIC = 0x534B5931  # "SKY1"


def photo_hash(name):
    return int(hashlib.md5(name.encode()).hexdigest(), 16) % 2


def load(paths, exclude, fold):
    frames = []
    for p in paths:
        df = pd.read_csv(p)
        frames.append(df)
    df = pd.concat(frames, ignore_index=True)
    if exclude:
        rx = re.compile(exclude)
        keep = ~df["photo"].map(lambda s: bool(rx.search(str(s))))
        print(f"excluding {int((~keep).sum())} rows of photos matching {exclude!r}")
        df = df[keep]
    if fold is not None:
        keep = df["photo"].map(lambda s: photo_hash(str(s)) == fold)
        print(f"fold {fold}: keeping {int(keep.sum())} of {len(df)} rows")
        df = df[keep]
    return df.reset_index(drop=True)


def load_fold_manual(paths, fold):
    """All rows of every file but the last, plus the given fold of the last file."""
    frames = [pd.read_csv(p) for p in paths[:-1]]
    manual = pd.read_csv(paths[-1])
    keep = manual["photo"].map(lambda s: photo_hash(str(s)) == fold)
    print(f"manual fold {fold}: keeping {int(keep.sum())} of {len(manual)} rows of {paths[-1]}")
    frames.append(manual[keep])
    return pd.concat(frames, ignore_index=True)


def export(clf, feature_count, out):
    """Writes the forest; returns a function evaluating the raw score the way Java does."""
    baseline = float(np.ravel(clf._baseline_prediction)[0])
    trees = []
    for stage in clf._predictors:
        assert len(stage) == 1, "binary classifier expected"
        nodes = stage[0].nodes
        tree = []
        for nd in nodes:
            if nd["is_leaf"]:
                tree.append((-1, 0.0, 0, 0, float(nd["value"])))
            else:
                tree.append((int(nd["feature_idx"]), float(nd["num_threshold"]),
                             int(nd["left"]), int(nd["right"]), 0.0))
        trees.append(tree)
    with open(out, "wb") as f:
        f.write(struct.pack(">iifi", MAGIC, feature_count, baseline, len(trees)))
        for tree in trees:
            f.write(struct.pack(">i", len(tree)))
            for feat, thr, left, right, value in tree:
                f.write(struct.pack(">ifiif", feat, thr, left, right, value))
    total = sum(len(t) for t in trees)
    print(f"wrote {len(trees)} trees, {total} nodes, {4 * 4 + total * 20 + len(trees) * 4} bytes to {out}")

    def score(x):
        s = baseline
        for tree in trees:
            node = 0
            while tree[node][0] >= 0:
                feat, thr, left, right, _ = tree[node]
                node = left if x[feat] <= thr else right
            s += tree[node][4]
        return s

    return score


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("rows", nargs="+")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--check", help="write feature rows with probabilities for the Java parity check")
    ap.add_argument("--exclude", help="regex of photo names to keep out of training")
    ap.add_argument("--fold", type=int, choices=[0, 1])
    ap.add_argument("--fold-manual", type=int, choices=[0, 1],
                    help="train on all rows of the first files and this fold of the last file")
    ap.add_argument("--trees", type=int, default=150)
    ap.add_argument("--depth", type=int, default=6)
    ap.add_argument("--rate", type=float, default=0.1)
    ap.add_argument("--leaf", type=int, default=200)
    args = ap.parse_args()

    df = (load_fold_manual(args.rows, args.fold_manual) if args.fold_manual is not None
          else load(args.rows, args.exclude, args.fold))
    features = [c for c in df.columns if c not in ("photo", "label", "y", "ridge")]
    X = df[features].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy()
    photos = df["photo"].astype(str).to_numpy()
    print(f"{len(df)} rows from {len(set(photos))} photos, {len(features)} features, sky fraction {y.mean():.2f}")

    # validation split by photo, so the score is on unseen pictures
    val_mask = np.array([int(hashlib.md5(("v" + p).encode()).hexdigest(), 16) % 5 == 0 for p in photos])
    clf = HistGradientBoostingClassifier(max_iter=args.trees, max_depth=args.depth, learning_rate=args.rate,
                                         min_samples_leaf=args.leaf, early_stopping=False, random_state=1)
    clf.fit(X[~val_mask], y[~val_mask])
    p = clf.predict_proba(X[val_mask])[:, 1]
    acc = ((p > 0.5) == y[val_mask]).mean()
    near = np.abs(df["y"].to_numpy() - df["ridge"].to_numpy())[val_mask] < 0.06 * 300
    print(f"validation on {val_mask.sum()} rows of {len(set(photos[val_mask]))} unseen photos: "
          f"pixel accuracy {acc:.3f}, near the ridge {((p > 0.5) == y[val_mask])[near].mean():.3f}")

    # importance by permutation on the validation rows (cheap and honest)
    base = ((clf.predict_proba(X[val_mask])[:, 1] > 0.5) == y[val_mask]).mean()
    rng = np.random.default_rng(0)
    imp = []
    Xv = X[val_mask].copy()
    for k, name in enumerate(features):
        col = Xv[:, k].copy()
        rng.shuffle(Xv[:, k])
        a = ((clf.predict_proba(Xv)[:, 1] > 0.5) == y[val_mask]).mean()
        Xv[:, k] = col
        imp.append((base - a, name))
    print("importance (accuracy lost when shuffled):")
    for loss, name in sorted(imp, reverse=True):
        print(f"  {name:16s} {loss:+.4f}")

    # final fit on everything, then export
    clf.fit(X, y)
    score = export(clf, len(features), args.out)
    sample = X[:200]
    mine = np.array([score(x) for x in sample])
    theirs = clf.decision_function(sample)
    print(f"export check: largest raw-score difference vs scikit-learn {np.abs(mine - theirs).max():.2e}")
    if args.check:
        with open(args.check, "w") as f:
            f.write(",".join(features) + ",p\n")
            for x, s in zip(sample, mine):
                f.write(",".join(f"{v:.6g}" for v in x) + f",{1 / (1 + np.exp(-s)):.6f}\n")
        print(f"wrote {len(sample)} check rows to {args.check}")


if __name__ == "__main__":
    sys.exit(main())
