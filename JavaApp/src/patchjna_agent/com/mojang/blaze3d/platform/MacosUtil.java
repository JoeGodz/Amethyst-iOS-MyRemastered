package com.mojang.blaze3d.platform;

/**
 * iOS no-op replacement for Minecraft's MacosUtil, delivered via PatchJNAAgent.
 *
 * Background (26.3 crash on unjailbroken / no DyldLVBypass):
 *   Window.init -> MacosUtil.disableCloseWindowMenuItem()
 *     -> ca.weblite.objc.Client -> com.sun.jna.Native
 *     -> extracts libjnidispatch to natives/jna/jna*.tmp
 *     -> dlopen fails with "code signature invalid"
 *     -> NoClassDefFoundError: Could not initialize class com.sun.jna.Native
 *
 * launcher.jar used to shadow this class, but that package is stripped to avoid
 * Forge module ResolutionException (split package com.mojang.blaze3d.platform).
 * PatchJNAAgent replaces the class at load time instead — same effect, no
 * module export conflict.
 *
 * Must mirror every member Minecraft calls (missing members -> NoSuchMethodError):
 *   - Window.init        -> disableCloseWindowMenuItem()
 *   - Options / Minecraft -> setCtrlClickEmulatesRightClick / setFullscreenMenuVisibility
 *   - VideoSettingsScreen -> IS_MACOS
 */
public final class MacosUtil {
    private MacosUtil() {
    }

    public static final boolean IS_MACOS = false;

    public static void disableCloseWindowMenuItem() {
        // No-op: no AppKit menu bar on iOS; avoids JNA/objc bridge entirely.
    }

    public static void setCtrlClickEmulatesRightClick(boolean value) {
        // No-op: not applicable on iOS.
    }

    public static void setFullscreenMenuVisibility(boolean value) {
        // No-op: not applicable on iOS.
    }
}
