package com.shiroha.mmdskin.mixin.forge;

import com.shiroha.mmdskin.forge.YsmCompat;
import com.shiroha.mmdskin.renderer.core.FirstPersonManager;
import com.shiroha.mmdskin.renderer.core.IrisCompat;
import com.shiroha.mmdskin.ui.network.PlayerModelSyncManager;
import net.minecraft.client.Camera;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * LevelRenderer Mixin — 第一人称 MMD 模型渲染
 * 
 * 在第一人称模式下，Minecraft 默认跳过渲染本地玩家实体。
 * 此 Mixin 通过在 renderLevel 方法内将 Camera.isDetached() 重定向为 true，
 * 使实体渲染循环不再跳过本地玩家，从而触发 PlayerRendererMixin 的 MMD 模型渲染。
 * 
 * 支持 YSM 兼容：根据 YSM 激活状态和配置决定是否渲染。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    
    @Redirect(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;isDetached()Z", ordinal = 0)
    )
    private boolean onCameraIsDetached(Camera camera) {
        if (FirstPersonManager.shouldRenderFirstPerson() && !IrisCompat.isRenderingShadows()) {
            Entity entity = camera.getEntity();
            if (entity instanceof AbstractClientPlayer player) {
                String playerName = player.getName().getString();
                String selectedModel = PlayerModelSyncManager.getPlayerModel(player.getUUID(), playerName, true);

                boolean isMmdDefault = selectedModel == null || selectedModel.isEmpty() || selectedModel.equals("默认 (原版渲染)");
                boolean isMmdActive = !isMmdDefault;
                boolean isVanilaMmdModel = isMmdActive && (selectedModel.equals("VanilaModel") || selectedModel.equalsIgnoreCase("vanila"));

                // YSM 兼容
                if (YsmCompat.isYsmModelActive(player)) {
                    if (YsmCompat.isDisableSelfModel()) {
                        if (isMmdActive && !isVanilaMmdModel) {
                            if (camera.getXRot() < 0) {
                                return false;
                            }
                            return true;
                        }
                        return false;
                    } else {
                        return false;
                    }
                }

                // 无 YSM，由 MMD 决定
                if (isMmdActive && !isVanilaMmdModel) {
                    if (camera.getXRot() < 0) {
                        return false;
                    }
                    return true;
                }
            }
        }
        return camera.isDetached();
    }
}
