package com.peaknav.compatibility;

import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * The screen density of every iPhone, iPad and iPod, for the devices libGDX's own table gets wrong
 * or does not know - and for the simulator, which it always gets wrong.
 *
 * <p>libGDX looks a device's ppi up by its model identifier ("iPhone18,2"), in a table that stops
 * at the 2019-2020 models and has not been extended since 2022, on its master branch too. A newer
 * device gets a guess - 132 x scale on an iPad, 164 x scale on an iPhone - that is right for some
 * models by luck and wrong for others, and every size worked out from
 * {@code Gdx.graphics.getDensity()} inherits the error: {@code Units.getUiScale()}, which caps
 * the interface at phone size on tablets, among them.
 *
 * <p>The simulator is a case of its own: it reports the host's machine, "x86_64" or "arm64", not
 * the model it simulates, and libGDX gives those a fixed 264 ppi. An iPhone in the simulator was
 * therefore sized as an iPad - 480 dp capped at 1.65 px/dp is 792 px, 0.6 of an iPhone 17 Pro
 * Max's 1320 - and its buttons came out at 26 pt instead of 44, in every simulator screenshot.
 * The simulator names the model it simulates in SIMULATOR_MODEL_IDENTIFIER, so its machine
 * strings are given that model's density here.
 *
 * <p>Generated on 2026-09-16 from two sources that agree on 74 of the 78 models both cover:
 * Apple's own simulator device profiles ({@code mainScreenWidthDPI}, taken for every identifier
 * of a device that has one) and DeviceKit's device list (github.com/devicekit/DeviceKit, MIT)
 * otherwise. The iPhone 6/6s/7/8 Plus draw at 1242 x 2208 and scale down to 1080p panels, so the
 * pixels an app works in are 461 to the inch, not the panel's 401; the iPhone 14 Pro Max is 460,
 * not DeviceKit's 458. 167 models: 79 that libGDX does not know, 9 it has wrong.
 */
public final class IOSDeviceTable {

    private IOSDeviceTable() {
    }

    /** Adds every model below to {@code config}'s known devices; call before the application starts. */
    public static void register(IOSApplicationConfiguration config) {
        Map<String, Integer> ppiByModel = new HashMap<>();
        // classifier, model identifier, pixels per inch
        // iPhones
        add(config, ppiByModel, "IPHONE_4", "iPhone3,1", 326);
        add(config, ppiByModel, "IPHONE_4", "iPhone3,2", 326);
        add(config, ppiByModel, "IPHONE_4", "iPhone3,3", 326);
        add(config, ppiByModel, "IPHONE_4S", "iPhone4,1", 326);
        add(config, ppiByModel, "IPHONE_5", "iPhone5,1", 326);
        add(config, ppiByModel, "IPHONE_5", "iPhone5,2", 326);
        add(config, ppiByModel, "IPHONE_5C", "iPhone5,3", 326);
        add(config, ppiByModel, "IPHONE_5C", "iPhone5,4", 326);
        add(config, ppiByModel, "IPHONE_5S", "iPhone6,1", 326);
        add(config, ppiByModel, "IPHONE_5S", "iPhone6,2", 326);
        add(config, ppiByModel, "IPHONE_6_PLUS", "iPhone7,1", 461);   // libGDX: 401
        add(config, ppiByModel, "IPHONE_6", "iPhone7,2", 326);
        add(config, ppiByModel, "IPHONE_6S", "iPhone8,1", 326);
        add(config, ppiByModel, "IPHONE_6S_PLUS", "iPhone8,2", 461);   // libGDX: 401
        add(config, ppiByModel, "IPHONE_SE", "iPhone8,4", 326);
        add(config, ppiByModel, "IPHONE_7", "iPhone9,1", 326);
        add(config, ppiByModel, "IPHONE_7_PLUS", "iPhone9,2", 461);   // libGDX: 401
        add(config, ppiByModel, "IPHONE_7", "iPhone9,3", 326);
        add(config, ppiByModel, "IPHONE_7_PLUS", "iPhone9,4", 461);   // libGDX: 401
        add(config, ppiByModel, "IPHONE_8", "iPhone10,1", 326);
        add(config, ppiByModel, "IPHONE_8_PLUS", "iPhone10,2", 461);   // libGDX: 401
        add(config, ppiByModel, "IPHONE_X", "iPhone10,3", 458);
        add(config, ppiByModel, "IPHONE_8", "iPhone10,4", 326);
        add(config, ppiByModel, "IPHONE_8_PLUS", "iPhone10,5", 461);   // libGDX: 401
        add(config, ppiByModel, "IPHONE_X", "iPhone10,6", 458);
        add(config, ppiByModel, "IPHONE_XS", "iPhone11,2", 458);
        add(config, ppiByModel, "IPHONE_XS_MAX", "iPhone11,4", 458);
        add(config, ppiByModel, "IPHONE_XS_MAX", "iPhone11,6", 458);
        add(config, ppiByModel, "IPHONE_XR", "iPhone11,8", 326);
        add(config, ppiByModel, "IPHONE_11", "iPhone12,1", 326);
        add(config, ppiByModel, "IPHONE_11_PRO", "iPhone12,3", 458);
        add(config, ppiByModel, "IPHONE_11_PRO_MAX", "iPhone12,5", 458);
        add(config, ppiByModel, "IPHONE_SE_2", "iPhone12,8", 326);
        add(config, ppiByModel, "IPHONE_12_MINI", "iPhone13,1", 476);
        add(config, ppiByModel, "IPHONE_12", "iPhone13,2", 460);
        add(config, ppiByModel, "IPHONE_12_PRO", "iPhone13,3", 460);
        add(config, ppiByModel, "IPHONE_12_PRO_MAX", "iPhone13,4", 458);
        add(config, ppiByModel, "IPHONE_13_PRO", "iPhone14,2", 460);
        add(config, ppiByModel, "IPHONE_13_PRO_MAX", "iPhone14,3", 458);
        add(config, ppiByModel, "IPHONE_13_MINI", "iPhone14,4", 476);
        add(config, ppiByModel, "IPHONE_13", "iPhone14,5", 460);
        add(config, ppiByModel, "IPHONE_SE_3", "iPhone14,6", 326);
        add(config, ppiByModel, "IPHONE_14", "iPhone14,7", 460);
        add(config, ppiByModel, "IPHONE_14_PLUS", "iPhone14,8", 458);
        add(config, ppiByModel, "IPHONE_14_PRO", "iPhone15,2", 460);
        add(config, ppiByModel, "IPHONE_14_PRO_MAX", "iPhone15,3", 460);
        add(config, ppiByModel, "IPHONE_15", "iPhone15,4", 460);
        add(config, ppiByModel, "IPHONE_15_PLUS", "iPhone15,5", 460);
        add(config, ppiByModel, "IPHONE_15_PRO", "iPhone16,1", 460);
        add(config, ppiByModel, "IPHONE_15_PRO_MAX", "iPhone16,2", 460);
        add(config, ppiByModel, "IPHONE_16_PRO", "iPhone17,1", 460);
        add(config, ppiByModel, "IPHONE_16_PRO_MAX", "iPhone17,2", 460);
        add(config, ppiByModel, "IPHONE_16", "iPhone17,3", 460);
        add(config, ppiByModel, "IPHONE_16_PLUS", "iPhone17,4", 460);
        add(config, ppiByModel, "IPHONE_16E", "iPhone17,5", 460);
        add(config, ppiByModel, "IPHONE_17_PRO", "iPhone18,1", 460);
        add(config, ppiByModel, "IPHONE_17_PRO_MAX", "iPhone18,2", 460);
        add(config, ppiByModel, "IPHONE_17", "iPhone18,3", 460);
        add(config, ppiByModel, "IPHONE_AIR", "iPhone18,4", 460);
        add(config, ppiByModel, "IPHONE_17E", "iPhone18,5", 460);

        // iPads
        add(config, ppiByModel, "IPAD_2", "iPad2,1", 132);
        add(config, ppiByModel, "IPAD_2", "iPad2,2", 132);
        add(config, ppiByModel, "IPAD_2", "iPad2,3", 132);
        add(config, ppiByModel, "IPAD_2", "iPad2,4", 132);
        add(config, ppiByModel, "IPAD_MINI", "iPad2,5", 163);   // libGDX: 164
        add(config, ppiByModel, "IPAD_MINI", "iPad2,6", 163);   // libGDX: 164
        add(config, ppiByModel, "IPAD_MINI", "iPad2,7", 163);   // libGDX: 164
        add(config, ppiByModel, "IPAD_3", "iPad3,1", 264);
        add(config, ppiByModel, "IPAD_3", "iPad3,2", 264);
        add(config, ppiByModel, "IPAD_3", "iPad3,3", 264);
        add(config, ppiByModel, "IPAD_4", "iPad3,4", 264);
        add(config, ppiByModel, "IPAD_4", "iPad3,5", 264);
        add(config, ppiByModel, "IPAD_4", "iPad3,6", 264);
        add(config, ppiByModel, "IPAD_AIR", "iPad4,1", 264);
        add(config, ppiByModel, "IPAD_AIR", "iPad4,2", 264);
        add(config, ppiByModel, "IPAD_AIR", "iPad4,3", 264);
        add(config, ppiByModel, "IPAD_MINI_2", "iPad4,4", 326);
        add(config, ppiByModel, "IPAD_MINI_2", "iPad4,5", 326);
        add(config, ppiByModel, "IPAD_MINI_2", "iPad4,6", 326);
        add(config, ppiByModel, "IPAD_MINI_3", "iPad4,7", 326);
        add(config, ppiByModel, "IPAD_MINI_3", "iPad4,8", 326);
        add(config, ppiByModel, "IPAD_MINI_3", "iPad4,9", 326);
        add(config, ppiByModel, "IPAD_MINI_4", "iPad5,1", 326);
        add(config, ppiByModel, "IPAD_MINI_4", "iPad5,2", 326);
        add(config, ppiByModel, "IPAD_AIR_2", "iPad5,3", 264);
        add(config, ppiByModel, "IPAD_AIR_2", "iPad5,4", 264);
        add(config, ppiByModel, "IPAD_PRO_9_INCH", "iPad6,3", 264);
        add(config, ppiByModel, "IPAD_PRO_9_INCH", "iPad6,4", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH", "iPad6,7", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH", "iPad6,8", 264);
        add(config, ppiByModel, "IPAD_5", "iPad6,11", 264);
        add(config, ppiByModel, "IPAD_5", "iPad6,12", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_2", "iPad7,1", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_2", "iPad7,2", 264);
        add(config, ppiByModel, "IPAD_PRO_10_INCH", "iPad7,3", 264);
        add(config, ppiByModel, "IPAD_PRO_10_INCH", "iPad7,4", 264);
        add(config, ppiByModel, "IPAD_6", "iPad7,5", 264);
        add(config, ppiByModel, "IPAD_6", "iPad7,6", 264);
        add(config, ppiByModel, "IPAD_7", "iPad7,11", 264);
        add(config, ppiByModel, "IPAD_7", "iPad7,12", 264);
        add(config, ppiByModel, "IPAD_PRO_11_INCH", "iPad8,1", 264);
        add(config, ppiByModel, "IPAD_PRO_11_INCH", "iPad8,2", 264);
        add(config, ppiByModel, "IPAD_PRO_11_INCH", "iPad8,3", 264);
        add(config, ppiByModel, "IPAD_PRO_11_INCH", "iPad8,4", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_3", "iPad8,5", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_3", "iPad8,6", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_3", "iPad8,7", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_3", "iPad8,8", 264);
        add(config, ppiByModel, "IPAD_PRO_11_INCH_2", "iPad8,9", 264);
        add(config, ppiByModel, "IPAD_PRO_11_INCH_2", "iPad8,10", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_4", "iPad8,11", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_4", "iPad8,12", 264);
        add(config, ppiByModel, "IPAD_MINI_5", "iPad11,1", 326);
        add(config, ppiByModel, "IPAD_MINI_5", "iPad11,2", 326);
        add(config, ppiByModel, "IPAD_AIR_3", "iPad11,3", 264);
        add(config, ppiByModel, "IPAD_AIR_3", "iPad11,4", 264);
        add(config, ppiByModel, "IPAD_8", "iPad11,6", 264);
        add(config, ppiByModel, "IPAD_8", "iPad11,7", 264);
        add(config, ppiByModel, "IPAD_9", "iPad12,1", 264);
        add(config, ppiByModel, "IPAD_9", "iPad12,2", 264);
        add(config, ppiByModel, "IPAD_AIR_4", "iPad13,1", 264);
        add(config, ppiByModel, "IPAD_AIR_4", "iPad13,2", 264);
        add(config, ppiByModel, "IPAD_PRO_11_INCH_3", "iPad13,4", 264);
        add(config, ppiByModel, "IPAD_PRO_11_INCH_3", "iPad13,5", 264);
        add(config, ppiByModel, "IPAD_PRO_11_INCH_3", "iPad13,6", 264);
        add(config, ppiByModel, "IPAD_PRO_11_INCH_3", "iPad13,7", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_5", "iPad13,8", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_5", "iPad13,9", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_5", "iPad13,10", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_5", "iPad13,11", 264);
        add(config, ppiByModel, "IPAD_AIR_5", "iPad13,16", 264);
        add(config, ppiByModel, "IPAD_AIR_5", "iPad13,17", 264);
        add(config, ppiByModel, "IPAD_10", "iPad13,18", 264);
        add(config, ppiByModel, "IPAD_10", "iPad13,19", 264);
        add(config, ppiByModel, "IPAD_MINI_6", "iPad14,1", 326);
        add(config, ppiByModel, "IPAD_MINI_6", "iPad14,2", 326);
        add(config, ppiByModel, "IPAD_PRO_11_INCH_4", "iPad14,3", 264);
        add(config, ppiByModel, "IPAD_PRO_11_INCH_4", "iPad14,4", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_6", "iPad14,5", 264);
        add(config, ppiByModel, "IPAD_PRO_12_INCH_6", "iPad14,6", 264);
        add(config, ppiByModel, "IPAD_AIR_11_M2", "iPad14,8", 264);
        add(config, ppiByModel, "IPAD_AIR_11_M2", "iPad14,9", 264);
        add(config, ppiByModel, "IPAD_AIR_13_M2", "iPad14,10", 264);
        add(config, ppiByModel, "IPAD_AIR_13_M2", "iPad14,11", 264);
        add(config, ppiByModel, "IPAD_AIR_11_M3", "iPad15,3", 264);
        add(config, ppiByModel, "IPAD_AIR_11_M3", "iPad15,4", 264);
        add(config, ppiByModel, "IPAD_AIR_13_M3", "iPad15,5", 264);
        add(config, ppiByModel, "IPAD_AIR_13_M3", "iPad15,6", 264);
        add(config, ppiByModel, "IPAD_A16", "iPad15,7", 264);
        add(config, ppiByModel, "IPAD_A16", "iPad15,8", 264);
        add(config, ppiByModel, "IPAD_MINI_A17_PRO", "iPad16,1", 326);
        add(config, ppiByModel, "IPAD_MINI_A17_PRO", "iPad16,2", 326);
        add(config, ppiByModel, "IPAD_PRO_11_M4", "iPad16,3", 264);
        add(config, ppiByModel, "IPAD_PRO_11_M4", "iPad16,4", 264);
        add(config, ppiByModel, "IPAD_PRO_13_M4", "iPad16,5", 264);
        add(config, ppiByModel, "IPAD_PRO_13_M4", "iPad16,6", 264);
        add(config, ppiByModel, "IPAD_AIR_11_M4", "iPad16,8", 264);
        add(config, ppiByModel, "IPAD_AIR_11_M4", "iPad16,9", 264);
        add(config, ppiByModel, "IPAD_AIR_13_M4", "iPad16,10", 264);
        add(config, ppiByModel, "IPAD_AIR_13_M4", "iPad16,11", 264);
        add(config, ppiByModel, "IPAD_PRO_11_M5", "iPad17,1", 264);
        add(config, ppiByModel, "IPAD_PRO_11_M5", "iPad17,2", 264);
        add(config, ppiByModel, "IPAD_PRO_13_M5", "iPad17,3", 264);
        add(config, ppiByModel, "IPAD_PRO_13_M5", "iPad17,4", 264);

        // iPods
        add(config, ppiByModel, "IPOD_TOUCH_5", "iPod5,1", 326);
        add(config, ppiByModel, "IPOD_TOUCH_6", "iPod7,1", 326);
        add(config, ppiByModel, "IPOD_TOUCH_7", "iPod9,1", 326);

        // The simulator: its machine strings take the density of the model it simulates.
        String simulated = System.getenv("SIMULATOR_MODEL_IDENTIFIER");
        Integer simulatedPpi = simulated != null ? ppiByModel.get(simulated) : null;
        if (simulatedPpi != null) {
            for (String machine : new String[] {"i386", "x86_64", "arm64"}) {
                config.addIosDevice("SIMULATOR", machine, simulatedPpi);
            }
        }
    }

    private static void add(IOSApplicationConfiguration config, Map<String, Integer> ppiByModel,
                            String classifier, String model, int ppi) {
        config.addIosDevice(classifier, model, ppi);
        ppiByModel.put(model, ppi);
    }
}
