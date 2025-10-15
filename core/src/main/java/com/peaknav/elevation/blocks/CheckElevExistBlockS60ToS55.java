
package com.peaknav.elevation.blocks;

class CheckElevExistBlockS60ToS55 {
    private CheckElevExistBlockS60ToS55() {}

    public static boolean _checkElevExistS60ToS55(int lat, int lon) {
        if (lat == -60) {
            return _checkLatS60(lon);
        } else if (lat == -59) {
            return _checkLatS59(lon);
        } else if (lat == -58) {
            return _checkLatS58(lon);
        } else if (lat == -57) {
            return _checkLatS57(lon);
        } else if (lat == -56) {
            return _checkLatS56(lon);
        }
        return false;
    }

    private static boolean _checkLatS60(int lon) {
        if (lon == -28) {
            return true;
        } else if (lon == -27) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS59(int lon) {
        if (lon == -27) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS58(int lon) {
        if (lon == -27) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS57(int lon) {
        if (lon == -28) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS56(int lon) {
        if (lon == -72) {
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
        } else if (lon == -35) {
            return true;
        } else if (lon == 158) {
            return true;
        }
        return false;
    }

}
