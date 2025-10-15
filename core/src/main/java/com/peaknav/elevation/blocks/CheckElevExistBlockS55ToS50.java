
package com.peaknav.elevation.blocks;

class CheckElevExistBlockS55ToS50 {
    private CheckElevExistBlockS55ToS50() {}

    public static boolean _checkElevExistS55ToS50(int lat, int lon) {
        if (lat == -55) {
            return _checkLatS55(lon);
        } else if (lat == -54) {
            return _checkLatS54(lon);
        } else if (lat == -53) {
            return _checkLatS53(lon);
        } else if (lat == -52) {
            return _checkLatS52(lon);
        } else if (lat == -51) {
            return _checkLatS51(lon);
        }
        return false;
    }

    private static boolean _checkLatS55(int lon) {
        if (lon == -74) {
            return true;
        } else if (lon == -73) {
            return true;
        } else if (lon == -72) {
            return true;
        } else if (lon == -71) {
            return true;
        } else if (lon == -70) {
            return true;
        } else if (lon == -69) {
            return true;
        } else if (lon == -68) {
            return true;
        } else if (lon == -67) {
            return true;
        } else if (lon == -66) {
            return true;
        } else if (lon == -65) {
            return true;
        } else if (lon == -64) {
            return true;
        } else if (lon == -39) {
            return true;
        } else if (lon == -38) {
            return true;
        } else if (lon == -37) {
            return true;
        } else if (lon == -36) {
            return true;
        } else if (lon == 3) {
            return true;
        } else if (lon == 158) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS54(int lon) {
        if (lon == -75) {
            return true;
        } else if (lon == -74) {
            return true;
        } else if (lon == -73) {
            return true;
        } else if (lon == -72) {
            return true;
        } else if (lon == -71) {
            return true;
        } else if (lon == -70) {
            return true;
        } else if (lon == -69) {
            return true;
        } else if (lon == -68) {
            return true;
        } else if (lon == -39) {
            return true;
        } else if (lon == -38) {
            return true;
        } else if (lon == 72) {
            return true;
        } else if (lon == 73) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS53(int lon) {
        if (lon == -76) {
            return true;
        } else if (lon == -75) {
            return true;
        } else if (lon == -74) {
            return true;
        } else if (lon == -73) {
            return true;
        } else if (lon == -72) {
            return true;
        } else if (lon == -71) {
            return true;
        } else if (lon == -70) {
            return true;
        } else if (lon == -69) {
            return true;
        } else if (lon == -62) {
            return true;
        } else if (lon == -61) {
            return true;
        } else if (lon == -60) {
            return true;
        } else if (lon == -59) {
            return true;
        } else if (lon == 73) {
            return true;
        } else if (lon == 169) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS52(int lon) {
        if (lon == -76) {
            return true;
        } else if (lon == -75) {
            return true;
        } else if (lon == -74) {
            return true;
        } else if (lon == -73) {
            return true;
        } else if (lon == -72) {
            return true;
        } else if (lon == -71) {
            return true;
        } else if (lon == -70) {
            return true;
        } else if (lon == -69) {
            return true;
        } else if (lon == -62) {
            return true;
        } else if (lon == -61) {
            return true;
        } else if (lon == -60) {
            return true;
        } else if (lon == -59) {
            return true;
        } else if (lon == -58) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS51(int lon) {
        if (lon == -76) {
            return true;
        } else if (lon == -75) {
            return true;
        } else if (lon == -74) {
            return true;
        } else if (lon == -73) {
            return true;
        } else if (lon == -72) {
            return true;
        } else if (lon == -71) {
            return true;
        } else if (lon == -70) {
            return true;
        } else if (lon == -69) {
            return true;
        } else if (lon == -68) {
            return true;
        } else if (lon == -62) {
            return true;
        } else if (lon == 165) {
            return true;
        } else if (lon == 166) {
            return true;
        }
        return false;
    }

}
