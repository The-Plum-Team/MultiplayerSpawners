package com.theplumteam.multiplayerspawners.client;

import java.util.ArrayList;
import com.theplumteam.multiplayerspawners.Constants;
import com.theplumteam.multiplayerspawners.block.SpawnerStateProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client-side storage for spawner states with visual updates via "mark dirty" approach.
 * FIX: Removed all logic that deletes data based on world state to prevent data loss during break prediction.
 */
public class ClientSpawnerData {

    private static final Map<BlockPos, Boolean> playerDisabledSpawners = new HashMap<>();
    private static final Set<BlockPos> spawnersNeedingUpdate = new HashSet<>();
    private static final Set<BlockPos> vanillaTreatedSpawners = new HashSet<>();
    /** Single-use spawners of other mods that are used up for the current player. */
    private static final Set<BlockPos> usedUpOneShots = new HashSet<>();

    private static String cachedPlayerName = null;
    private static String currentDimension = null;
    private static long lastPeriodicRefresh = 0;

    public static void updateSpawnerState(BlockPos pos, Set<String> disabledPlayers, boolean treatedAsVanilla) {
        String currentPlayerName = getCurrentPlayerName();
        if (currentPlayerName == null) return;

        boolean isDisabledForPlayer = disabledPlayers.contains(currentPlayerName);
        Boolean previousState = playerDisabledSpawners.put(pos, isDisabledForPlayer);

        boolean wasVanillaTreated = vanillaTreatedSpawners.contains(pos);
        if (treatedAsVanilla) {
            vanillaTreatedSpawners.add(pos);
        } else {
            vanillaTreatedSpawners.remove(pos);
        }

        boolean stateChanged = (previousState == null || !previousState.equals(isDisabledForPlayer));
        boolean treatmentChanged = (wasVanillaTreated != treatedAsVanilla);

        Constants.debug("CLIENT: Spawner at {} updated - disabled: {}, treatedAsVanilla: {} (was: disabled={}, vanilla={})",
                pos, isDisabledForPlayer, treatedAsVanilla, previousState, wasVanillaTreated);

        // Always force an update when a packet arrives.
        boolean forceUpdate = true;
        boolean success = updateBlockStateInWorld(pos, isDisabledForPlayer, forceUpdate);

        // If failed (e.g. chunk not loaded), mark for update later
        if (!success) {
            spawnersNeedingUpdate.add(pos);
        } else {
            spawnersNeedingUpdate.remove(pos);
        }
    }

    private static boolean updateBlockStateInWorld(BlockPos pos, boolean isDisabledForPlayer, boolean forceUpdate) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.isLoaded(pos)) return false;

        var currentState = minecraft.level.getBlockState(pos);

        // CRITICAL FIX: Ghost Block Handling
        // If the client predicted the break, currentState is likely AIR.
        // The old code returned 'false' here, so the Spawner never reappeared.
        // Now, if forceUpdate is true (packet received), we assume a Spawner exists there.
        boolean isSpawner = currentState.getBlock() instanceof SpawnerBlock;

        if (!isSpawner) {
            if (forceUpdate) {
                // The client thinks it's AIR (or something else), but the server sent a spawner update.
                // We must forcefully restore the Spawner block to fix the "invisible block" glitch.
                // We use the vanilla spawner block as a base.
                currentState = Blocks.SPAWNER.defaultBlockState();
            } else {
                // If this is just a routine check (not forced by packet) and it's not a spawner, ignore it.
                return false;
            }
        }

        if (!currentState.hasProperty(SpawnerStateProperties.MULTIPLAYER_ACTIVATED)) return false;

        boolean currentActivated = currentState.getValue(SpawnerStateProperties.MULTIPLAYER_ACTIVATED);
        boolean isVanillaTreated = vanillaTreatedSpawners.contains(pos);

        // Logic: Vanilla/Disabled = Not Activated (False). Active Mod Spawner = Activated (True).
        boolean newActivated;
        if (isVanillaTreated) {
            newActivated = false;
        } else {
            newActivated = !isDisabledForPlayer;
        }

        if (currentActivated != newActivated || forceUpdate) {
            try {
                var newState = currentState.setValue(SpawnerStateProperties.MULTIPLAYER_ACTIVATED, newActivated);
                // Flag 3 = Block Update + Send to Clients (Render Update)
                // Flag 16 = Prevent Neighbor Reactions (important to stop chains)
                // We must use setBlock to physically put the block back if it was AIR
                minecraft.level.setBlock(pos, newState, 3);

                markBlockForRerender(pos, currentState, newState);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private static void markBlockForRerender(BlockPos pos, BlockState oldState, BlockState newState) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.levelRenderer == null) return;
        try {
            // Forcefully notify the renderer that this block changed
            minecraft.level.sendBlockUpdated(pos, oldState, newState, 3);
            minecraft.level.setBlocksDirty(pos, oldState, newState);
        } catch (Exception e) {
            // Ignore errors during rendering update
        }
    }

    /** A single-use spawner's state: only remembered, the block itself is left alone. */
    public static void updateOneShotState(BlockPos pos, Set<String> usedBy) {
        String currentPlayerName = getCurrentPlayerName();
        if (currentPlayerName != null && usedBy.contains(currentPlayerName)) {
            usedUpOneShots.add(pos.immutable());
        } else {
            usedUpOneShots.remove(pos);
        }
    }

    public static boolean isOneShotUsedUpForCurrentPlayer(BlockPos pos) {
        return usedUpOneShots.contains(pos);
    }

    public static boolean isSpawnerDisabledForCurrentPlayer(BlockPos pos) {
        return playerDisabledSpawners.getOrDefault(pos, false);
    }

    public static boolean isPlayerPlacedSpawner(BlockPos pos) {
        return vanillaTreatedSpawners.contains(pos);
    }

    public static void clearAll() {
        playerDisabledSpawners.clear();
        spawnersNeedingUpdate.clear();
        vanillaTreatedSpawners.clear();
        usedUpOneShots.clear();
        cachedPlayerName = null;
        currentDimension = null;
        lastPeriodicRefresh = 0;
        Constants.debug("CLIENT: Cleared all client-side spawner data");
    }

    public static void onDimensionChange(String newDimension) {
        currentDimension = newDimension;
        spawnersNeedingUpdate.addAll(playerDisabledSpawners.keySet());
        scheduleTargetedRefresh();
    }

    public static void onChunkLoaded(int chunkX, int chunkZ) {
        refreshSpawnersInChunk(chunkX, chunkZ);
    }

    private static void refreshSpawnersInChunk(int chunkX, int chunkZ) {
        Set<BlockPos> allTrackedSpawners = new HashSet<>(playerDisabledSpawners.keySet());
        allTrackedSpawners.addAll(vanillaTreatedSpawners);

        for (BlockPos pos : allTrackedSpawners) {
            if ((pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ) {
                Boolean isDisabled = playerDisabledSpawners.get(pos);
                boolean effectivelyDisabled = isDisabled != null && isDisabled;
                boolean success = updateBlockStateInWorld(pos, effectivelyDisabled, true);
                if (success) spawnersNeedingUpdate.remove(pos);
                else spawnersNeedingUpdate.add(pos);
            }
        }
    }

    public static void removeSpawner(BlockPos pos) {
        playerDisabledSpawners.remove(pos);
        spawnersNeedingUpdate.remove(pos);
        vanillaTreatedSpawners.remove(pos);
    }

    private static String getCurrentPlayerName() {
        if (cachedPlayerName == null && Minecraft.getInstance().player != null) {
            cachedPlayerName = Minecraft.getInstance().player.getName().getString();
        }
        return cachedPlayerName;
    }

    public static void refreshPendingSpawnerStates() {
        if (spawnersNeedingUpdate.isEmpty()) return;
        var spawnersToUpdate = new HashSet<>(spawnersNeedingUpdate);
        for (BlockPos pos : spawnersToUpdate) {
            Boolean isDisabled = playerDisabledSpawners.get(pos);
            if (isDisabled != null) {
                boolean success = updateBlockStateInWorld(pos, isDisabled, true);
                if (success) spawnersNeedingUpdate.remove(pos);
            }
        }
    }

    private static void scheduleTargetedRefresh() {
        var minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(ClientSpawnerData::refreshPendingSpawnerStates);
        }
    }

    public static void periodicRefresh() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPeriodicRefresh < 5000) return; // Check more often (5s)
        lastPeriodicRefresh = currentTime;
        if (!spawnersNeedingUpdate.isEmpty() && getCurrentPlayerName() != null) {
            refreshPendingSpawnerStates();
        }
    }

    public static void monitorAndCorrectSpawnerStates() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;

        int radius = 32;
        var playerPos = minecraft.player.blockPosition();
        var spawnersToCorrect = new ArrayList<BlockPos>();

        // We iterate over known spawners.
        for (Map.Entry<BlockPos, Boolean> entry : playerDisabledSpawners.entrySet()) {
            BlockPos spawnerPos = entry.getKey();

            // Skip far away
            if (spawnerPos.distSqr(playerPos) > radius * radius) continue;
            // Skip unloaded
            if (!minecraft.level.isLoaded(spawnerPos)) continue;

            var currentState = minecraft.level.getBlockState(spawnerPos);

            // If block is AIR (client prediction), skip. DO NOT REMOVE DATA.
            if (!(currentState.getBlock() instanceof SpawnerBlock)) continue;

            if (!currentState.hasProperty(SpawnerStateProperties.MULTIPLAYER_ACTIVATED)) continue;

            boolean isDisabled = entry.getValue();
            boolean isVanillaTreated = vanillaTreatedSpawners.contains(spawnerPos);
            boolean currentActivated = currentState.getValue(SpawnerStateProperties.MULTIPLAYER_ACTIVATED);

            boolean expectedActivated;
            if (isVanillaTreated) {
                expectedActivated = false;
            } else {
                expectedActivated = !isDisabled;
            }

            if (currentActivated != expectedActivated) {
                spawnersToCorrect.add(spawnerPos);
            }
        }

        for (BlockPos pos : spawnersToCorrect) {
            Boolean isDisabled = playerDisabledSpawners.get(pos);
            if (isDisabled != null) {
                updateBlockStateInWorld(pos, isDisabled, true);
            }
        }
    }
}
