/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package kernitus.plugin.OldCombatMechanics.utilities.storage;

import kernitus.plugin.OldCombatMechanics.OCMMain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stores data associated with players to disk, persisting across server restarts.
 */
public class PlayerStorage {

    private static OCMMain plugin;
    private static Map<UUID, PlayerData> dataMap;

    public static void initialise(OCMMain plugin) {
        PlayerStorage.plugin = plugin;
        dataMap = new HashMap<>();
    }

    public static PlayerData getPlayerData(UUID uuid) {
        return dataMap.computeIfAbsent(uuid, k -> new PlayerData());
    }

    public static void clearPlayerData(UUID uuid) {
        dataMap.remove(uuid);
    }

    public static void setPlayerData(UUID uuid, PlayerData playerData) {
        dataMap.put(uuid, playerData);
    }
}