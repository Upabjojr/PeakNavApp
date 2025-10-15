
package com.peaknav.elevation.blocks;

class CheckElevExistBlockS15ToS10 {
    private CheckElevExistBlockS15ToS10() {}

    public static boolean _checkElevExistS15ToS10(int lat, int lon) {
        if (lat == -15) {
            return _checkLatS15(lon);
        } else if (lat == -14) {
            return _checkLatS14(lon);
        } else if (lat == -13) {
            return _checkLatS13(lon);
        } else if (lat == -12) {
            return _checkLatS12(lon);
        } else if (lat == -11) {
            return _checkLatS11(lon);
        }
        return false;
    }

    private static boolean _checkLatS15(int lon) {
        if (lon == -179) {
            return true;
        } else if (lon == -178) {
            return true;
        } else if (lon == -172) {
            return true;
        } else if (lon == -171) {
            return true;
        } else if (lon == -170) {
            return true;
        } else if (lon == -169) {
            return true;
        } else if (lon == -149) {
            return true;
        } else if (lon == -148) {
            return true;
        } else if (lon == -147) {
            return true;
        } else if (lon == -146) {
            return true;
        } else if (lon == -145) {
            return true;
        } else if (lon == -142) {
            return true;
        } else if (lon == -139) {
            return true;
        } else if (lon == -77) {
            return true;
        } else if (lon == -76) {
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
        } else if (lon == -65) {
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
        } else if (lon == -56) {
            return true;
        } else if (lon == -55) {
            return true;
        } else if (lon == -54) {
            return true;
        } else if (lon == -53) {
            return true;
        } else if (lon == -52) {
            return true;
        } else if (lon == -51) {
            return true;
        } else if (lon == -50) {
            return true;
        } else if (lon == -49) {
            return true;
        } else if (lon == -48) {
            return true;
        } else if (lon == -47) {
            return true;
        } else if (lon == -46) {
            return true;
        } else if (lon == -45) {
            return true;
        } else if (lon == -44) {
            return true;
        } else if (lon == -43) {
            return true;
        } else if (lon == -42) {
            return true;
        } else if (lon == -41) {
            return true;
        } else if (lon == -40) {
            return true;
        } else if (lon == -39) {
            return true;
        } else if (lon == 12) {
            return true;
        } else if (lon == 13) {
            return true;
        } else if (lon == 14) {
            return true;
        } else if (lon == 15) {
            return true;
        } else if (lon == 16) {
            return true;
        } else if (lon == 17) {
            return true;
        } else if (lon == 18) {
            return true;
        } else if (lon == 19) {
            return true;
        } else if (lon == 20) {
            return true;
        } else if (lon == 21) {
            return true;
        } else if (lon == 22) {
            return true;
        } else if (lon == 23) {
            return true;
        } else if (lon == 24) {
            return true;
        } else if (lon == 25) {
            return true;
        } else if (lon == 26) {
            return true;
        } else if (lon == 27) {
            return true;
        } else if (lon == 28) {
            return true;
        } else if (lon == 29) {
            return true;
        } else if (lon == 30) {
            return true;
        } else if (lon == 31) {
            return true;
        } else if (lon == 32) {
            return true;
        } else if (lon == 33) {
            return true;
        } else if (lon == 34) {
            return true;
        } else if (lon == 35) {
            return true;
        } else if (lon == 36) {
            return true;
        } else if (lon == 37) {
            return true;
        } else if (lon == 38) {
            return true;
        } else if (lon == 39) {
            return true;
        } else if (lon == 40) {
            return true;
        } else if (lon == 47) {
            return true;
        } else if (lon == 48) {
            return true;
        } else if (lon == 49) {
            return true;
        } else if (lon == 50) {
            return true;
        } else if (lon == 123) {
            return true;
        } else if (lon == 124) {
            return true;
        } else if (lon == 125) {
            return true;
        } else if (lon == 126) {
            return true;
        } else if (lon == 127) {
            return true;
        } else if (lon == 128) {
            return true;
        } else if (lon == 129) {
            return true;
        } else if (lon == 130) {
            return true;
        } else if (lon == 131) {
            return true;
        } else if (lon == 132) {
            return true;
        } else if (lon == 133) {
            return true;
        } else if (lon == 134) {
            return true;
        } else if (lon == 135) {
            return true;
        } else if (lon == 136) {
            return true;
        } else if (lon == 141) {
            return true;
        } else if (lon == 142) {
            return true;
        } else if (lon == 143) {
            return true;
        } else if (lon == 144) {
            return true;
        } else if (lon == 145) {
            return true;
        } else if (lon == 166) {
            return true;
        } else if (lon == 167) {
            return true;
        } else if (lon == 168) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS14(int lon) {
        if (lon == -177) {
            return true;
        } else if (lon == -173) {
            return true;
        } else if (lon == -172) {
            return true;
        } else if (lon == -77) {
            return true;
        } else if (lon == -76) {
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
        } else if (lon == -65) {
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
        } else if (lon == -56) {
            return true;
        } else if (lon == -55) {
            return true;
        } else if (lon == -54) {
            return true;
        } else if (lon == -53) {
            return true;
        } else if (lon == -52) {
            return true;
        } else if (lon == -51) {
            return true;
        } else if (lon == -50) {
            return true;
        } else if (lon == -49) {
            return true;
        } else if (lon == -48) {
            return true;
        } else if (lon == -47) {
            return true;
        } else if (lon == -46) {
            return true;
        } else if (lon == -45) {
            return true;
        } else if (lon == -44) {
            return true;
        } else if (lon == -43) {
            return true;
        } else if (lon == -42) {
            return true;
        } else if (lon == -41) {
            return true;
        } else if (lon == -40) {
            return true;
        } else if (lon == -39) {
            return true;
        } else if (lon == 12) {
            return true;
        } else if (lon == 13) {
            return true;
        } else if (lon == 14) {
            return true;
        } else if (lon == 15) {
            return true;
        } else if (lon == 16) {
            return true;
        } else if (lon == 17) {
            return true;
        } else if (lon == 18) {
            return true;
        } else if (lon == 19) {
            return true;
        } else if (lon == 20) {
            return true;
        } else if (lon == 21) {
            return true;
        } else if (lon == 22) {
            return true;
        } else if (lon == 23) {
            return true;
        } else if (lon == 24) {
            return true;
        } else if (lon == 25) {
            return true;
        } else if (lon == 26) {
            return true;
        } else if (lon == 27) {
            return true;
        } else if (lon == 28) {
            return true;
        } else if (lon == 29) {
            return true;
        } else if (lon == 30) {
            return true;
        } else if (lon == 31) {
            return true;
        } else if (lon == 32) {
            return true;
        } else if (lon == 33) {
            return true;
        } else if (lon == 34) {
            return true;
        } else if (lon == 35) {
            return true;
        } else if (lon == 36) {
            return true;
        } else if (lon == 37) {
            return true;
        } else if (lon == 38) {
            return true;
        } else if (lon == 39) {
            return true;
        } else if (lon == 40) {
            return true;
        } else if (lon == 45) {
            return true;
        } else if (lon == 47) {
            return true;
        } else if (lon == 48) {
            return true;
        } else if (lon == 49) {
            return true;
        } else if (lon == 50) {
            return true;
        } else if (lon == 125) {
            return true;
        } else if (lon == 126) {
            return true;
        } else if (lon == 127) {
            return true;
        } else if (lon == 129) {
            return true;
        } else if (lon == 130) {
            return true;
        } else if (lon == 131) {
            return true;
        } else if (lon == 132) {
            return true;
        } else if (lon == 133) {
            return true;
        } else if (lon == 134) {
            return true;
        } else if (lon == 135) {
            return true;
        } else if (lon == 136) {
            return true;
        } else if (lon == 141) {
            return true;
        } else if (lon == 142) {
            return true;
        } else if (lon == 143) {
            return true;
        } else if (lon == 144) {
            return true;
        } else if (lon == 166) {
            return true;
        } else if (lon == 167) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS13(int lon) {
        if (lon == -78) {
            return true;
        } else if (lon == -77) {
            return true;
        } else if (lon == -76) {
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
        } else if (lon == -65) {
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
        } else if (lon == -56) {
            return true;
        } else if (lon == -55) {
            return true;
        } else if (lon == -54) {
            return true;
        } else if (lon == -53) {
            return true;
        } else if (lon == -52) {
            return true;
        } else if (lon == -51) {
            return true;
        } else if (lon == -50) {
            return true;
        } else if (lon == -49) {
            return true;
        } else if (lon == -48) {
            return true;
        } else if (lon == -47) {
            return true;
        } else if (lon == -46) {
            return true;
        } else if (lon == -45) {
            return true;
        } else if (lon == -44) {
            return true;
        } else if (lon == -43) {
            return true;
        } else if (lon == -42) {
            return true;
        } else if (lon == -41) {
            return true;
        } else if (lon == -40) {
            return true;
        } else if (lon == -39) {
            return true;
        } else if (lon == -38) {
            return true;
        } else if (lon == 12) {
            return true;
        } else if (lon == 13) {
            return true;
        } else if (lon == 14) {
            return true;
        } else if (lon == 15) {
            return true;
        } else if (lon == 16) {
            return true;
        } else if (lon == 17) {
            return true;
        } else if (lon == 18) {
            return true;
        } else if (lon == 19) {
            return true;
        } else if (lon == 20) {
            return true;
        } else if (lon == 21) {
            return true;
        } else if (lon == 22) {
            return true;
        } else if (lon == 23) {
            return true;
        } else if (lon == 24) {
            return true;
        } else if (lon == 25) {
            return true;
        } else if (lon == 26) {
            return true;
        } else if (lon == 27) {
            return true;
        } else if (lon == 28) {
            return true;
        } else if (lon == 29) {
            return true;
        } else if (lon == 30) {
            return true;
        } else if (lon == 31) {
            return true;
        } else if (lon == 32) {
            return true;
        } else if (lon == 33) {
            return true;
        } else if (lon == 34) {
            return true;
        } else if (lon == 35) {
            return true;
        } else if (lon == 36) {
            return true;
        } else if (lon == 37) {
            return true;
        } else if (lon == 38) {
            return true;
        } else if (lon == 39) {
            return true;
        } else if (lon == 40) {
            return true;
        } else if (lon == 43) {
            return true;
        } else if (lon == 44) {
            return true;
        } else if (lon == 45) {
            return true;
        } else if (lon == 48) {
            return true;
        } else if (lon == 49) {
            return true;
        } else if (lon == 96) {
            return true;
        } else if (lon == 122) {
            return true;
        } else if (lon == 123) {
            return true;
        } else if (lon == 130) {
            return true;
        } else if (lon == 131) {
            return true;
        } else if (lon == 132) {
            return true;
        } else if (lon == 133) {
            return true;
        } else if (lon == 134) {
            return true;
        } else if (lon == 135) {
            return true;
        } else if (lon == 136) {
            return true;
        } else if (lon == 141) {
            return true;
        } else if (lon == 142) {
            return true;
        } else if (lon == 143) {
            return true;
        } else if (lon == 168) {
            return true;
        } else if (lon == 176) {
            return true;
        } else if (lon == 177) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS12(int lon) {
        if (lon == -172) {
            return true;
        } else if (lon == -166) {
            return true;
        } else if (lon == -78) {
            return true;
        } else if (lon == -77) {
            return true;
        } else if (lon == -76) {
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
        } else if (lon == -65) {
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
        } else if (lon == -56) {
            return true;
        } else if (lon == -55) {
            return true;
        } else if (lon == -54) {
            return true;
        } else if (lon == -53) {
            return true;
        } else if (lon == -52) {
            return true;
        } else if (lon == -51) {
            return true;
        } else if (lon == -50) {
            return true;
        } else if (lon == -49) {
            return true;
        } else if (lon == -48) {
            return true;
        } else if (lon == -47) {
            return true;
        } else if (lon == -46) {
            return true;
        } else if (lon == -45) {
            return true;
        } else if (lon == -44) {
            return true;
        } else if (lon == -43) {
            return true;
        } else if (lon == -42) {
            return true;
        } else if (lon == -41) {
            return true;
        } else if (lon == -40) {
            return true;
        } else if (lon == -39) {
            return true;
        } else if (lon == -38) {
            return true;
        } else if (lon == 13) {
            return true;
        } else if (lon == 14) {
            return true;
        } else if (lon == 15) {
            return true;
        } else if (lon == 16) {
            return true;
        } else if (lon == 17) {
            return true;
        } else if (lon == 18) {
            return true;
        } else if (lon == 19) {
            return true;
        } else if (lon == 20) {
            return true;
        } else if (lon == 21) {
            return true;
        } else if (lon == 22) {
            return true;
        } else if (lon == 23) {
            return true;
        } else if (lon == 24) {
            return true;
        } else if (lon == 25) {
            return true;
        } else if (lon == 26) {
            return true;
        } else if (lon == 27) {
            return true;
        } else if (lon == 28) {
            return true;
        } else if (lon == 29) {
            return true;
        } else if (lon == 30) {
            return true;
        } else if (lon == 31) {
            return true;
        } else if (lon == 32) {
            return true;
        } else if (lon == 33) {
            return true;
        } else if (lon == 34) {
            return true;
        } else if (lon == 35) {
            return true;
        } else if (lon == 36) {
            return true;
        } else if (lon == 37) {
            return true;
        } else if (lon == 38) {
            return true;
        } else if (lon == 39) {
            return true;
        } else if (lon == 40) {
            return true;
        } else if (lon == 43) {
            return true;
        } else if (lon == 47) {
            return true;
        } else if (lon == 49) {
            return true;
        } else if (lon == 96) {
            return true;
        } else if (lon == 122) {
            return true;
        } else if (lon == 130) {
            return true;
        } else if (lon == 131) {
            return true;
        } else if (lon == 132) {
            return true;
        } else if (lon == 133) {
            return true;
        } else if (lon == 134) {
            return true;
        } else if (lon == 135) {
            return true;
        } else if (lon == 136) {
            return true;
        } else if (lon == 141) {
            return true;
        } else if (lon == 142) {
            return true;
        } else if (lon == 143) {
            return true;
        } else if (lon == 144) {
            return true;
        } else if (lon == 151) {
            return true;
        } else if (lon == 152) {
            return true;
        } else if (lon == 153) {
            return true;
        } else if (lon == 154) {
            return true;
        } else if (lon == 159) {
            return true;
        } else if (lon == 160) {
            return true;
        } else if (lon == 166) {
            return true;
        }
        return false;
    }

    private static boolean _checkLatS11(int lon) {
        if (lon == -166) {
            return true;
        } else if (lon == -162) {
            return true;
        } else if (lon == -161) {
            return true;
        } else if (lon == -151) {
            return true;
        } else if (lon == -140) {
            return true;
        } else if (lon == -139) {
            return true;
        } else if (lon == -79) {
            return true;
        } else if (lon == -78) {
            return true;
        } else if (lon == -77) {
            return true;
        } else if (lon == -76) {
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
        } else if (lon == -65) {
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
        } else if (lon == -56) {
            return true;
        } else if (lon == -55) {
            return true;
        } else if (lon == -54) {
            return true;
        } else if (lon == -53) {
            return true;
        } else if (lon == -52) {
            return true;
        } else if (lon == -51) {
            return true;
        } else if (lon == -50) {
            return true;
        } else if (lon == -49) {
            return true;
        } else if (lon == -48) {
            return true;
        } else if (lon == -47) {
            return true;
        } else if (lon == -46) {
            return true;
        } else if (lon == -45) {
            return true;
        } else if (lon == -44) {
            return true;
        } else if (lon == -43) {
            return true;
        } else if (lon == -42) {
            return true;
        } else if (lon == -41) {
            return true;
        } else if (lon == -40) {
            return true;
        } else if (lon == -39) {
            return true;
        } else if (lon == -38) {
            return true;
        } else if (lon == -37) {
            return true;
        } else if (lon == 13) {
            return true;
        } else if (lon == 14) {
            return true;
        } else if (lon == 15) {
            return true;
        } else if (lon == 16) {
            return true;
        } else if (lon == 17) {
            return true;
        } else if (lon == 18) {
            return true;
        } else if (lon == 19) {
            return true;
        } else if (lon == 20) {
            return true;
        } else if (lon == 21) {
            return true;
        } else if (lon == 22) {
            return true;
        } else if (lon == 23) {
            return true;
        } else if (lon == 24) {
            return true;
        } else if (lon == 25) {
            return true;
        } else if (lon == 26) {
            return true;
        } else if (lon == 27) {
            return true;
        } else if (lon == 28) {
            return true;
        } else if (lon == 29) {
            return true;
        } else if (lon == 30) {
            return true;
        } else if (lon == 31) {
            return true;
        } else if (lon == 32) {
            return true;
        } else if (lon == 33) {
            return true;
        } else if (lon == 34) {
            return true;
        } else if (lon == 35) {
            return true;
        } else if (lon == 36) {
            return true;
        } else if (lon == 37) {
            return true;
        } else if (lon == 38) {
            return true;
        } else if (lon == 39) {
            return true;
        } else if (lon == 40) {
            return true;
        } else if (lon == 47) {
            return true;
        } else if (lon == 51) {
            return true;
        } else if (lon == 56) {
            return true;
        } else if (lon == 105) {
            return true;
        } else if (lon == 119) {
            return true;
        } else if (lon == 120) {
            return true;
        } else if (lon == 121) {
            return true;
        } else if (lon == 122) {
            return true;
        } else if (lon == 123) {
            return true;
        } else if (lon == 124) {
            return true;
        } else if (lon == 132) {
            return true;
        } else if (lon == 133) {
            return true;
        } else if (lon == 141) {
            return true;
        } else if (lon == 142) {
            return true;
        } else if (lon == 143) {
            return true;
        } else if (lon == 147) {
            return true;
        } else if (lon == 148) {
            return true;
        } else if (lon == 149) {
            return true;
        } else if (lon == 150) {
            return true;
        } else if (lon == 151) {
            return true;
        } else if (lon == 152) {
            return true;
        } else if (lon == 153) {
            return true;
        } else if (lon == 161) {
            return true;
        } else if (lon == 162) {
            return true;
        } else if (lon == 165) {
            return true;
        } else if (lon == 166) {
            return true;
        }
        return false;
    }

}
