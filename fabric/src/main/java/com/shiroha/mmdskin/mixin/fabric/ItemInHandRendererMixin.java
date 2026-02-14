package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.fabric.YsmCompat;
import com.shiroha.mmdskin.ui.network.PlayerModelSyncManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ItemInHandRenderer Mixin — 第一人称手臂隐藏
 * 
 * 在第一人称 MMD 模型模式下，跳过原版手臂和手持物品的渲染。
 * 支持 YSM 兼容：若 YSM 接管，按其配置决定手臂渲染。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void onRenderHandsWithItems(float partialTick, PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource, LocalPlayer player, int packedLight,
            CallbackInfo ci) {
        String playerName = player.getName().getString();
        String selectedModel = PlayerModelSyncManager.getPlayerModel(player.getUUID(), playerName, true);

        boolean isMmdDefault = selectedModel == null || selectedModel.isEmpty() || selectedModel.equals("默认 (原版渲染)");
        boolean isMmdActive = !isMmdDefault;
        boolean isVanilaMmdModel = isMmdActive && (selectedModel.equals("VanilaModel") || selectedModel.equalsIgnoreCase("vanila"));

        // YSM 接管时，按 YSM 配置决定手臂渲染
        if (YsmCompat.isYsmModelActive(player)) {
            if (YsmCompat.isDisableSelfHands()) {
                ci.cancel();
            }
            return;
        }

        // 无 YSM 时，非原版 MMD 模型下取消手臂渲染
        if (isMmdActive && !isVanilaMmdModel) {
            ci.cancel();
        }
    }
}
