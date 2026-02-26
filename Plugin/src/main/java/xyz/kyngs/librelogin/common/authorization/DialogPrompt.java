/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.authorization;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.dialog.*;
import com.github.retrooper.packetevents.protocol.dialog.action.*;
import com.github.retrooper.packetevents.protocol.dialog.body.*;
import com.github.retrooper.packetevents.protocol.dialog.button.*;
import com.github.retrooper.packetevents.protocol.dialog.input.*;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import xyz.kyngs.librelogin.api.event.events.WrongPasswordEvent;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;
import xyz.kyngs.librelogin.common.event.events.AuthenticWrongPasswordEvent;

public class DialogPrompt<P, S> extends PacketListenerAbstract {

    private final AuthenticLibreLogin<P, S> plugin;

    public DialogPrompt(AuthenticLibreLogin<P, S> plugin) {
        this.plugin = plugin;
    }

    public boolean checkAndSend(P player, boolean registered) {
        if (!PacketEvents.getAPI().isInitialized()) {
            plugin.getLogger().info("PacketEvents not initialized, initializing now...");
            PacketEvents.getAPI().init();
        }
        var user = PacketEvents.getAPI().getPlayerManager().getUser(player);

        int protocolVersion;
        String playerName = plugin.getPlatformHandle().getUsernameForPlayer(player);

        if (user == null) {
            plugin.getLogger()
                    .info(
                            "Debug: PacketEvents User is NULL for player "
                                    + playerName
                                    + ". Dialog cannot be sent.");
            return false;
        }

        protocolVersion = user.getClientVersion().getProtocolVersion();
        plugin.getLogger()
                .info(
                        "Debug: Player "
                                + playerName
                                + " protocol version (PacketEvents): "
                                + protocolVersion);

        if (protocolVersion >= 771) {
            plugin.getLogger()
                    .info(
                            "Debug: Protocol version "
                                    + protocolVersion
                                    + " is >= 771. Sending dialog to "
                                    + playerName);
            sendInputDialog(player, registered, DialogAction.CLOSE);
            return true;
        } else {
            plugin.getLogger()
                    .info(
                            "Debug: Protocol version "
                                    + protocolVersion
                                    + " is < 771. Falling back to text prompt for "
                                    + playerName);
        }
        return false;
    }

    private void sendInputDialog(P player, boolean registered, DialogAction action) {
        // Setup Kontrol Input
        TextInputControl controlA =
                new TextInputControl(
                        100,
                        getMessages().getMessage("dialog-input-password"),
                        true,
                        "",
                        255,
                        null);
        Input inputA = new Input("password", controlA);

        List<Input> inputs;
        if (!registered) {
            TextInputControl controlB =
                    new TextInputControl(
                            100,
                            getMessages().getMessage("dialog-input-confirm-password"),
                            true,
                            "",
                            255,
                            null);
            Input inputB = new Input("confirm_password", controlB);
            inputs = Arrays.asList(inputA, inputB);
        } else {
            inputs = Collections.singletonList(inputA);
        }

        // Setup Body (Pesan)
        Component title = getMessages().getMessage(registered ? "title-login" : "title-register");
        Component prompt =
                getMessages()
                        .getMessage(registered ? "dialog-login-prompt" : "dialog-register-prompt");

        PlainMessage plainMsg = new PlainMessage(prompt, 100);
        PlainMessageDialogBody body = new PlainMessageDialogBody(plainMsg);

        // Setup Button dengan ID Action
        CommonButtonData btnDataSubmit =
                new CommonButtonData(getMessages().getMessage("dialog-button-submit"), null, 50);
        ActionButton submitBtn =
                new ActionButton(
                        btnDataSubmit,
                        new DynamicCustomAction(new ResourceLocation("kite", "submit"), null));

        CommonButtonData btnDataCancel =
                new CommonButtonData(getMessages().getMessage("dialog-button-cancel"), null, 50);
        ActionButton cancelBtn =
                new ActionButton(
                        btnDataCancel,
                        new DynamicCustomAction(new ResourceLocation("kite", "cancel"), null));

        // Setup Common Data
        CommonDialogData commonData =
                new CommonDialogData(
                        title,
                        null,
                        false, // canCloseWithEscape = false
                        false, // pause = false
                        action,
                        Collections.singletonList(body),
                        inputs);

        // Setup Dialog
        MultiActionDialog dialog =
                new MultiActionDialog(commonData, Arrays.asList(submitBtn, cancelBtn), null, 2);

        // Kirim Dialog
        WrapperPlayServerShowDialog packet = new WrapperPlayServerShowDialog(dialog);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.CUSTOM_CLICK_ACTION) {
            try {
                WrapperPlayClientCustomClickAction wrapper =
                        new WrapperPlayClientCustomClickAction(event);
                String actionId = wrapper.getId().toString();
                Object payload = wrapper.getPayload();

                UUID uuid = event.getUser().getUUID();
                var player = plugin.getPlayerForUUID(uuid);
                if (player == null) return;

                if (actionId.equals("kite:cancel")) {
                    plugin.getPlatformHandle()
                            .kick(player, Component.text("Disconnected by user."));
                    return;
                }

                if (actionId.equals("kite:submit") && payload instanceof NBTCompound nbt) {
                    String password = nbt.getStringTagValueOrNull("password");
                    String confirmPassword = nbt.getStringTagValueOrNull("confirm_password");

                    var user = plugin.getDatabaseProvider().getByUUID(uuid);
                    if (user == null) return;

                    var audience = plugin.getPlatformHandle().getAudienceForPlayer(player);

                    if (user.isRegistered()) {
                        handleLogin(player, user, password, audience);
                    } else {
                        handleRegister(player, user, password, confirmPassword, audience);
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private void handleLogin(
            P player,
            xyz.kyngs.librelogin.api.database.User user,
            String password,
            net.kyori.adventure.audience.Audience audience) {
        try {
            if (password == null || password.isEmpty()) {
                sendInputDialog(player, true, DialogAction.CLOSE);
                return;
            }

            if (!getMessages().isEmpty("info-logging-in"))
                audience.sendMessage(getMessages().getMessage("info-logging-in"));

            var hashed = user.getHashedPassword();
            var crypto = plugin.getCryptoProvider(hashed.algo());

            if (crypto == null)
                throw new xyz.kyngs.librelogin.common.command.InvalidCommandArgument(
                        getMessages().getMessage("error-password-corrupted"));

            if (!crypto.matches(password, hashed)) {
                plugin.getEventProvider()
                        .unsafeFire(
                                plugin.getEventTypes().wrongPassword,
                                new AuthenticWrongPasswordEvent<P, S>(
                                        user,
                                        player,
                                        plugin,
                                        WrongPasswordEvent.AuthenticationSource.LOGIN));
                // LoginTryListener will handle kick if needed
                if (plugin.isPresent(plugin.getPlatformHandle().getUUIDForPlayer(player))) {
                    throw new xyz.kyngs.librelogin.common.command.InvalidCommandArgument(
                            getMessages().getMessage("error-password-wrong"));
                }
                return;
            }

            if (!getMessages().isEmpty("info-logged-in"))
                audience.sendMessage(getMessages().getMessage("info-logged-in"));

            plugin.getAuthorizationProvider()
                    .authorize(
                            user,
                            player,
                            xyz.kyngs.librelogin.api.event.events.AuthenticatedEvent
                                    .AuthenticationReason.LOGIN);
        } catch (xyz.kyngs.librelogin.common.command.InvalidCommandArgument e) {
            audience.sendMessage(e.getUserFuckUp());
            sendInputDialog(player, true, DialogAction.CLOSE);
        }
    }

    private void handleRegister(
            P player,
            xyz.kyngs.librelogin.api.database.User user,
            String password,
            String confirmPassword,
            net.kyori.adventure.audience.Audience audience) {
        try {
            if (password == null
                    || password.isEmpty()
                    || confirmPassword == null
                    || confirmPassword.isEmpty()) {
                sendInputDialog(player, false, DialogAction.CLOSE);
                return;
            }

            if (!password.contentEquals(confirmPassword)) {
                plugin.getEventProvider()
                        .unsafeFire(
                                plugin.getEventTypes().wrongPassword,
                                new AuthenticWrongPasswordEvent<P, S>(
                                        user,
                                        player,
                                        plugin,
                                        WrongPasswordEvent.AuthenticationSource.LOGIN));
                if (plugin.isPresent(plugin.getPlatformHandle().getUUIDForPlayer(player))) {
                    throw new xyz.kyngs.librelogin.common.command.InvalidCommandArgument(
                            getMessages().getMessage("error-password-not-match"));
                }
                return;
            }

            setPassword(audience, user, password, "info-registering");

            if (!getMessages().isEmpty("info-registered"))
                audience.sendMessage(getMessages().getMessage("info-registered"));

            plugin.getAuthorizationProvider()
                    .authorize(
                            user,
                            player,
                            xyz.kyngs.librelogin.api.event.events.AuthenticatedEvent
                                    .AuthenticationReason.REGISTER);
        } catch (xyz.kyngs.librelogin.common.command.InvalidCommandArgument e) {
            audience.sendMessage(e.getUserFuckUp());
            sendInputDialog(player, false, DialogAction.CLOSE);
        }
    }

    private void setPassword(
            net.kyori.adventure.audience.Audience sender,
            xyz.kyngs.librelogin.api.database.User user,
            String password,
            String messageKey) {
        if (!plugin.validPassword(password))
            throw new xyz.kyngs.librelogin.common.command.InvalidCommandArgument(
                    getMessages().getMessage("error-forbidden-password"));

        if (!getMessages().isEmpty(messageKey))
            sender.sendMessage(getMessages().getMessage(messageKey));

        var defaultProvider = plugin.getDefaultCryptoProvider();
        var hash = defaultProvider.createHash(password);

        if (hash == null) {
            throw new xyz.kyngs.librelogin.common.command.InvalidCommandArgument(
                    getMessages().getMessage("error-password-too-long"));
        }

        user.setHashedPassword(hash);
        plugin.getDatabaseProvider().updateUser(user);
    }

    private xyz.kyngs.librelogin.api.configuration.Messages getMessages() {
        return plugin.getMessages();
    }
}
