#!/bin/bash
# Encode every videos/frames/<name>.<N>f/ JPEG sequence into videos/webm/<name>.webm
# (VP9, two-pass, constant quality, 1280x720 @ 30 fps: a standard YouTube
# 720p upload). Encoding from the frames rather than the mp4 avoids a second
# generation loss; the 1600x900 frames are 16:9, so the scale is a clean 0.8x.
#
# Usage: frames_to_webm.sh [--force] [GLOB...]
#   GLOB     restrict to frame dirs whose name matches (e.g. 'zermatt*' '*.peak-labels')
#   --force  re-encode even if the .webm already exists (default: skip it)
#
# Safe to interrupt and rerun (finished files are skipped, half-written ones are
# discarded), and safe to run twice at once: each file is locked while it is
# being encoded, so a second run just takes the next free one.
#
# Tunables (environment):
#   CRF=40           VP9 constant-quality level (lower = bigger/better; 31..44 sensible)
#   WORKERS=2        parallel encodes; each one uses THREADS threads
#   THREADS=4        libvpx scales poorly past ~4 threads, so prefer more WORKERS
#   FPS=30           frame rate of the JPEG sequences and of the output. YouTube
#                    accepts 24/25/30/48/50/60 and wants the source rate kept.
#   SIZE=1280x720    output resolution (16:9 YouTube sizes: 1280x720, 1920x1080,
#                    2560x1440, 3840x2160); scaled with lanczos, sharp on lines/text.
#                    720p is a downscale of the 1600x900 frames; 1080p would only
#                    upscale them (bigger files, no extra detail).
set -euo pipefail

cd "$(dirname "$0")/videos"
command -v ffmpeg >/dev/null || { echo "error: ffmpeg not found" >&2; exit 1; }
command -v flock >/dev/null || { echo "error: flock (util-linux) not found" >&2; exit 1; }

export CRF="${CRF:-40}" THREADS="${THREADS:-4}" FPS="${FPS:-30}" SIZE="${SIZE:-1280x720}" FORCE=0
WORKERS="${WORKERS:-2}"
globs=()
for a in "$@"; do
    case "$a" in
        --force) FORCE=1 ;;
        *) globs+=("$a") ;;
    esac
done
[ ${#globs[@]} -eq 0 ] && globs=('*')
mkdir -p webm

encode_one() {
    local dir="$1" name out tmp log lock start
    name=$(basename "$dir"); name=${name%.*f}
    out="webm/$name.webm"; tmp="webm/.$name.tmp.webm"
    log="webm/.$name.2pass"; lock="webm/.$name.lock"
    if [ "$FORCE" = 0 ] && [ -s "$out" ]; then
        echo "skip  $out (exists; use --force to redo)"; return 0
    fi
    exec 9>"$lock"
    if ! flock -n 9; then
        echo "busy  $out (another run is encoding it)"; return 0
    fi
    # This shell owns the file now: never leave a partial output or stale
    # pass log behind, whatever happens. (Expand the paths now: the locals
    # are gone by the time the EXIT trap runs.)
    trap "rm -f '$tmp' '$log'-*.log '$lock'" EXIT
    local vp9=(-vf "scale=${SIZE/x/:}:flags=lanczos" -r "$FPS"
               -c:v libvpx-vp9 -b:v 0 -crf "$CRF" -deadline good -cpu-used 2
               -row-mt 1 -tile-columns 2 -threads "$THREADS" -g 240
               -auto-alt-ref 1 -lag-in-frames 25 -pix_fmt yuv420p -an)
    start=$(date +%s)
    ffmpeg -y -loglevel error -framerate "$FPS" -i "$dir/f%05d.jpg" "${vp9[@]}" \
        -pass 1 -passlogfile "$log" -f null /dev/null
    ffmpeg -y -loglevel error -framerate "$FPS" -i "$dir/f%05d.jpg" "${vp9[@]}" \
        -pass 2 -passlogfile "$log" "$tmp"
    mv "$tmp" "$out"
    echo "wrote $out ($(stat -c %s "$out" | awk '{printf "%.1f", $1/1e6}') MB, $(( $(date +%s) - start ))s)"
}
export -f encode_one

dirs=()
for g in "${globs[@]}"; do
    for d in frames/$g.*f frames/$g; do
        [ -d "$d" ] && [ -f "$d/f00000.jpg" ] && dirs+=("$d")
    done
done
[ ${#dirs[@]} -eq 0 ] && { echo "no frame dirs match: ${globs[*]}" >&2; exit 1; }

# Several dirs can share a name and differ only in the frame count (e.g. a
# 12-frame --probe render next to the real 1080-frame one). They would all
# write the same .webm, so keep only the longest one per name and say so.
mapfile -t dirs < <(
    for d in "${dirs[@]}"; do
        n=${d##*.}; n=${n%f}; echo "${d%.*f} $n $d"
    done | sort -u | sort -k1,1 -k2,2nr | awk '
        $1 == prev { printf "warning: ignoring %s (%s has more frames)\n", $3, keep > "/dev/stderr"; next }
        { prev = $1; keep = $3; print $3 }')

printf '%s\n' "${dirs[@]}" | tr '\n' '\0' \
    | xargs -0 -P "$WORKERS" -n 1 bash -c 'encode_one "$1"' _
echo "done: $(ls webm/*.webm | wc -l) webm files, $(du -sh webm | cut -f1) total"
