package com.theplumteam.multiplayerspawners.network;

import com.theplumteam.multiplayerspawners.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
//? if >=1.21 {
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-to-client sync of which players disabled each spawner, batched per chunk. An update with
 * {@code oneShot} is a single-use spawner of another mod (OneShotSpawners): its players are those
 * it is used up for, and the client leaves the block itself alone.
 * The wire format is the same on every loader and version.
 */
public record SpawnerStatePacket(List<SpawnerUpdate> updates, boolean isFullSync)
        //? if >=1.21 {
        implements CustomPacketPayload
        //?}
{

    public static final ResourceLocation ID = Constants.id("spawner_state");

    //? if >=1.21 {
    public static final CustomPacketPayload.Type<SpawnerStatePacket> TYPE = new CustomPacketPayload.Type<>(ID);
    public static final StreamCodec<FriendlyByteBuf, SpawnerStatePacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> packet.write(buf), SpawnerStatePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
    //?}

    public record SpawnerUpdate(BlockPos pos, Set<String> disabledPlayers, boolean treatedAsVanilla, boolean oneShot) {
        public SpawnerUpdate(BlockPos pos, Set<String> disabledPlayers, boolean treatedAsVanilla) {
            this(pos, disabledPlayers, treatedAsVanilla, false);
        }
    }

    public static SpawnerStatePacket single(BlockPos pos, Set<String> disabledPlayers, boolean isFullSync, boolean treatedAsVanilla) {
        List<SpawnerUpdate> updates = new ArrayList<>(1);
        updates.add(new SpawnerUpdate(pos, new HashSet<>(disabledPlayers), treatedAsVanilla));
        return new SpawnerStatePacket(updates, isFullSync);
    }

    public static SpawnerStatePacket read(FriendlyByteBuf buf) {
        boolean isFullSync = buf.readBoolean();
        int updateCount = buf.readVarInt();
        List<SpawnerUpdate> updates = new ArrayList<>(updateCount);
        for (int i = 0; i < updateCount; i++) {
            BlockPos pos = buf.readBlockPos();
            boolean treatedAsVanilla = buf.readBoolean();
            boolean oneShot = buf.readBoolean();
            int playerCount = buf.readVarInt();
            Set<String> disabledPlayers = new HashSet<>(playerCount);
            for (int p = 0; p < playerCount; p++) {
                disabledPlayers.add(buf.readUtf());
            }
            updates.add(new SpawnerUpdate(pos, disabledPlayers, treatedAsVanilla, oneShot));
        }
        return new SpawnerStatePacket(updates, isFullSync);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(isFullSync);
        buf.writeVarInt(updates.size());
        for (SpawnerUpdate update : updates) {
            buf.writeBlockPos(update.pos());
            buf.writeBoolean(update.treatedAsVanilla());
            buf.writeBoolean(update.oneShot());
            buf.writeVarInt(update.disabledPlayers().size());
            for (String playerName : update.disabledPlayers()) {
                buf.writeUtf(playerName);
            }
        }
    }
}
