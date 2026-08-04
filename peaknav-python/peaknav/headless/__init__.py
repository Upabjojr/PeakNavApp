"""Client for the PeakNav renderer running off-screen - needs the headless jar.

The jar is 75 MB and so is not shipped inside the wheel: it is looked for in a local
build, then in a cache, and fetched once from the release if it is in neither. See
:mod:`peaknav.headless.jar` for the search order and the ways to steer it, and
``client.py`` for the renderer API itself. Nothing here needs more than the standard
library.

Note: no release carries the renderer jar yet, so until one does the way to use this is
to build it (``./gradlew :headless:renderJar`` in a PeakNavApp checkout) or to point
``$PEAKNAV_HEADLESS_JAR`` at a jar you already have.
"""

from .client import PeakNavError, PeakNavHeadless
from .jar import JarNotFound, ensure_jar, resolve_jar

__all__ = ["PeakNavHeadless", "PeakNavError", "JarNotFound", "ensure_jar", "resolve_jar"]
