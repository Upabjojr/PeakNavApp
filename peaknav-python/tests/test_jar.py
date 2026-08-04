"""The renderer jar is found, not shipped: these cover the search and the download.

Nothing here touches the network. The download is exercised against a file:// URL, which
goes through exactly the same code as an https one - the checks that matter (is this a
renderer jar, does the digest match, is a partial file left behind) are about what
arrived, not about how.
"""

import hashlib
import os
import zipfile

import pytest

from peaknav.headless import jar as jar_module


def make_jar(path, entry_point=True):
    """A minimal file that passes, or deliberately fails, the renderer-jar check."""
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        if entry_point:
            archive.writestr("com/peaknav/headless/RenderCli.class", b"\xca\xfe\xba\xbe")
        else:
            archive.writestr("com/peaknav/viewer/MapApp.class", b"\xca\xfe\xba\xbe")
    return str(path)


@pytest.fixture
def isolated(tmp_path, monkeypatch):
    """A world with its own cache and no checkout to fall back on."""
    monkeypatch.setenv("PEAKNAV_CACHE_DIR", str(tmp_path / "cache"))
    monkeypatch.delenv("PEAKNAV_HEADLESS_JAR", raising=False)
    monkeypatch.delenv("PEAKNAV_NO_DOWNLOAD", raising=False)
    # These tests run inside the repository, where a built jar would otherwise win.
    monkeypatch.setattr(jar_module, "find_local_jar", lambda: None)
    return tmp_path


# ----------------------------------------------------------------- the search order

def test_an_explicit_path_is_used_as_given(isolated, tmp_path):
    mine = make_jar(tmp_path / "mine.jar")
    assert jar_module.resolve_jar(mine) == mine


def test_an_explicit_path_that_is_not_there_is_an_error(isolated):
    # Silently downloading a different renderer than the one that was named would be a
    # far worse answer than refusing.
    with pytest.raises(jar_module.JarNotFound, match="no jar at"):
        jar_module.resolve_jar("/no/such/renderer.jar")


def test_the_environment_points_a_whole_session_at_one_build(isolated, tmp_path,
                                                             monkeypatch):
    mine = make_jar(tmp_path / "session.jar")
    monkeypatch.setenv("PEAKNAV_HEADLESS_JAR", mine)
    assert jar_module.resolve_jar() == mine


def test_a_local_build_beats_the_cache(isolated, tmp_path, monkeypatch):
    # A developer testing a renderer change must get their own build, not the release.
    built = make_jar(tmp_path / "built.jar")
    monkeypatch.setattr(jar_module, "find_local_jar", lambda: built)
    make_jar(_prepare_cache(isolated))
    assert jar_module.resolve_jar() == built


def test_the_cache_is_used_when_there_is_no_build(isolated):
    cached = make_jar(_prepare_cache(isolated))
    assert jar_module.resolve_jar() == cached


def test_a_corrupt_cached_jar_is_not_trusted(isolated, monkeypatch):
    # Half a file in the cache must not be handed to a JVM as if it were a renderer.
    cached = _prepare_cache(isolated)
    with open(cached, "wb") as broken:
        broken.write(b"not a zip at all")
    monkeypatch.setenv("PEAKNAV_NO_DOWNLOAD", "1")
    with pytest.raises(jar_module.JarNotFound):
        jar_module.resolve_jar()


def test_downloading_can_be_forbidden(isolated, monkeypatch):
    monkeypatch.setenv("PEAKNAV_NO_DOWNLOAD", "1")
    with pytest.raises(jar_module.JarNotFound, match="PEAKNAV_NO_DOWNLOAD"):
        jar_module.resolve_jar()
    # And the same by argument, for a caller that does not want to set the environment.
    with pytest.raises(jar_module.JarNotFound, match="not allowed"):
        jar_module.resolve_jar(allow_download=False)


# --------------------------------------------------------------------- the download

def test_a_fetched_jar_lands_in_the_cache(isolated, tmp_path):
    source = make_jar(tmp_path / "release.jar")
    got = jar_module.ensure_jar("9.9.9", url="file://" + source, quiet=True)
    assert got == jar_module.cached_jar_path("9.9.9")
    assert zipfile.ZipFile(got).getinfo("com/peaknav/headless/RenderCli.class")
    # Second call is a cache hit: no URL needed at all.
    assert jar_module.ensure_jar("9.9.9", url="file:///nowhere", quiet=True) == got


def test_the_wrong_artifact_is_refused(isolated, tmp_path):
    # The desktop jar is published under a similar name and has no renderer in it.
    desktop = make_jar(tmp_path / "desktop.jar", entry_point=False)
    with pytest.raises(jar_module.JarNotFound, match="not a renderer jar"):
        jar_module.ensure_jar("9.9.9", url="file://" + desktop, quiet=True)
    assert not os.path.exists(jar_module.cached_jar_path("9.9.9"))


def test_a_truncated_download_is_refused(isolated, tmp_path):
    truncated = tmp_path / "truncated.jar"
    truncated.write_bytes(b"PK\x03\x04 and then the connection dropped")
    with pytest.raises(jar_module.JarNotFound, match="not a renderer jar"):
        jar_module.ensure_jar("9.9.9", url="file://" + str(truncated), quiet=True)


def test_a_digest_that_does_not_match_is_refused(isolated, tmp_path):
    source = make_jar(tmp_path / "release.jar")
    with pytest.raises(jar_module.JarNotFound, match="refusing to use it"):
        jar_module.ensure_jar("9.9.9", url="file://" + source, sha256="00" * 32,
                              quiet=True)
    assert not os.path.exists(jar_module.cached_jar_path("9.9.9"))


def test_the_pinned_digest_is_accepted(isolated, tmp_path):
    source = make_jar(tmp_path / "release.jar")
    with open(source, "rb") as handle:
        digest = hashlib.sha256(handle.read()).hexdigest()
    got = jar_module.ensure_jar("9.9.9", url="file://" + source, sha256=digest,
                                quiet=True)
    assert os.path.exists(got)


def test_a_refused_download_leaves_nothing_behind(isolated, tmp_path):
    # A rejected fetch must not leave a part-file that a later run mistakes for progress.
    desktop = make_jar(tmp_path / "desktop.jar", entry_point=False)
    with pytest.raises(jar_module.JarNotFound):
        jar_module.ensure_jar("9.9.9", url="file://" + desktop, quiet=True)
    leftovers = os.listdir(jar_module.cache_dir())
    assert leftovers == [], "left behind: %s" % leftovers


def test_a_missing_release_asset_says_so(isolated, tmp_path):
    with pytest.raises(jar_module.JarNotFound) as failure:
        jar_module.ensure_jar("9.9.9", url="file://" + str(tmp_path / "absent.jar"),
                              quiet=True)
    assert "could not fetch" in str(failure.value)


# ------------------------------------------------------------------------- the cache

def test_the_cache_follows_the_environment(tmp_path, monkeypatch):
    monkeypatch.setenv("PEAKNAV_CACHE_DIR", str(tmp_path / "elsewhere"))
    assert jar_module.cache_dir() == str(tmp_path / "elsewhere" / "jars")
    monkeypatch.delenv("PEAKNAV_CACHE_DIR")
    monkeypatch.setenv("XDG_CACHE_HOME", str(tmp_path / "xdg"))
    if os.name != "nt":
        assert jar_module.cache_dir() == str(tmp_path / "xdg" / "peaknav" / "jars")


def _prepare_cache(root):
    """The cached jar's path, with its directory made."""
    path = jar_module.cached_jar_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path
