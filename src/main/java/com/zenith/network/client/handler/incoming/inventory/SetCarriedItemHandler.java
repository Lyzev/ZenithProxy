package com.zenith.network.client.handler.incoming.inventory;

//import com.zenith.feature.spectator.SpectatorSync;
import com.zenith.network.client.ClientSession;
import com.zenith.network.registry.ClientEventLoopPacketHandler;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;

import static com.zenith.Shared.DEFAULT_LOG;

public class SetCarriedItemHandler implements ClientEventLoopPacketHandler<ClientboundSetCarriedItemPacket, ClientSession> {
    @Override
    public boolean applyAsync(ClientboundSetCarriedItemPacket packet, ClientSession session) {
        try {
            // the mc server does not know we are using this slot until we say so
            // so we make sure its synced correctly here
            // our outbound handler will update the slot in the cache
            session.sendAwait(new ServerboundSetCarriedItemPacket(packet.getSlot()));
//            SpectatorSync.syncPlayerEquipmentWithSpectatorsFromCache();
        } catch (final Exception e) {
            DEFAULT_LOG.error("failed updating main hand slot", e);
        }
        return true;
    }
}
