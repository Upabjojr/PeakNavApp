
package com.peaknav.elevation.blocks;

import static com.peaknav.elevation.blocks.CheckElevExistBlockS90ToS85._checkElevExistS90ToS85;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS85ToS80._checkElevExistS85ToS80;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS80ToS75._checkElevExistS80ToS75;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS75ToS70._checkElevExistS75ToS70;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS70ToS65._checkElevExistS70ToS65;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS65ToS60._checkElevExistS65ToS60;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS60ToS55._checkElevExistS60ToS55;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS55ToS50._checkElevExistS55ToS50;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS50ToS45._checkElevExistS50ToS45;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS45ToS40._checkElevExistS45ToS40;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS40ToS35._checkElevExistS40ToS35;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS35ToS30._checkElevExistS35ToS30;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS30ToS25._checkElevExistS30ToS25;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS25ToS20._checkElevExistS25ToS20;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS20ToS15._checkElevExistS20ToS15;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS15ToS10._checkElevExistS15ToS10;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS10ToS05._checkElevExistS10ToS05;
import static com.peaknav.elevation.blocks.CheckElevExistBlockS05ToN00._checkElevExistS05ToN00;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN00ToN05._checkElevExistN00ToN05;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN05ToN10._checkElevExistN05ToN10;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN10ToN15._checkElevExistN10ToN15;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN15ToN20._checkElevExistN15ToN20;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN20ToN25._checkElevExistN20ToN25;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN25ToN30._checkElevExistN25ToN30;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN30ToN35._checkElevExistN30ToN35;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN35ToN40._checkElevExistN35ToN40;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN40ToN45._checkElevExistN40ToN45;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN45ToN50._checkElevExistN45ToN50;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN50ToN55._checkElevExistN50ToN55;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN55ToN60._checkElevExistN55ToN60;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN60ToN65._checkElevExistN60ToN65;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN65ToN70._checkElevExistN65ToN70;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN70ToN75._checkElevExistN70ToN75;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN75ToN80._checkElevExistN75ToN80;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN80ToN85._checkElevExistN80ToN85;
import static com.peaknav.elevation.blocks.CheckElevExistBlockN85ToN90._checkElevExistN85ToN90;

public class CheckElevExistBlock {
    private CheckElevExistBlock() {}

    public static boolean checkElevationExistence(int lat, int lon) {
        if (lat >= -90 && lat < -85) {
            return _checkElevExistS90ToS85(lat, lon);
        } else if (lat >= -85 && lat < -80) {
            return _checkElevExistS85ToS80(lat, lon);
        } else if (lat >= -80 && lat < -75) {
            return _checkElevExistS80ToS75(lat, lon);
        } else if (lat >= -75 && lat < -70) {
            return _checkElevExistS75ToS70(lat, lon);
        } else if (lat >= -70 && lat < -65) {
            return _checkElevExistS70ToS65(lat, lon);
        } else if (lat >= -65 && lat < -60) {
            return _checkElevExistS65ToS60(lat, lon);
        } else if (lat >= -60 && lat < -55) {
            return _checkElevExistS60ToS55(lat, lon);
        } else if (lat >= -55 && lat < -50) {
            return _checkElevExistS55ToS50(lat, lon);
        } else if (lat >= -50 && lat < -45) {
            return _checkElevExistS50ToS45(lat, lon);
        } else if (lat >= -45 && lat < -40) {
            return _checkElevExistS45ToS40(lat, lon);
        } else if (lat >= -40 && lat < -35) {
            return _checkElevExistS40ToS35(lat, lon);
        } else if (lat >= -35 && lat < -30) {
            return _checkElevExistS35ToS30(lat, lon);
        } else if (lat >= -30 && lat < -25) {
            return _checkElevExistS30ToS25(lat, lon);
        } else if (lat >= -25 && lat < -20) {
            return _checkElevExistS25ToS20(lat, lon);
        } else if (lat >= -20 && lat < -15) {
            return _checkElevExistS20ToS15(lat, lon);
        } else if (lat >= -15 && lat < -10) {
            return _checkElevExistS15ToS10(lat, lon);
        } else if (lat >= -10 && lat < -5) {
            return _checkElevExistS10ToS05(lat, lon);
        } else if (lat >= -5 && lat < 0) {
            return _checkElevExistS05ToN00(lat, lon);
        } else if (lat >= 0 && lat < 5) {
            return _checkElevExistN00ToN05(lat, lon);
        } else if (lat >= 5 && lat < 10) {
            return _checkElevExistN05ToN10(lat, lon);
        } else if (lat >= 10 && lat < 15) {
            return _checkElevExistN10ToN15(lat, lon);
        } else if (lat >= 15 && lat < 20) {
            return _checkElevExistN15ToN20(lat, lon);
        } else if (lat >= 20 && lat < 25) {
            return _checkElevExistN20ToN25(lat, lon);
        } else if (lat >= 25 && lat < 30) {
            return _checkElevExistN25ToN30(lat, lon);
        } else if (lat >= 30 && lat < 35) {
            return _checkElevExistN30ToN35(lat, lon);
        } else if (lat >= 35 && lat < 40) {
            return _checkElevExistN35ToN40(lat, lon);
        } else if (lat >= 40 && lat < 45) {
            return _checkElevExistN40ToN45(lat, lon);
        } else if (lat >= 45 && lat < 50) {
            return _checkElevExistN45ToN50(lat, lon);
        } else if (lat >= 50 && lat < 55) {
            return _checkElevExistN50ToN55(lat, lon);
        } else if (lat >= 55 && lat < 60) {
            return _checkElevExistN55ToN60(lat, lon);
        } else if (lat >= 60 && lat < 65) {
            return _checkElevExistN60ToN65(lat, lon);
        } else if (lat >= 65 && lat < 70) {
            return _checkElevExistN65ToN70(lat, lon);
        } else if (lat >= 70 && lat < 75) {
            return _checkElevExistN70ToN75(lat, lon);
        } else if (lat >= 75 && lat < 80) {
            return _checkElevExistN75ToN80(lat, lon);
        } else if (lat >= 80 && lat < 85) {
            return _checkElevExistN80ToN85(lat, lon);
        } else if (lat >= 85 && lat < 90) {
            return _checkElevExistN85ToN90(lat, lon);
        }
        return false;
    }
}
