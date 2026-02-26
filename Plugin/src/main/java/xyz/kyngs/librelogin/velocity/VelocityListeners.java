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
import xyz.kyngs.librelogin.api.BiHolder;
import xyz.kyngs.librelogin.api.database.User;
import xyz.kyngs.librelogin.api.event.exception.EventCancelledException;
import xyz.kyngs.librelogin.common.config.ConfigurationKeys;
import xyz.kyngs.librelogin.common.listener.AuthenticListeners;

public class VelocityListeners
        extends AuthenticListeners<VelocityLibreLogin, Player, RegisteredServer> {

    private static final AttributeKey<?> FLOODGATE_ATTR = AttributeKey.valueOf("floodgate-player");
    private final ConcurrentHashMap<UUID, BiHolder<PlayerChooseInitialServerEvent, Continuation>>
            continuations = new ConcurrentHashMap<>();

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

        if (profile == null) return;

        var gProfile = event.getOriginalProfile();

        event.setGameProfile(
                new GameProfile(profile.getUuid(), gProfile.getName(), gProfile.getProperties()));
    }

    @Subscribe(order = PostOrder.EARLY)
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
                    var authProvider = plugin.getAuthorizationProvider();

                    if (authProvider == null
                            || authProvider.isAuthorized(player)
                            || plugin.fromFloodgate(player.getUniqueId())) {
                        var dbUser = plugin.getDatabaseProvider().getByUUID(player.getUniqueId());
                        handleInitialServer(event, continuation, dbUser, true);
                        return;
                    }

                    var user =
                            com.github.retrooper.packetevents.PacketEvents.getAPI()
                                    .getPlayerManager()
                                    .getUser(player);

                    if (user != null && user.getClientVersion().getProtocolVersion() >= 771) {
                        // Tunda koneksi dan kirim dialog
                        // JANGAN panggil handleInitialServer di sini agar event tidak dianggap
                        // 'selesai' dengan server null
                        continuations.put(
                                player.getUniqueId(), new BiHolder<>(event, continuation));
                        var dbUser = plugin.getDatabaseProvider().getByUUID(player.getUniqueId());
                        if (dbUser != null) {
                            authProvider.startTracking(dbUser, player);
                        }
                        authProvider
                                .getDialogPrompt()
                                .checkAndSend(player, dbUser != null && dbUser.isRegistered());
                    } else {
                        // Versi lama, lanjutkan ke Limbo/Lobby as usual
                        var dbUser = plugin.getDatabaseProvider().getByUUID(player.getUniqueId());
                        handleInitialServer(event, continuation, dbUser, false);
                    }
                });
    }

    public void resumeConnection(UUID uuid) {
        var holder = continuations.remove(uuid);
        if (holder != null) {
            var user = plugin.getDatabaseProvider().getByUUID(uuid);
            handleInitialServer(holder.key(), holder.value(), user, true);
        }
    }

    public boolean isWaitingForResume(UUID uuid) {
        return continuations.containsKey(uuid);
    }

    private void handleInitialServer(
            PlayerChooseInitialServerEvent event,
            Continuation continuation,
            User user,
            boolean authorized) {
        BiHolder<Boolean, RegisteredServer> server;

        if (authorized) {
            server =
                    new BiHolder<>(
                            true,
                            plugin.getServerHandler()
                                    .chooseLobbyServer(user, event.getPlayer(), true, false));
        } else {
            server = chooseServer(event.getPlayer(), null, user);
        }

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
