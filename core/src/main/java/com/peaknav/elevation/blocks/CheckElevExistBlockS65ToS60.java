
package com.peaknav.elevation.blocks;

class CheckElevExistBlockS65ToS60 {
    private CheckElevExistBlockS65ToS60() {}

    public static boolean _checkElevExistS65ToS60(int lat, int lon) {
        if (lat == -65) {
            return _checkLatS65(lon);
        } else if (lat == -64) {
            return _checkLatS64(lon);
        } else if (lat == -63) {
            return _checkLatS63(lon);
        } else if (lat == -62) {
            return _checkLatS62(lon);
        } else if (lat == -61) {
            return _checkLatS61(lon);
        }
        return false;
    }

    private static boolean _checkLatS65(int lon) {
        if (lon == -65) {
            return true;
        } else if (lon == -64) {
            return true;
        } else if (lon == -63) {
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
        } else if (lon == -57) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS64(int lon) {
        if (lon == -63) {
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
        } else if (lon == -57) {
            return true;
        } else if (lon == -56) {
            return true;
        } else if (lon == -55) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS63(int lon) {
        if (lon == -63) {
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
        } else if (lon == -57) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS62(int lon) {
        if (lon == -59) {
            return true;
        } else if (lon == -58) {
            return true;
        } else if (lon == -56) {
            return true;
        } else if (lon == -55) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS61(int lon) {
        if (lon == -47) {
            return true;
        } else if (lon == -46) {
            return true;
        } else if (lon == -45) {
            return true;
        }
        return false;
    }

}
