"""Finding the renderer jar: a build tree, a cache, or the published release.

The renderer is 75 MB of Java, which is no way to ship a Python package - a wheel that
size would be downloaded by everyone who only wanted :mod:`peaknav.terrain`, which is
pure Python. So the jar is fetched on demand instead, once, into a cache directory, and
every later run finds it there.

The search order, first hit wins:

1. the ``jar=`` argument, when the caller names a file themselves;
2. ``$PEAKNAV_HEADLESS_JAR``, for pointing a whole session at one build;
3. ``headless/build/libs/`` of a PeakNavApp checkout, when this package is being used
   from inside the repository - a developer's own build must always win over a download,
   or testing a change would silently run the released renderer instead;
4. the cache, ``$XDG_CACHE_HOME/peaknav/jars`` (``%LOCALAPPDATA%`` on Windows);
5. the release asset, downloaded into that cache.

Set ``$PEAKNAV_NO_DOWNLOAD`` to any value to forbid step 5 - useful on a build machine
that should never reach the network, and it turns a silent 75 MB download into an error
that says what is missing.

The download is verified before it is installed into the cache: against a pinned SHA-256
when one is known for that version (see :data:`KNOWN_SHA256`, or set
``$PEAKNAV_HEADLESS_JAR_SHA256``), and always structurally - it must be a readable zip
containing the renderer's entry point. The structural check is what catches the ordinary
failures, a truncated download or an HTML error page saved as a jar; the digest is what
makes it tamper-evident, so pin one for every release you publish.
"""

import hashlib
import os
import sys
import zipfile

__all__ = ["ensure_jar", "resolve_jar", "cache_dir", "cached_jar_path",
           "find_local_jar", "JAR_VERSION", "RELEASE_URL_TEMPLATE", "KNOWN_SHA256"]


class JarNotFound(RuntimeError):
    """No renderer jar could be found, and none could be fetched."""


#: The release whose renderer this client expects. Pinned rather than "latest": the
#: client and the renderer speak a versioned HTTP API, and a silently newer renderer is
#: how a script that worked yesterday breaks today.
#:
#: 1.2.0 is the first release that carries a headless jar - earlier releases ship only
#: ``peaknav-<version>.jar``, the desktop application, which has no renderer inside it.
#: Raise this in step with each release being published, and pin its digest in
#: :data:`KNOWN_SHA256` at the same time.
JAR_VERSION = "1.2.0"

#: Where a released renderer lives. The asset name is canonical - ``peaknav-<version>.jar``
#: is the DESKTOP jar and does not contain the renderer at all.
RELEASE_URL_TEMPLATE = ("https://github.com/Upabjojr/PeakNavApp/releases/download/"
                        "{version}/peaknav-headless-{version}.jar")

#: Known-good digests, by version. A version absent from here still downloads (over
#: HTTPS, structurally checked) but cannot be proven byte-for-byte; fill it in as part of
#: publishing a release, from ``sha256sum peaknav-headless-<version>.jar``.
KNOWN_SHA256 = {
    "1.2.0": "495dbf480f449b1a6e054122239131316c8e7346ee3038d465465c5792edc727",
}

#: The entry point every renderer jar has. Its presence is what tells a renderer jar from
#: the desktop jar, from a half-downloaded file, and from an error page.
_ENTRY_POINT = "com/peaknav/headless/RenderCli.class"

_DOWNLOAD_TIMEOUT_S = 60


def cache_dir():
    """The directory holding downloaded jars.

    ``$PEAKNAV_CACHE_DIR`` overrides it outright; otherwise the platform's usual cache
    location, so a cleaner that empties caches is free to remove it - nothing here cannot
    be fetched again.

    >>> os.path.basename(cache_dir())
    'jars'
    """
    override = os.environ.get("PEAKNAV_CACHE_DIR")
    if override:
        return os.path.join(override, "jars")
    if sys.platform == "win32":
        base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    else:
        base = (os.environ.get("XDG_CACHE_HOME")
                or os.path.join(os.path.expanduser("~"), ".cache"))
    return os.path.join(base, "peaknav", "jars")


def cached_jar_path(version=JAR_VERSION):
    """Where the jar for ``version`` is kept once fetched.

    >>> cached_jar_path("9.9.9").endswith("peaknav-headless-9.9.9.jar")
    True
    """
    return os.path.join(cache_dir(), "peaknav-headless-%s.jar" % version)


def find_local_jar():
    """The jar built in this checkout, or ``None`` when not inside one.

    Newest wins, so rebuilding while a script is being written does the obvious thing.
    """
    here = os.path.abspath(__file__)
    for _ in range(4):   # peaknav-python/peaknav/headless/jar.py -> repository root
        here = os.path.dirname(here)
    import glob
    built = glob.glob(os.path.join(here, "headless", "build", "libs",
                                   "*-headless-*.jar"))
    if not built:
        return None
    return max(built, key=os.path.getmtime)


def _is_renderer_jar(path):
    """Is this file a readable renderer jar, rather than a truncated or wrong download?"""
    try:
        with zipfile.ZipFile(path) as archive:
            archive.getinfo(_ENTRY_POINT)
        return True
    except (zipfile.BadZipFile, KeyError, OSError):
        return False


def _digest(path):
    """The file's SHA-256, read in chunks - this is a 75 MB file."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_jar(version=JAR_VERSION, *, url=None, sha256=None, quiet=False):
    """Returns the cached jar for ``version``, downloading it if it is not there yet.

    The download goes to a temporary file beside its destination and is moved into place
    only once it has passed its checks, so an interrupted or corrupt fetch leaves no file
    that a later run would trust. Two processes racing to fetch the same version is safe:
    each writes its own temporary file and the rename is atomic.

    :param url: where to fetch from, overriding the release URL for this version.
    :param sha256: the digest to require, overriding :data:`KNOWN_SHA256`.
    :param quiet: suppress the one-line note that a large download has started.
    :returns: the path to a verified jar.
    :raises JarNotFound: if downloading is forbidden, or the fetch or its checks fail.

    >>> ensure_jar()                                        # doctest: +SKIP
    '/home/you/.cache/peaknav/jars/peaknav-headless-1.2.0.jar'
    """
    destination = cached_jar_path(version)
    if os.path.exists(destination) and _is_renderer_jar(destination):
        return destination

    if os.environ.get("PEAKNAV_NO_DOWNLOAD"):
        raise JarNotFound(
            "no renderer jar, and downloading is off ($PEAKNAV_NO_DOWNLOAD). Build one "
            "with './gradlew :headless:renderJar' or point $PEAKNAV_HEADLESS_JAR at a jar.")

    source = url or os.environ.get("PEAKNAV_HEADLESS_JAR_URL") \
        or RELEASE_URL_TEMPLATE.format(version=version)
    expected = sha256 or os.environ.get("PEAKNAV_HEADLESS_JAR_SHA256") \
        or KNOWN_SHA256.get(version)

    os.makedirs(os.path.dirname(destination), exist_ok=True)
    partial = destination + ".%d.part" % os.getpid()
    if not quiet:
        print("peaknav: fetching the renderer (~75 MB, once) from %s" % source,
              file=sys.stderr, flush=True)
    try:
        import urllib.error
        import urllib.request
        try:
            with urllib.request.urlopen(source, timeout=_DOWNLOAD_TIMEOUT_S) as response, \
                    open(partial, "wb") as out:
                while True:
                    block = response.read(1 << 20)
                    if not block:
                        break
                    out.write(block)
        except urllib.error.HTTPError as failed:
            if failed.code == 404:
                raise JarNotFound(
                    "release %s carries no renderer jar (%s). The desktop jar published "
                    "as peaknav-%s.jar is a different artifact and will not work. Build "
                    "one with './gradlew :headless:renderJar', or set "
                    "$PEAKNAV_HEADLESS_JAR to a jar you already have."
                    % (version, source, version)) from failed
            raise JarNotFound("could not fetch the renderer from %s: %s"
                              % (source, failed)) from failed
        except OSError as failed:      # network down, DNS, TLS, disk full
            raise JarNotFound("could not fetch the renderer from %s: %s"
                              % (source, failed)) from failed

        if expected:
            got = _digest(partial)
            if got != expected:
                raise JarNotFound(
                    "the renderer downloaded from %s has digest %s, not the expected %s "
                    "- refusing to use it" % (source, got, expected))
        if not _is_renderer_jar(partial):
            raise JarNotFound(
                "what %s served is not a renderer jar (no %s inside): a truncated "
                "download, or the wrong asset" % (source, _ENTRY_POINT))
        os.replace(partial, destination)
    finally:
        if os.path.exists(partial):
            os.remove(partial)
    return destination


def resolve_jar(explicit=None, *, allow_download=True, version=JAR_VERSION):
    """The renderer jar to run, by the search order documented for this module.

    :param explicit: a path given by the caller; used as-is, and an error if it is not
        there - naming a file and silently getting a different one is worse than failing.
    :param allow_download: when false, stop at the cache instead of fetching.
    :raises JarNotFound: when nothing is available.

    >>> resolve_jar("/no/such/renderer.jar")
    Traceback (most recent call last):
        ...
    peaknav.headless.jar.JarNotFound: no jar at /no/such/renderer.jar
    """
    if explicit:
        if not os.path.exists(explicit):
            raise JarNotFound("no jar at %s" % explicit)
        return explicit

    from_env = os.environ.get("PEAKNAV_HEADLESS_JAR")
    if from_env:
        if not os.path.exists(from_env):
            raise JarNotFound("$PEAKNAV_HEADLESS_JAR points at %s, which does not exist"
                              % from_env)
        return from_env

    built = find_local_jar()
    if built:
        return built

    cached = cached_jar_path(version)
    if os.path.exists(cached) and _is_renderer_jar(cached):
        return cached

    if not allow_download:
        raise JarNotFound(
            "no renderer jar in this checkout or in %s, and downloading was not allowed"
            % cache_dir())
    return ensure_jar(version)
