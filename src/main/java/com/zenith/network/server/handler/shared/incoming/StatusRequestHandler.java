package com.zenith.network.server.handler.shared.incoming;

import com.zenith.network.registry.PacketHandler;
import com.zenith.network.server.ServerSession;
import com.zenith.network.server.ZenithServerInfoBuilder;
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo;
import org.geysermc.mcprotocollib.protocol.packet.status.clientbound.ClientboundStatusResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.status.serverbound.ServerboundStatusRequestPacket;

import static com.zenith.Shared.CONFIG;
import static com.zenith.Shared.SERVER_LOG;

public class StatusRequestHandler implements PacketHandler<ServerboundStatusRequestPacket, ServerSession> {
    @Override
    public ServerboundStatusRequestPacket apply(final ServerboundStatusRequestPacket packet, final ServerSession session) {
        if (CONFIG.server.ping.logPings)
            SERVER_LOG.info("[Ping] Request from: {} [{}] to: {}:{}",
                            session.getRemoteAddress(),
                            session.getProtocolVersionId(), // ProtocolVersion.getProtocol(session.getProtocolVersionId()).getName()
                            session.getConnectingServerAddress(),
                            session.getConnectingServerPort());
        ServerStatusInfo info = ZenithServerInfoBuilder.INSTANCE.buildInfo(session);
        if (info == null) session.disconnect("bye");
        else session.send(new ClientboundStatusResponsePacket(info));
        return null;
    }
}
