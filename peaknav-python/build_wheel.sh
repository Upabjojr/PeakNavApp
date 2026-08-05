#!/bin/sh
# Build the peaknav Python wheel into dist/.
#
# The wheel is versioned independently of the app (pyproject.toml [project]
# version, mirrored in peaknav/__init__.py __version__) — it only wraps the
# renderer jar, whose version it pins in peaknav/headless/jar.py JAR_VERSION.
#
# Needs the "build" package: python3 -m pip install build
set -e
cd "$(dirname "$0")"

# Fail early if the two version declarations have drifted apart.
PY_VERSION=$(python3 -c "import re; print(re.search(r'__version__ = \"([^\"]+)\"', open('peaknav/__init__.py').read()).group(1))")
TOML_VERSION=$(python3 -c "import tomllib; print(tomllib.load(open('pyproject.toml','rb'))['project']['version'])")
if [ "$PY_VERSION" != "$TOML_VERSION" ]; then
    echo "version mismatch: peaknav/__init__.py says $PY_VERSION, pyproject.toml says $TOML_VERSION" >&2
    exit 1
fi

rm -rf build dist peaknav.egg-info
python3 -m build --wheel
echo
echo "built:"
ls dist/*.whl
