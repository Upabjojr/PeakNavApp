
package com.peaknav.elevation.blocksS3;

import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock000To008._checkS3ElevExist000To008;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock008To016._checkS3ElevExist008To016;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock016To024._checkS3ElevExist016To024;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock024To032._checkS3ElevExist024To032;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock032To040._checkS3ElevExist032To040;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock040To048._checkS3ElevExist040To048;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock048To056._checkS3ElevExist048To056;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock056To064._checkS3ElevExist056To064;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock064To072._checkS3ElevExist064To072;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock072To080._checkS3ElevExist072To080;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock080To088._checkS3ElevExist080To088;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock088To096._checkS3ElevExist088To096;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock096To104._checkS3ElevExist096To104;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock104To112._checkS3ElevExist104To112;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock112To120._checkS3ElevExist112To120;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock120To128._checkS3ElevExist120To128;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock128To136._checkS3ElevExist128To136;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock136To144._checkS3ElevExist136To144;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock144To152._checkS3ElevExist144To152;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock152To160._checkS3ElevExist152To160;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock160To168._checkS3ElevExist160To168;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock168To176._checkS3ElevExist168To176;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock176To184._checkS3ElevExist176To184;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock184To192._checkS3ElevExist184To192;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock192To200._checkS3ElevExist192To200;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock200To208._checkS3ElevExist200To208;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock208To216._checkS3ElevExist208To216;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock216To224._checkS3ElevExist216To224;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock224To232._checkS3ElevExist224To232;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock232To240._checkS3ElevExist232To240;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock240To248._checkS3ElevExist240To248;
import static com.peaknav.elevation.blocksS3.CheckS3ElevExistBlock248To256._checkS3ElevExist248To256;

public class CheckS3ElevExistBlock {
    private CheckS3ElevExistBlock() {}

    public static boolean checkS3ElevationExistence(int x, int y) {
        if (x >= 0 && x < 8) {
            return _checkS3ElevExist000To008(x, y);
        } else if (x >= 8 && x < 16) {
            return _checkS3ElevExist008To016(x, y);
        } else if (x >= 16 && x < 24) {
            return _checkS3ElevExist016To024(x, y);
        } else if (x >= 24 && x < 32) {
            return _checkS3ElevExist024To032(x, y);
        } else if (x >= 32 && x < 40) {
            return _checkS3ElevExist032To040(x, y);
        } else if (x >= 40 && x < 48) {
            return _checkS3ElevExist040To048(x, y);
        } else if (x >= 48 && x < 56) {
            return _checkS3ElevExist048To056(x, y);
        } else if (x >= 56 && x < 64) {
            return _checkS3ElevExist056To064(x, y);
        } else if (x >= 64 && x < 72) {
            return _checkS3ElevExist064To072(x, y);
        } else if (x >= 72 && x < 80) {
            return _checkS3ElevExist072To080(x, y);
        } else if (x >= 80 && x < 88) {
            return _checkS3ElevExist080To088(x, y);
        } else if (x >= 88 && x < 96) {
            return _checkS3ElevExist088To096(x, y);
        } else if (x >= 96 && x < 104) {
            return _checkS3ElevExist096To104(x, y);
        } else if (x >= 104 && x < 112) {
            return _checkS3ElevExist104To112(x, y);
        } else if (x >= 112 && x < 120) {
            return _checkS3ElevExist112To120(x, y);
        } else if (x >= 120 && x < 128) {
            return _checkS3ElevExist120To128(x, y);
        } else if (x >= 128 && x < 136) {
            return _checkS3ElevExist128To136(x, y);
        } else if (x >= 136 && x < 144) {
            return _checkS3ElevExist136To144(x, y);
        } else if (x >= 144 && x < 152) {
            return _checkS3ElevExist144To152(x, y);
        } else if (x >= 152 && x < 160) {
            return _checkS3ElevExist152To160(x, y);
        } else if (x >= 160 && x < 168) {
            return _checkS3ElevExist160To168(x, y);
        } else if (x >= 168 && x < 176) {
            return _checkS3ElevExist168To176(x, y);
        } else if (x >= 176 && x < 184) {
            return _checkS3ElevExist176To184(x, y);
        } else if (x >= 184 && x < 192) {
            return _checkS3ElevExist184To192(x, y);
        } else if (x >= 192 && x < 200) {
            return _checkS3ElevExist192To200(x, y);
        } else if (x >= 200 && x < 208) {
            return _checkS3ElevExist200To208(x, y);
        } else if (x >= 208 && x < 216) {
            return _checkS3ElevExist208To216(x, y);
        } else if (x >= 216 && x < 224) {
            return _checkS3ElevExist216To224(x, y);
        } else if (x >= 224 && x < 232) {
            return _checkS3ElevExist224To232(x, y);
        } else if (x >= 232 && x < 240) {
            return _checkS3ElevExist232To240(x, y);
        } else if (x >= 240 && x < 248) {
            return _checkS3ElevExist240To248(x, y);
        } else if (x >= 248 && x < 256) {
            return _checkS3ElevExist248To256(x, y);
        }
        return false;
    }
}
