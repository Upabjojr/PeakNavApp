
package com.peaknav.elevation.blocks;

class CheckElevExistBlockS50ToS45 {
    private CheckElevExistBlockS50ToS45() {}

    public static boolean _checkElevExistS50ToS45(int lat, int lon) {
        if (lat == -50) {
            return _checkLatS50(lon);
        } else if (lat == -49) {
            return _checkLatS49(lon);
        } else if (lat == -48) {
            return _checkLatS48(lon);
        } else if (lat == -47) {
            return _checkLatS47(lon);
        } else if (lat == -46) {
            return _checkLatS46(lon);
        }
        return false;
    }

    private static boolean _checkLatS50(int lon) {
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
        } else if (lon == 68) {
            return true;
        } else if (lon == 69) {
            return true;
        } else if (lon == 70) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS49(int lon) {
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
        } else if (lon == -67) {
            return true;
        } else if (lon == -66) {
            return true;
        } else if (lon == 68) {
            return true;
        } else if (lon == 69) {
            return true;
        } else if (lon == 166) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS48(int lon) {
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
        } else if (lon == -67) {
            return true;
        } else if (lon == -66) {
            return true;
        } else if (lon == 167) {
            return true;
        } else if (lon == 168) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS47(int lon) {
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
        } else if (lon == -67) {
            return true;
        } else if (lon == 37) {
            return true;
        } else if (lon == 38) {
            return true;
        } else if (lon == 50) {
            return true;
        } else if (lon == 51) {
            return true;
        } else if (lon == 52) {
            return true;
        } else if (lon == 166) {
            return true;
        } else if (lon == 167) {
            return true;
        } else if (lon == 168) {
            return true;
        } else if (lon == 169) {
            return true;
        } else if (lon == 170) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS46(int lon) {
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
        } else if (lon == -67) {
            return true;
        } else if (lon == -66) {
            return true;
        } else if (lon == 50) {
            return true;
        } else if (lon == 166) {
            return true;
        } else if (lon == 167) {
            return true;
        } else if (lon == 168) {
            return true;
        } else if (lon == 169) {
            return true;
        } else if (lon == 170) {
            return true;
        } else if (lon == 171) {
            return true;
        }
        return false;
    }

}
