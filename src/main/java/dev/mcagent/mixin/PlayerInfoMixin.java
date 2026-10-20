package dev.mcagent.mixin;

import com.mojang.authlib.GameProfile;
import dev.mcagent.client.LocalSkin;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * Single interception point for "where does this player's skin come from".
 *
 * <p>In 1.21.1 every {@link PlayerInfo} builds its skin through the private static
 * {@code createSkinLookup(GameProfile)}, and everything downstream - head model, cape,
 * elytra, slim/wide arm width - reads that one result. Returning our own supplier here
 * therefore replaces the whole skin rather than just painting over it.</p>
 *
 * <p>Client-only: declared under {@code client} in mcagent.mixins.json, because
 * {@code PlayerInfo} and {@code PlayerSkin} do not exist on a dedicated server.</p>
 */
@Mixin(value = PlayerInfo.class, remap = false)
public abstract class PlayerInfoMixin {

    @Inject(method = "createSkinLookup", at = @At("HEAD"), cancellable = true)
    private static void mcagent$useLocalSkin(GameProfile profile,
                                             CallbackInfoReturnable<Supplier<PlayerSkin>> cir) {
        PlayerSkin skin = LocalSkin.forProfile(profile);
        if (skin != null) {
            cir.setReturnValue(() -> skin);
        }
    }
}
