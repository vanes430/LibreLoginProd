/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.velocity;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import xyz.kyngs.librelogin.api.event.exception.EventCancelledException;
import xyz.kyngs.librelogin.common.config.ConfigurationKeys;
import xyz.kyngs.librelogin.common.listener.AuthenticListeners;

public class VelocityListeners
        extends AuthenticListeners<VelocityLibreLogin, Player, RegisteredServer> {

    private static final AttributeKey<?> FLOODGATE_ATTR = AttributeKey.valueOf("floodgate-player");
    private final ConcurrentHashMap<UUID, Continuation> continuations = new ConcurrentHashMap<>();

    public VelocityListeners(VelocityLibreLogin plugin) {
        super(plugin);
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPostLogin(PostLoginEvent event) {
        onPostLogin(event.getPlayer(), null);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        continuations.remove(uuid);
        onPlayerDisconnect(event.getPlayer());
    }

    @Subscribe(order = PostOrder.NORMAL)
    public void onProfileRequest(GameProfileRequestEvent event) {
        var existing = event.getGameProfile();

        if (existing != null && plugin.fromFloodgate(existing.getId())) return;

        var profile = plugin.getDatabaseProvider().getByName(event.getUsername());

        var gProfile = event.getOriginalProfile();

        event.setGameProfile(
                new GameProfile(profile.getUuid(), gProfile.getName(), gProfile.getProperties()));
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPreLogin(PreLoginEvent event) {

        if (!event.getResult().isAllowed()) return;

        // If floodgate is present, attempt to extract the floodgate player from the connection
        // channel.
        if (plugin.floodgateEnabled()) {
            try {
                var user =
                        com.github.retrooper.packetevents.PacketEvents.getAPI()
                                .getPlayerManager()
                                .getUser(event.getConnection());

                if (user != null) {
                    Channel channel = (Channel) user.getChannel();

                    if (channel != null && channel.attr(FLOODGATE_ATTR).get() != null) {
                        return; // Player is coming from Floodgate
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warn("Failed to check if player is coming from Floodgate.");
                e.printStackTrace();
                event.setResult(
                        PreLoginEvent.PreLoginComponentResult.denied(
                                Component.text("Internal LibreLogin error")));
                return;
            }
        }

        var result =
                onPreLogin(
                        event.getUsername(), event.getConnection().getRemoteAddress().getAddress());

        event.setResult(
                switch (result.state()) {
                    case DENIED -> {
                        assert result.message() != null;
                        yield PreLoginEvent.PreLoginComponentResult.denied(result.message());
                    }
                    case FORCE_ONLINE -> PreLoginEvent.PreLoginComponentResult.forceOnlineMode();
                    case FORCE_OFFLINE -> PreLoginEvent.PreLoginComponentResult.forceOfflineMode();
                });
    }

    @Subscribe(order = PostOrder.LAST)
    public EventTask chooseServer(PlayerChooseInitialServerEvent event) {
        return EventTask.withContinuation(
                continuation -> {
                    Player player = event.getPlayer();
                    plugin.getLogger()
                            .info(
                                    "Debug: PlayerChooseInitialServerEvent for "
                                            + player.getUsername());
                    var authProvider = plugin.getAuthorizationProvider();

                    if (authProvider == null
                            || authProvider.isAuthorized(player)
                            || plugin.fromFloodgate(player.getUniqueId())) {
                        plugin.getLogger()
                                .info(
                                        "Debug: Skipping auth for "
                                                + player.getUsername()
                                                + " (Authorized or Floodgate)");
                        handleInitialServer(event, continuation);
                        return;
                    }

                    var user =
                            com.github.retrooper.packetevents.PacketEvents.getAPI()
                                    .getPlayerManager()
                                    .getUser(player);

                    if (user != null) {
                        int protocolVersion = user.getClientVersion().getProtocolVersion();
                        plugin.getLogger()
                                .info(
                                        "Debug: Player "
                                                + player.getUsername()
                                                + " protocol: "
                                                + protocolVersion);

                        if (protocolVersion >= 771) {
                            continuations.put(player.getUniqueId(), continuation);
                            var dbUser =
                                    plugin.getDatabaseProvider().getByUUID(player.getUniqueId());
                            authProvider
                                    .getDialogPrompt()
                                    .checkAndSend(player, dbUser != null && dbUser.isRegistered());
                        } else {
                            handleInitialServer(event, continuation);
                        }
                    } else {
                        handleInitialServer(event, continuation);
                    }
                });
    }

    public void resumeConnection(UUID uuid) {
        Continuation continuation = continuations.remove(uuid);
        if (continuation != null) {
            continuation.resume();
        }
    }

    private void handleInitialServer(
            PlayerChooseInitialServerEvent event, Continuation continuation) {
        var server = chooseServer(event.getPlayer(), null, null);

        if (server.value() == null) {
            event.getPlayer()
                    .disconnect(
                            plugin.getMessages()
                                    .getMessage("kick-no-" + (server.key() ? "lobby" : "limbo")));
            event.setInitialServer(null);
        } else {
            event.setInitialServer(server.value());
        }
        continuation.resume();
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onKick(KickedFromServerEvent event) {
        var reason = event.getServerKickReason().orElse(Component.text("null"));
        var message =
                plugin.getMessages()
                        .getMessage("info-kick")
                        .replaceText(
                                builder -> builder.matchLiteral("%reason%").replacement(reason));
        var player = event.getPlayer();

        if (event.kickedDuringServerConnect()) {
            event.setResult(KickedFromServerEvent.Notify.create(message));
        } else {
            if (!plugin.getConfiguration().get(ConfigurationKeys.FALLBACK)
                    || plugin.getServerHandler()
                            .getLobbyServers()
                            .containsValue(event.getServer())) {
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(message));
            } else {
                try {
                    var server =
                            plugin.getServerHandler()
                                    .chooseLobbyServer(
                                            plugin.getDatabaseProvider()
                                                    .getByUUID(player.getUniqueId()),
                                            player,
                                            false,
                                            true);

                    if (server == null) throw new NoSuchElementException();

                    event.setResult(KickedFromServerEvent.RedirectPlayer.create(server, message));
                } catch (NoSuchElementException | EventCancelledException e) {
                    event.setResult(KickedFromServerEvent.DisconnectPlayer.create(message));
                }
            }
        }
    }
}
