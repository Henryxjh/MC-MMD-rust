package com.shiroha.mmdskin.stage.client;

import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.config.PathConstants;
import com.shiroha.mmdskin.config.StageConfig;
import com.shiroha.mmdskin.renderer.camera.MMDCameraController;
import com.shiroha.mmdskin.renderer.render.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.renderer.render.PlayerModelResolver;
import com.shiroha.mmdskin.stage.application.StageSessionService;
import com.shiroha.mmdskin.stage.domain.model.StageDescriptor;
import com.shiroha.mmdskin.stage.protocol.StagePacket;
import com.shiroha.mmdskin.ui.network.StageNetworkHandler;
import com.shiroha.mmdskin.ui.stage.StageInvitePopup;
import com.shiroha.mmdskin.ui.stage.StageSelectScreen;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

public final class StagePlaybackCoordinator {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final StagePlaybackCoordinator INSTANCE = new StagePlaybackCoordinator();

    private final StageSessionService sessionService = StageSessionService.getInstance();

    private StagePlaybackCoordinator() {
    }

    public static StagePlaybackCoordinator getInstance() {
        return INSTANCE;
    }

    public void handleInviteRequest(java.util.UUID hostUUID, java.util.UUID sessionId) {
        if (sessionService.onInviteReceived(hostUUID, sessionId)) {
            StageInvitePopup.show(hostUUID);
        }
    }

    public void handleSessionDissolve(java.util.UUID hostUUID, java.util.UUID sessionId) {
        boolean affectsCurrentSession = sessionService.matchesCurrentSession(hostUUID, sessionId);
        sessionService.onSessionDissolved(hostUUID, sessionId);
        if (!affectsCurrentSession) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        MMDCameraController controller = MMDCameraController.getInstance();
        controller.setWaitingForHost(false);

        if (controller.isWatching()) {
            controller.exitWatchMode(false);
        } else if (controller.isInStageMode()) {
            controller.exitStageMode();
        }

        if (mc.screen instanceof StageSelectScreen) {
            mc.setScreen(null);
        }
    }

    public void handlePlaybackStop(java.util.UUID hostUUID, java.util.UUID sessionId) {
        if (!sessionService.matchesCurrentSession(hostUUID, sessionId)) {
            return;
        }
        sessionService.onPlaybackStopped(hostUUID);
        MMDCameraController controller = MMDCameraController.getInstance();
        if (controller.isWatching()) {
            controller.exitWatchMode(false);
        } else if (controller.isInStageMode()) {
            controller.exitStageMode();
        }
    }

    public void handleFrameSync(java.util.UUID hostUUID, java.util.UUID sessionId, Float frame) {
        if (frame == null || !sessionService.matchesCurrentSession(hostUUID, sessionId)) {
            return;
        }
        MMDCameraController controller = MMDCameraController.getInstance();
        controller.onFrameSync(frame);
    }

    public void handlePlaybackStart(java.util.UUID hostUUID, java.util.UUID sessionId, StagePacket packet) {
        if (packet == null || packet.descriptor == null) {
            return;
        }
        if (!sessionService.matchesCurrentSession(hostUUID, sessionId)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        MMDCameraController controller = MMDCameraController.getInstance();
        if (controller.isWaitingForHost() && mc.screen instanceof StageSelectScreen screen) {
            screen.markStartedByHost();
            mc.setScreen(null);
        }
        if (!controller.isInStageMode()) {
            controller.enterStageMode();
        }

        controller.setWaitingForHost(false);

        float hostHeightOffset = packet.heightOffset != null ? packet.heightOffset : 0.0f;
        float startFrame = packet.frame != null ? packet.frame : 0.0f;
        boolean useHostCamera = sessionService.isUseHostCamera();
        float effectiveHeight = useHostCamera ? hostHeightOffset : StageConfig.getInstance().cameraHeightOffset;

        GuestStartResult result = loadAndStartAsGuest(packet, controller, mc, effectiveHeight, useHostCamera, hostUUID);
        if (!result.started()) {
            sessionService.stopWatchingStageOnly();
            controller.setWaitingForHost(true);
            mc.setScreen(new StageSelectScreen());
            return;
        }

        sessionService.onPlaybackStarted(hostUUID, result.descriptor());

        StageDescriptor remoteDescriptor = buildRemoteStageDescriptor(result.descriptor(), result.motionPackName());
        if (remoteDescriptor != null) {
            StageNetworkHandler.sendRemoteStageStart(remoteDescriptor);
        }

        if (startFrame > 0.0f) {
            controller.onFrameSync(startFrame);
            controller.syncAudioPosition(startFrame / 30.0f);
        }
    }

    private GuestStartResult loadAndStartAsGuest(StagePacket packet,
                                                 MMDCameraController controller,
                                                 Minecraft mc,
                                                 float heightOffset,
                                                 boolean useHostCamera,
                                                 java.util.UUID hostUUID) {
        StageDescriptor descriptor = packet.descriptor;
        if (descriptor == null || !descriptor.isValid()) {
            return GuestStartResult.failed();
        }

        File hostStageDir = new File(PathConstants.getStageAnimDir(), descriptor.getPackName());
        if (!hostStageDir.exists() || !hostStageDir.isDirectory()) {
            LOGGER.warn("[多人舞台] 本地缺少舞台包: {}", descriptor.getPackName());
            return GuestStartResult.failed();
        }

        StageDescriptor effectiveDescriptor = descriptor.copy();
        String motionPackName = sanitizePackName(packet.motionPackName);
        File motionStageDir = resolveMotionStageDir(hostStageDir, motionPackName);
        if (motionStageDir == null) {
            return GuestStartResult.failed();
        }

        NativeFunc nf = NativeFunc.GetInst();
        long mergedAnim = 0;
        long cameraAnim = 0;

        try {
            for (String motionFile : effectiveDescriptor.getMotionFiles()) {
                String filePath = new File(motionStageDir, motionFile).getAbsolutePath();
                long tempAnim = nf.LoadAnimation(0, filePath);
                if (tempAnim == 0) {
                    continue;
                }

                boolean hasMotion = nf.HasBoneData(tempAnim) || nf.HasMorphData(tempAnim);

                if (hasMotion) {
                    if (mergedAnim == 0) {
                        mergedAnim = tempAnim;
                    } else {
                        nf.MergeAnimation(mergedAnim, tempAnim);
                        nf.DeleteAnimation(tempAnim);
                    }
                } else {
                    nf.DeleteAnimation(tempAnim);
                }
            }

            if (effectiveDescriptor.getCameraFile() != null && !effectiveDescriptor.getCameraFile().isEmpty()) {
                cameraAnim = nf.LoadAnimation(0, new File(hostStageDir, effectiveDescriptor.getCameraFile()).getAbsolutePath());
            }

            if (mergedAnim == 0 && cameraAnim == 0) {
                LOGGER.warn("[多人舞台] 没有可用的动作或相机数据");
                return GuestStartResult.failed();
            }

            long modelHandle = 0;
            String modelName = null;
            if (mc.player != null && mergedAnim != 0) {
                modelName = com.shiroha.mmdskin.ui.config.ModelSelectorConfig.getInstance().getSelectedModel();
                if (modelName != null && !modelName.isEmpty()) {
                    com.shiroha.mmdskin.renderer.model.MMDModelManager.Model modelData =
                            com.shiroha.mmdskin.renderer.model.MMDModelManager.GetModel(
                                    modelName,
                                    PlayerModelResolver.getCacheKey(mc.player)
                            );
                    if (modelData != null) {
                        modelHandle = modelData.model.getModelHandle();
                        MmdSkinRendererPlayerHelper.startStageAnimation(modelData, mergedAnim);
                    }
                }
            }

            String audioPath = effectiveDescriptor.resolveAudioPath(hostStageDir);
            if (audioPath == null) {
                audioPath = findFirstAudio(hostStageDir);
            }
            if (useHostCamera) {
                controller.enterWatchMode(hostUUID);
                if (cameraAnim != 0) {
                    controller.setWatchCamera(cameraAnim, heightOffset);
                }
                controller.setWatchMotion(mergedAnim, modelHandle, modelName);
                if (audioPath != null && !audioPath.isEmpty()) {
                    controller.loadWatchAudio(audioPath);
                }
                return GuestStartResult.success(effectiveDescriptor, motionPackName);
            }

            boolean started = controller.startStage(
                    mergedAnim != 0 ? mergedAnim : cameraAnim,
                    cameraAnim,
                    StageConfig.getInstance().cinematicMode,
                    modelHandle,
                    modelName,
                    audioPath,
                    heightOffset
            );
            if (!started) {
                if (mergedAnim != 0) {
                    nf.DeleteAnimation(mergedAnim);
                }
                if (cameraAnim != 0 && cameraAnim != mergedAnim) {
                    nf.DeleteAnimation(cameraAnim);
                }
                LOGGER.warn("[多人舞台] 启动播放失败");
            }
            return started ? GuestStartResult.success(effectiveDescriptor, motionPackName) : GuestStartResult.failed();
        } catch (Exception e) {
            LOGGER.error("[多人舞台] 启动来宾播放失败", e);
            if (mergedAnim != 0) {
                nf.DeleteAnimation(mergedAnim);
            }
            if (cameraAnim != 0 && cameraAnim != mergedAnim) {
                nf.DeleteAnimation(cameraAnim);
            }
            return GuestStartResult.failed();
        }
    }

    private String findFirstAudio(File stageDir) {
        File[] audioFiles = stageDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".wav");
        });
        if (audioFiles != null && audioFiles.length > 0) {
            return audioFiles[0].getAbsolutePath();
        }
        return null;
    }

    private File resolveMotionStageDir(File hostStageDir, String motionPackName) {
        if (motionPackName == null || motionPackName.equals(hostStageDir.getName())) {
            return hostStageDir;
        }
        File motionStageDir = new File(PathConstants.getStageAnimDir(), motionPackName);
        if (!motionStageDir.exists() || !motionStageDir.isDirectory()) {
            LOGGER.warn("[多人舞台] 本地缺少自选动作包: {}", motionPackName);
            return null;
        }
        return motionStageDir;
    }

    private StageDescriptor buildRemoteStageDescriptor(StageDescriptor descriptor, String motionPackName) {
        if (descriptor == null || descriptor.getMotionFiles().isEmpty()) {
            return null;
        }
        StageDescriptor remoteDescriptor = descriptor.copy();
        if (motionPackName != null && !motionPackName.isEmpty()) {
            remoteDescriptor.setPackName(motionPackName);
        }
        remoteDescriptor.setCameraFile(null);
        remoteDescriptor.setAudioFile(null);
        return remoteDescriptor.isValid() ? remoteDescriptor : null;
    }

    private String sanitizePackName(String packName) {
        return packName != null && !packName.isEmpty()
                && !packName.contains("..")
                && !packName.contains("/")
                && !packName.contains("\\")
                ? packName
                : null;
    }

    private record GuestStartResult(boolean started, StageDescriptor descriptor, String motionPackName) {
        private static GuestStartResult success(StageDescriptor descriptor, String motionPackName) {
            return new GuestStartResult(true, descriptor, motionPackName);
        }

        private static GuestStartResult failed() {
            return new GuestStartResult(false, null, null);
        }
    }
}
