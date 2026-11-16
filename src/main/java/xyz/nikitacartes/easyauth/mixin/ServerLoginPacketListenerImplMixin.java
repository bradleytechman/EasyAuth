package xyz.nikitacartes.easyauth.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.core.UUIDUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.nikitacartes.easyauth.EasyAuth;
import xyz.nikitacartes.easyauth.storage.PlayerEntryV1;
import xyz.nikitacartes.easyauth.utils.PlayersCache;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static xyz.nikitacartes.easyauth.integrations.MojangApi.getUuid;
import static xyz.nikitacartes.easyauth.utils.EasyLogger.*;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    @Shadow
    public GameProfile authenticatedProfile;

    @Shadow
    private ServerLoginPacketListenerImpl.State state;

    @Shadow
    public abstract void disconnect(Component reason);

    @Final
    @Shadow
    MinecraftServer server;

    @Unique
    private static final Pattern pattern = Pattern.compile("^[a-zA-Z0-9_]{1,16}$");

    /**
     * Checks whether the player has purchased an account.
     * If so, server is presented as online, and continues as in normal-online mode.
     * Otherwise, player is marked as ready to be accepted into the game.
     *
     * @param packet
     * @param ci
     */
    @Inject(
            method = "handleHello(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"
            ),
            cancellable = true
    )
    private void checkPremium(ServerboundHelloPacket packet, CallbackInfo ci) {
        String username = packet.name();

        LogDebug("UUID of player " + username + " is " + packet.profileId());

        PlayerEntryV1 playerData = PlayersCache.loadOrRegister(username);

        if (server.usesAuthentication()) {
            try {
                Matcher matcher = pattern.matcher(username);

                if (playerData.onlineAccount == PlayerEntryV1.OnlineAccount.TRUE) {
                    LogDebug("Player " + username + " is cached as online player. Authentication continues as vanilla");
                    return;
                }
                if (!matcher.matches()) {
                    LogDebug("Player " + username + " doesn't have a valid username for Mojang account");
                    acceptOfflineOrReject(username, playerData, ci);
                    return;
                }

                UUID onlineUuid = getUuid(username);
                UUID presented = packet.profileId();
                if (onlineUuid != null && presented != null && presented.equals(onlineUuid)) {
                    playerData.onlineAccount = PlayerEntryV1.OnlineAccount.TRUE;
                    playerData.update();
                    return;
                }

                if (onlineUuid != null && EasyAuth.extendedConfig.preventOfflinePlayersWithOnlineUsernames) {
                    // Vanilla session verification rejects a client that does not own this account.
                    return;
                }

                if (onlineUuid == null) {
                    LogDebug("Player " + username + " doesn't have a Mojang account");
                } else {
                    LogInfo("Player " + username + " has a Mojang account, but UUID mismatch: expected " + onlineUuid + ", got " + presented);
                }
                acceptOfflineOrReject(username, playerData, ci);
            } catch (IOException e) {
                LogError("checkPremium error", e);
            }
        }
    }

    /**
     * Floodgate handles Bedrock logins before this method runs, so a rejected
     * client here is a Java client that Mojang did not authenticate.
     */
    @Unique
    private void acceptOfflineOrReject(String username, PlayerEntryV1 playerData, CallbackInfo ci) {
        if (!EasyAuth.config.allowCrackedClients) {
            LogInfo("Rejected non-premium client " + username);
            disconnect(Component.literal("This server requires a Minecraft Java account."));
            ci.cancel();
            return;
        }

        state = getReadyState();
        playerData.onlineAccount = PlayerEntryV1.OnlineAccount.FALSE;
        playerData.update();
        this.authenticatedProfile = getGameProfile(username);
        ci.cancel();
    }

    @Unique
    private GameProfile getGameProfile(String name) {
        // Check if player has a forced UUID set
        PlayerEntryV1 playerData = PlayersCache.get(name);
        if (playerData != null && playerData.forcedUuid != null && !playerData.forcedUuid.isEmpty()) {
            try {
                UUID forcedUuid = UUID.fromString(playerData.forcedUuid);
                LogInfo("Using forced UUID " + forcedUuid + " for player " + name);
                return new GameProfile(forcedUuid, name);
            } catch (IllegalArgumentException e) {
                LogError("Invalid forced UUID for player " + name + ": " + playerData.forcedUuid, e);
            }
        }
        return new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
    }

    @Unique
    private ServerLoginPacketListenerImpl.State getReadyState() {
        return ServerLoginPacketListenerImpl.State.VERIFYING;
    }

}