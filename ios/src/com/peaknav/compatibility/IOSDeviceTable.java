package com.peaknav.compatibility;

import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;

/**
 * The screen density of every iPhone, iPad and iPod that libGDX's own table gets wrong or does
 * not know.
 *
 * <p>libGDX looks a device's ppi up by its model identifier ("iPhone18,2"), in a table that stops
 * at the 2019-2020 models and has not been extended since 2022, on its master branch too. A newer
 * device gets a guess - 132 x scale on an iPad, 164 x scale on an iPhone - that is right for some
 * models by luck and wrong for others, and every size worked out from
 * {@code Gdx.graphics.getDensity()} inherits the error: {@code Units.getUiScale()}, which caps
 * the interface at phone size on tablets, among them.
 *
 * <p>Generated on 2026-09-16 from two sources that agree on 74 of the 78 models both cover:
 * Apple's own simulator device profiles ({@code mainScreenWidthDPI}, kept wherever a model has
 * one) and DeviceKit's device list (github.com/devicekit/DeviceKit, MIT). Where they differ,
 * Apple's value stands. The iPhone 6/6s/7/8 Plus draw at 1242 x 2208 and scale down to 1080p
 * panels, so the pixels an app works in are 461 to the inch, not the panel's 401 - libGDX's 401
 * for those is corrected here - and the iPhone 14 Pro Max is 460, not DeviceKit's 458.
 */
public final class IOSDeviceTable {

    private IOSDeviceTable() {
    }

    /** Adds the models below to {@code config}'s known devices; call before the application starts. */
    public static void register(IOSApplicationConfiguration config) {
        // classifier, model identifier, pixels per inch
        // iPhones
        config.addIosDevice("IPHONE_6_PLUS", "iPhone7,1", 461);   // libGDX says 401
        config.addIosDevice("IPHONE_6S_PLUS", "iPhone8,2", 461);   // libGDX says 401
        config.addIosDevice("IPHONE_7_PLUS", "iPhone9,2", 461);   // libGDX says 401
        config.addIosDevice("IPHONE_8_PLUS", "iPhone10,5", 461);   // libGDX says 401
        config.addIosDevice("IPHONE_12_MINI", "iPhone13,1", 476);
        config.addIosDevice("IPHONE_12", "iPhone13,2", 460);
        config.addIosDevice("IPHONE_12_PRO", "iPhone13,3", 460);
        config.addIosDevice("IPHONE_12_PRO_MAX", "iPhone13,4", 458);
        config.addIosDevice("IPHONE_13_PRO", "iPhone14,2", 460);
        config.addIosDevice("IPHONE_13_PRO_MAX", "iPhone14,3", 458);
        config.addIosDevice("IPHONE_13_MINI", "iPhone14,4", 476);
        config.addIosDevice("IPHONE_13", "iPhone14,5", 460);
        config.addIosDevice("IPHONE_SE_3", "iPhone14,6", 326);
        config.addIosDevice("IPHONE_14", "iPhone14,7", 460);
        config.addIosDevice("IPHONE_14_PLUS", "iPhone14,8", 458);
        config.addIosDevice("IPHONE_14_PRO", "iPhone15,2", 460);
        config.addIosDevice("IPHONE_14_PRO_MAX", "iPhone15,3", 460);
        config.addIosDevice("IPHONE_15", "iPhone15,4", 460);
        config.addIosDevice("IPHONE_15_PLUS", "iPhone15,5", 460);
        config.addIosDevice("IPHONE_15_PRO", "iPhone16,1", 460);
        config.addIosDevice("IPHONE_15_PRO_MAX", "iPhone16,2", 460);
        config.addIosDevice("IPHONE_16_PRO", "iPhone17,1", 460);
        config.addIosDevice("IPHONE_16_PRO_MAX", "iPhone17,2", 460);
        config.addIosDevice("IPHONE_16", "iPhone17,3", 460);
        config.addIosDevice("IPHONE_16_PLUS", "iPhone17,4", 460);
        config.addIosDevice("IPHONE_16E", "iPhone17,5", 460);
        config.addIosDevice("IPHONE_17_PRO", "iPhone18,1", 460);
        config.addIosDevice("IPHONE_17_PRO_MAX", "iPhone18,2", 460);
        config.addIosDevice("IPHONE_17", "iPhone18,3", 460);
        config.addIosDevice("IPHONE_AIR", "iPhone18,4", 460);
        config.addIosDevice("IPHONE_17E", "iPhone18,5", 460);
        // iPads
        config.addIosDevice("IPAD_MINI", "iPad2,5", 163);   // libGDX says 164
        config.addIosDevice("IPAD_MINI", "iPad2,6", 163);   // libGDX says 164
        config.addIosDevice("IPAD_MINI", "iPad2,7", 163);   // libGDX says 164
        config.addIosDevice("IPAD_7", "iPad7,11", 264);
        config.addIosDevice("IPAD_7", "iPad7,12", 264);
        config.addIosDevice("IPAD_PRO_12_INCH_4", "iPad8,11", 264);
        config.addIosDevice("IPAD_PRO_12_INCH_4", "iPad8,12", 264);
        config.addIosDevice("IPAD_8", "iPad11,6", 264);
        config.addIosDevice("IPAD_8", "iPad11,7", 264);
        config.addIosDevice("IPAD_9", "iPad12,1", 264);
        config.addIosDevice("IPAD_9", "iPad12,2", 264);
        config.addIosDevice("IPAD_AIR_4", "iPad13,1", 264);
        config.addIosDevice("IPAD_AIR_4", "iPad13,2", 264);
        config.addIosDevice("IPAD_PRO_11_INCH_3", "iPad13,4", 264);
        config.addIosDevice("IPAD_PRO_11_INCH_3", "iPad13,5", 264);
        config.addIosDevice("IPAD_PRO_11_INCH_3", "iPad13,6", 264);
        config.addIosDevice("IPAD_PRO_11_INCH_3", "iPad13,7", 264);
        config.addIosDevice("IPAD_PRO_12_INCH_5", "iPad13,8", 264);
        config.addIosDevice("IPAD_PRO_12_INCH_5", "iPad13,9", 264);
        config.addIosDevice("IPAD_PRO_12_INCH_5", "iPad13,10", 264);
        config.addIosDevice("IPAD_PRO_12_INCH_5", "iPad13,11", 264);
        config.addIosDevice("IPAD_AIR_5", "iPad13,16", 264);
        config.addIosDevice("IPAD_AIR_5", "iPad13,17", 264);
        config.addIosDevice("IPAD_10", "iPad13,18", 264);
        config.addIosDevice("IPAD_10", "iPad13,19", 264);
        config.addIosDevice("IPAD_MINI_6", "iPad14,1", 326);
        config.addIosDevice("IPAD_MINI_6", "iPad14,2", 326);
        config.addIosDevice("IPAD_PRO_11_INCH_4", "iPad14,3", 264);
        config.addIosDevice("IPAD_PRO_11_INCH_4", "iPad14,4", 264);
        config.addIosDevice("IPAD_PRO_12_INCH_6", "iPad14,5", 264);
        config.addIosDevice("IPAD_PRO_12_INCH_6", "iPad14,6", 264);
        config.addIosDevice("IPAD_AIR_11_M2", "iPad14,8", 264);
        config.addIosDevice("IPAD_AIR_11_M2", "iPad14,9", 264);
        config.addIosDevice("IPAD_AIR_13_M2", "iPad14,10", 264);
        config.addIosDevice("IPAD_AIR_13_M2", "iPad14,11", 264);
        config.addIosDevice("IPAD_AIR_11_M3", "iPad15,3", 264);
        config.addIosDevice("IPAD_AIR_11_M3", "iPad15,4", 264);
        config.addIosDevice("IPAD_AIR_13_M3", "iPad15,5", 264);
        config.addIosDevice("IPAD_AIR_13_M3", "iPad15,6", 264);
        config.addIosDevice("IPAD_A16", "iPad15,7", 264);
        config.addIosDevice("IPAD_A16", "iPad15,8", 264);
        config.addIosDevice("IPAD_MINI_A17_PRO", "iPad16,1", 326);
        config.addIosDevice("IPAD_MINI_A17_PRO", "iPad16,2", 326);
        config.addIosDevice("IPAD_PRO_11_M4", "iPad16,3", 264);
        config.addIosDevice("IPAD_PRO_11_M4", "iPad16,4", 264);
        config.addIosDevice("IPAD_PRO_13_M4", "iPad16,5", 264);
        config.addIosDevice("IPAD_PRO_13_M4", "iPad16,6", 264);
        config.addIosDevice("IPAD_AIR_11_M4", "iPad16,8", 264);
        config.addIosDevice("IPAD_AIR_11_M4", "iPad16,9", 264);
        config.addIosDevice("IPAD_AIR_13_M4", "iPad16,10", 264);
        config.addIosDevice("IPAD_AIR_13_M4", "iPad16,11", 264);
        config.addIosDevice("IPAD_PRO_11_M5", "iPad17,1", 264);
        config.addIosDevice("IPAD_PRO_11_M5", "iPad17,2", 264);
        config.addIosDevice("IPAD_PRO_13_M5", "iPad17,3", 264);
        config.addIosDevice("IPAD_PRO_13_M5", "iPad17,4", 264);
    }
}
