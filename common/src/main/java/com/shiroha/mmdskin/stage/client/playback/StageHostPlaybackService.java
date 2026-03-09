package com.shiroha.mmdskin.stage.client.playback;

import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.config.StageConfig;
import com.shiroha.mmdskin.config.StagePack;
import com.shiroha.mmdskin.renderer.camera.MMDCameraController;
import com.shiroha.mmdskin.renderer.model.MMDModelManager;
import com.shiroha.mmdskin.renderer.render.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.renderer.render.PlayerModelResolver;
import com.shiroha.mmdskin.stage.client.viewmodel.StageLobbyViewModel;
import com.shiroha.mmdskin.stage.domain.model.StageDescriptor;
import com.shiroha.mmdskin.ui.config.ModelSelectorConfig;
import com.shiroha.mmdskin.ui.network.StageNetworkHandler;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class StageHostPlaybackService {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final StageHostPlaybackService INSTANCE = new StageHostPlaybackService();

    private final StageLobbyViewModel lobbyViewModel = StageLobbyViewModel.getInstance();

    private StageHostPlaybackService() {
    }

    public static StageHostPlaybackService getInstance() {
        return INSTANCE;
    }

    public boolean startPack(StagePack pack, boolean cinematicMode, float cameraHeightOffset,
                             String selectedMotionFileName) {
        if (pack == null || !pack.hasMotionVmd()) {
            return false;
        }

        NativeFunc nativeFunc = NativeFunc.GetInst();
        Minecraft mc = Minecraft.getInstance();

        StageConfig config = StageConfig.getInstance();
        config.lastStagePack = pack.getName();
        config.cinematicMode = cinematicMode;
        config.cameraHeightOffset = cameraHeightOffset;
        config.save();

        StagePack.VmdFileInfo cameraFile = null;
        List<StagePack.VmdFileInfo> motionFiles = new ArrayList<>();
        for (StagePack.VmdFileInfo info : pack.getVmdFiles()) {
            if (info.hasCamera && cameraFile == null) {
                cameraFile = info;
            }
            if (info.hasBones || info.hasMorphs) {
                motionFiles.add(info);
            }
        }

        if (selectedMotionFileName != null && !selectedMotionFileName.isEmpty()) {
            motionFiles = motionFiles.stream()
                    .filter(info -> selectedMotionFileName.equals(info.name))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        if (motionFiles.isEmpty()) {
            LOGGER.warn("[舞台模式] 没有可用的动作 VMD");
            return false;
        }

        long mergedAnim = nativeFunc.LoadAnimation(0, motionFiles.get(0).path);
        if (mergedAnim == 0) {
            LOGGER.error("[舞台模式] 动作 VMD 加载失败: {}", motionFiles.get(0).path);
            return false;
        }

        List<Long> tempHandles = new ArrayList<>();
        for (int i = 1; i < motionFiles.size(); i++) {
            long tempAnim = nativeFunc.LoadAnimation(0, motionFiles.get(i).path);
            if (tempAnim != 0) {
                nativeFunc.MergeAnimation(mergedAnim, tempAnim);
                tempHandles.add(tempAnim);
            }
        }
        for (long handle : tempHandles) {
            nativeFunc.DeleteAnimation(handle);
        }

        long cameraAnim = 0;
        if (cameraFile != null) {
            cameraAnim = nativeFunc.LoadAnimation(0, cameraFile.path);
        }

        List<String> motionNames = new ArrayList<>();
        for (StagePack.VmdFileInfo motionFile : motionFiles) {
            motionNames.add(motionFile.name);
        }

        String audioFileName = null;
        if (pack.hasAudio() && !pack.getAudioFiles().isEmpty()) {
            audioFileName = pack.getAudioFiles().get(0).name;
        }
        StageDescriptor defaultDescriptor = new StageDescriptor(
                pack.getName(),
                motionNames,
                cameraFile != null ? cameraFile.name : null,
                audioFileName
        );
        if (!defaultDescriptor.isValid()) {
            cleanupHandles(nativeFunc, mergedAnim, cameraAnim);
            LOGGER.warn("[舞台模式] 默认舞台描述构建失败");
            return false;
        }

        long modelHandle = 0;
        String modelName = null;
        if (mc.player != null) {
            modelName = ModelSelectorConfig.getInstance().getSelectedModel();
            if (modelName != null && !modelName.isEmpty()) {
                MMDModelManager.Model modelData = MMDModelManager.GetModel(
                        modelName,
                        PlayerModelResolver.getCacheKey(mc.player)
                );
                if (modelData != null) {
                    modelHandle = modelData.model.getModelHandle();
                    MmdSkinRendererPlayerHelper.startStageAnimation(modelData, mergedAnim);
                }
            }
        }

        String audioPath = pack.getFirstAudioPath();
        boolean started = MMDCameraController.getInstance().startStage(
                mergedAnim,
                cameraAnim,
                cinematicMode,
                modelHandle,
                modelName,
                audioPath,
                cameraHeightOffset
        );
        if (!started) {
            cleanupHandles(nativeFunc, mergedAnim, cameraAnim);
            LOGGER.warn("[舞台模式] 相机控制器启动失败，已释放动画句柄");
            return false;
        }

        notifyMembers(defaultDescriptor, cameraHeightOffset);
        StageNetworkHandler.sendRemoteStageStart(buildRemoteStageDescriptor(defaultDescriptor));
        return true;
    }

    private void notifyMembers(StageDescriptor defaultDescriptor, float heightOffset) {
        UUID sessionId = lobbyViewModel.getSessionId();
        for (UUID memberUUID : lobbyViewModel.getAcceptedMembers()) {
            if (defaultDescriptor.isValid()) {
                StageNetworkHandler.sendStageWatch(memberUUID, sessionId, defaultDescriptor, heightOffset, 0.0f);
            }
        }
    }

    private void cleanupHandles(NativeFunc nativeFunc, long mergedAnim, long cameraAnim) {
        if (mergedAnim != 0) {
            nativeFunc.DeleteAnimation(mergedAnim);
        }
        if (cameraAnim != 0 && cameraAnim != mergedAnim) {
            nativeFunc.DeleteAnimation(cameraAnim);
        }
    }

    private StageDescriptor buildRemoteStageDescriptor(StageDescriptor descriptor) {
        StageDescriptor remoteDescriptor = descriptor.copy();
        remoteDescriptor.setCameraFile(null);
        remoteDescriptor.setAudioFile(null);
        return remoteDescriptor;
    }
}
