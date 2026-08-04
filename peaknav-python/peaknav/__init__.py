"""Python tools for PeakNav, the 3D mountain viewer.

Two submodules, split by what they need:

``peaknav.terrain``
    Pure Python. Elevation of any coordinate on Earth, from the same compressed
    ASTER dataset the app renders - downloaded tile by tile and cached locally.
    Needs Pillow and a network connection on first use of an area; nothing else.

        >>> from peaknav.terrain import elevation_at
        >>> elevation_at(45.9763, 7.6586)        # the Matterhorn  # doctest: +SKIP
        4478

``peaknav.headless``
    A client for the real PeakNav renderer running off-screen - camera control,
    view options, rendered frames. Needs the headless jar (built from the PeakNav
    repository with ``./gradlew :headless:renderJar``) and a Java 17 runtime.

        >>> from peaknav.headless import PeakNavHeadless
        >>> with PeakNavHeadless(45.9763, 7.6586) as nav:    # doctest: +SKIP
        ...     nav.look(bearing_deg=230, pitch_deg=-4)
        ...     nav.save_frame("matterhorn.png")

The split is deliberate: scripts that only need "how high is this point" should
not pay for a JVM, and the two halves share nothing but this namespace.
"""

__version__ = "0.1.0"

__all__ = ["__version__"]
