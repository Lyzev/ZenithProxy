package com.zenith.network.client.handler.incoming.inventory;

import com.zenith.network.client.ClientSession;
import com.zenith.network.registry.ClientEventLoopPacketHandler;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.jspecify.annotations.NonNull;

import static com.zenith.Shared.CACHE;
//import static com.zenith.feature.spectator.SpectatorSync.syncPlayerEquipmentWithSpectatorsFromCache;


public class ContainerSetContentHandler implements ClientEventLoopPacketHandler<ClientboundContainerSetContentPacket, ClientSession> {
    @Override
    public boolean applyAsync(@NonNull ClientboundContainerSetContentPacket packet, @NonNull ClientSession session) {
        CACHE.getPlayerCache().setInventory(packet.getContainerId(), packet.getItems());
        CACHE.getPlayerCache().getInventoryCache().setMouseStack(packet.getCarriedItem());
        CACHE.getPlayerCache().getActionId().set(packet.getStateId());
//        syncPlayerEquipmentWithSpectatorsFromCache();
        return true;
    }
}
