package com.modkit.injector.engine;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedClassDef;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the Unity activity to patch, across Unity versions.
 *
 * Unity export history:
 *   - old builds   : activities extend com.unity3d.player.UnityPlayerActivity
 *   - Unity 2023+  : "Game Activity" entry -> UnityPlayerGameActivity
 *   - games        : often subclass one of the above with their own activity
 *
 * Injecting System.loadLibrary into the base Unity activity's class initializer
 * is reliable regardless of subclassing: initializing any subclass first
 * initializes its superclass, so the base <clinit> always runs before the
 * game's own activity code.
 */
public final class UnityDetector {

    public static final String UNITY_ACTIVITY = "Lcom/unity3d/player/UnityPlayerActivity;";
    public static final String UNITY_GAME_ACTIVITY = "Lcom/unity3d/player/UnityPlayerGameActivity;";

    public static class UnityInfo {
        /** dex entry name that contains the chosen target class. */
        public String dexEntry;
        /** smali type descriptor of the class to patch, e.g. L..;UnityPlayerGameActivity; */
        public String targetType;
        /** human-readable description of what was detected. */
        public String description;
        /** every Unity-ish activity class found (base + subclasses). */
        public final List<String> allActivities = new ArrayList<>();
        public boolean found;
    }

    private UnityDetector() {}

    public static UnityInfo detect(ApkArchive apk, Logger log) throws IOException {
        UnityInfo info = new UnityInfo();

        String baseActivityDex = null;   // dex containing UnityPlayerActivity
        String gameActivityDex = null;   // dex containing UnityPlayerGameActivity

        List<String> subclassTypes = new ArrayList<>();
        String subclassDex = null;

        for (String dexName : apk.dexNames()) {
            byte[] bytes = apk.read(dexName);
            if (bytes == null) continue;
            DexBackedDexFile dex = DexBackedDexFile.fromInputStream(
                    Opcodes.getDefault(), new ByteArrayInputStream(bytes));

            for (DexBackedClassDef cls : dex.getClasses()) {
                String type = cls.getType();
                String sup = cls.getSuperclass();

                if (UNITY_GAME_ACTIVITY.equals(type)) {
                    gameActivityDex = dexName;
                    info.allActivities.add(type);
                } else if (UNITY_ACTIVITY.equals(type)) {
                    baseActivityDex = dexName;
                    info.allActivities.add(type);
                }

                if (UNITY_ACTIVITY.equals(sup) || UNITY_GAME_ACTIVITY.equals(sup)) {
                    subclassTypes.add(type);
                    if (subclassDex == null) subclassDex = dexName;
                    info.allActivities.add(type + " (extends " + shortName(sup) + ")");
                }
            }
        }

        // Prefer the newest base class present (GameActivity), then the classic one,
        // then — if neither base is present but a subclass is — the subclass itself.
        if (gameActivityDex != null) {
            info.found = true;
            info.dexEntry = gameActivityDex;
            info.targetType = UNITY_GAME_ACTIVITY;
            info.description = "Unity 2023+/Unity 6 (UnityPlayerGameActivity)";
        } else if (baseActivityDex != null) {
            info.found = true;
            info.dexEntry = baseActivityDex;
            info.targetType = UNITY_ACTIVITY;
            info.description = "Classic Unity (UnityPlayerActivity)";
        } else if (!subclassTypes.isEmpty()) {
            info.found = true;
            info.dexEntry = subclassDex;
            info.targetType = subclassTypes.get(0);
            info.description = "Unity activity subclass " + shortName(subclassTypes.get(0));
        } else {
            info.found = false;
            info.description = "No Unity activity found (is this a Unity game?)";
        }

        if (log != null) {
            if (info.found) {
                log.ok("Unity target: " + info.description);
                log.log("   class = " + info.targetType + "  in " + info.dexEntry);
            } else {
                log.warn(info.description);
            }
        }
        return info;
    }

    private static String shortName(String type) {
        // Lcom/unity3d/player/UnityPlayerActivity; -> UnityPlayerActivity
        int slash = type.lastIndexOf('/');
        int end = type.endsWith(";") ? type.length() - 1 : type.length();
        return type.substring(slash + 1, end);
    }
}
