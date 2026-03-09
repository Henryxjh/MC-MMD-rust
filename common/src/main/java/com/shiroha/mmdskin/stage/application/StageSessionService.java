package com.shiroha.mmdskin.stage.application;

import com.shiroha.mmdskin.stage.client.StageClientContext;
import com.shiroha.mmdskin.stage.client.playback.StageLocalPlaybackPreferences;
import com.shiroha.mmdskin.stage.domain.model.StageCameraMode;
import com.shiroha.mmdskin.stage.domain.model.StageDescriptor;
import com.shiroha.mmdskin.stage.domain.model.StageInviteDecision;
import com.shiroha.mmdskin.stage.domain.model.StageMember;
import com.shiroha.mmdskin.stage.domain.model.StageMemberState;
import com.shiroha.mmdskin.stage.domain.model.StageRole;
import com.shiroha.mmdskin.stage.protocol.StageMemberSnapshot;
import com.shiroha.mmdskin.ui.network.StageNetworkHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class StageSessionService {
    private static final StageSessionService INSTANCE = new StageSessionService();

    private final Map<UUID, SessionMember> members = new LinkedHashMap<>();
    private final StageLocalPlaybackPreferences localPlaybackPreferences = StageLocalPlaybackPreferences.getInstance();

    private StageRole localRole = StageRole.NONE;
    private UUID sessionId;
    private UUID hostPlayerId;
    private PendingInvite pendingInvite;

    private boolean localReady;
    private StageCameraMode localCameraMode = StageCameraMode.HOST_CAMERA;

    private boolean watchingStage;
    private StageDescriptor watchingDescriptor;

    private StageSessionService() {
    }

    public static StageSessionService getInstance() {
        return INSTANCE;
    }

    public synchronized StageRole getLocalRole() {
        return localRole;
    }

    public synchronized boolean isSessionHost() {
        return localRole == StageRole.HOST;
    }

    public synchronized boolean isSessionMember() {
        return localRole == StageRole.MEMBER;
    }

    public synchronized UUID getSessionId() {
        return sessionId;
    }

    public synchronized UUID getHostPlayerId() {
        return hostPlayerId;
    }

    public synchronized boolean hasPendingInvite() {
        return pendingInvite != null;
    }

    public synchronized boolean isUseHostCamera() {
        return localCameraMode.usesHostCamera();
    }

    public synchronized boolean isLocalReady() {
        return localReady;
    }

    public synchronized boolean isWatchingStage() {
        return watchingStage;
    }

    public synchronized StageDescriptor getWatchingDescriptor() {
        return watchingDescriptor != null ? watchingDescriptor.copy() : null;
    }

    public synchronized boolean matchesCurrentSession(UUID hostUUID, UUID incomingSessionId) {
        if (hostUUID == null || hostPlayerId == null || !hostPlayerId.equals(hostUUID)) {
            return false;
        }
        return Objects.equals(sessionId, incomingSessionId);
    }

    public synchronized List<StageMember> getMembers() {
        return orderedMembers().stream()
                .map(member -> new StageMember(member.uuid, member.name, member.state, member.cameraMode, member.local))
                .collect(Collectors.toList());
    }

    public synchronized StageMemberState getMemberState(UUID uuid) {
        SessionMember member = members.get(uuid);
        return member != null ? member.state : null;
    }

    public synchronized void setUseHostCamera(boolean useHostCamera) {
        this.localCameraMode = StageCameraMode.fromUseHostCamera(useHostCamera);
        updateLocalMemberState(localReady ? StageMemberState.READY : StageMemberState.ACCEPTED);
        syncLocalPlaybackPreferences();
    }

    public synchronized void setLocalReady(boolean ready) {
        this.localReady = ready;
        updateLocalMemberState(ready ? StageMemberState.READY : StageMemberState.ACCEPTED);
        syncLocalPlaybackPreferences();
    }

    public synchronized void syncLocalPlaybackPreferences() {
        if (localRole != StageRole.MEMBER || sessionId == null || hostPlayerId == null) {
            return;
        }
        StageNetworkHandler.sendReady(
                hostPlayerId,
                sessionId,
                localReady,
                localCameraMode.usesHostCamera(),
                buildLocalMotionPackName(),
                buildLocalMotionFiles()
        );
    }

    public synchronized void sendInvite(UUID targetUUID) {
        UUID selfUUID = StageClientContext.getLocalPlayerUUID();
        if (selfUUID == null || targetUUID == null || selfUUID.equals(targetUUID) || localRole == StageRole.MEMBER) {
            return;
        }
        ensureHostedSession(selfUUID);

        SessionMember existing = members.get(targetUUID);
        if (existing != null && (existing.state == StageMemberState.INVITED
                || existing.state == StageMemberState.ACCEPTED
                || existing.state == StageMemberState.READY)) {
            return;
        }

        members.put(targetUUID, new SessionMember(
                targetUUID,
                StageClientContext.resolvePlayerName(targetUUID),
                StageMemberState.INVITED,
                StageCameraMode.HOST_CAMERA,
                false
        ));
        StageNetworkHandler.sendStageInvite(targetUUID, sessionId);
    }

    public synchronized void cancelInvite(UUID targetUUID) {
        if (localRole != StageRole.HOST || targetUUID == null || sessionId == null) {
            return;
        }
        SessionMember member = members.get(targetUUID);
        if (member == null || member.state != StageMemberState.INVITED) {
            return;
        }
        members.remove(targetUUID);
        StageNetworkHandler.sendInviteCancel(targetUUID, sessionId);
    }

    public synchronized void acceptInvite() {
        PendingInvite invite = pendingInvite;
        if (invite == null) {
            return;
        }
        pendingInvite = null;
        joinSessionAsMember(invite.hostUUID, invite.sessionId, invite.hostName);
        StageNetworkHandler.sendInviteResponse(invite.hostUUID, invite.sessionId, StageInviteDecision.ACCEPT);
    }

    public synchronized void declineInvite() {
        PendingInvite invite = pendingInvite;
        if (invite == null) {
            return;
        }
        pendingInvite = null;
        StageNetworkHandler.sendInviteResponse(invite.hostUUID, invite.sessionId, StageInviteDecision.DECLINE);
    }

    public synchronized boolean onInviteReceived(UUID hostUUID, UUID incomingSessionId) {
        UUID selfUUID = StageClientContext.getLocalPlayerUUID();
        if (selfUUID == null || hostUUID == null || selfUUID.equals(hostUUID)) {
            return false;
        }
        if (pendingInvite != null && pendingInvite.hostUUID.equals(hostUUID)
                && Objects.equals(pendingInvite.sessionId, incomingSessionId)) {
            return false;
        }
        if (localRole != StageRole.NONE) {
            StageNetworkHandler.sendInviteResponse(hostUUID, incomingSessionId, StageInviteDecision.BUSY);
            return false;
        }
        pendingInvite = new PendingInvite(hostUUID, incomingSessionId, StageClientContext.resolvePlayerName(hostUUID));
        return true;
    }

    public synchronized void onInviteCancelled(UUID hostUUID, UUID incomingSessionId) {
        if (pendingInvite != null && pendingInvite.hostUUID.equals(hostUUID)
                && Objects.equals(pendingInvite.sessionId, incomingSessionId)) {
            pendingInvite = null;
        }
    }

    public synchronized void onInviteReply(UUID memberUUID, UUID incomingSessionId, StageInviteDecision decision) {
        if (localRole != StageRole.HOST || memberUUID == null || decision == null || !Objects.equals(sessionId, incomingSessionId)) {
            return;
        }
        SessionMember member = members.get(memberUUID);
        if (member == null) {
            return;
        }
        member.name = StageClientContext.resolvePlayerName(memberUUID);
        member.state = switch (decision) {
            case ACCEPT -> StageMemberState.ACCEPTED;
            case DECLINE -> StageMemberState.DECLINED;
            case BUSY -> StageMemberState.BUSY;
        };
    }

    public synchronized void onMemberReady(UUID memberUUID, UUID incomingSessionId,
                                           boolean ready, StageCameraMode cameraMode) {
        if (localRole != StageRole.HOST || memberUUID == null || !Objects.equals(sessionId, incomingSessionId)) {
            return;
        }
        SessionMember member = members.get(memberUUID);
        if (member == null) {
            return;
        }
        member.cameraMode = cameraMode != null ? cameraMode : StageCameraMode.HOST_CAMERA;
        member.state = ready ? StageMemberState.READY : StageMemberState.ACCEPTED;
    }

    public synchronized void onSessionState(UUID hostUUID, UUID incomingSessionId, List<StageMemberSnapshot> snapshots) {
        if (hostUUID == null || incomingSessionId == null) {
            return;
        }
        UUID selfUUID = StageClientContext.getLocalPlayerUUID();
        this.hostPlayerId = hostUUID;
        this.sessionId = incomingSessionId;
        this.pendingInvite = null;
        this.members.clear();

        if (snapshots != null) {
            for (StageMemberSnapshot snapshot : snapshots) {
                UUID memberUUID = parseUUID(snapshot.uuid);
                StageMemberState state = parseState(snapshot.state);
                if (memberUUID == null || state == null) {
                    continue;
                }
                boolean local = selfUUID != null && selfUUID.equals(memberUUID);
                StageCameraMode cameraMode = parseCameraMode(snapshot.cameraMode);
                members.put(memberUUID, new SessionMember(
                        memberUUID,
                        snapshot.name != null && !snapshot.name.isEmpty() ? snapshot.name : StageClientContext.resolvePlayerName(memberUUID),
                        state,
                        cameraMode,
                        local
                ));
                if (local) {
                    localReady = state == StageMemberState.READY;
                    localCameraMode = cameraMode;
                }
            }
        }

        SessionMember localMember = selfUUID != null ? members.get(selfUUID) : null;
        if (localMember == null) {
            if (selfUUID != null && selfUUID.equals(hostUUID)) {
                ensureHostedSession(selfUUID);
            } else {
                clearSession(false);
                return;
            }
        }

        if (selfUUID != null && selfUUID.equals(hostUUID)) {
            localRole = StageRole.HOST;
        } else {
            localRole = StageRole.MEMBER;
        }
    }

    public synchronized void onMemberLeft(UUID memberUUID, UUID incomingSessionId) {
        if (!Objects.equals(sessionId, incomingSessionId) || memberUUID == null) {
            return;
        }
        members.remove(memberUUID);
    }

    public synchronized void onSessionDissolved(UUID hostUUID, UUID incomingSessionId) {
        if (hostUUID == null) {
            return;
        }
        if (pendingInvite != null && pendingInvite.hostUUID.equals(hostUUID)
                && Objects.equals(pendingInvite.sessionId, incomingSessionId)) {
            pendingInvite = null;
        }
        if (matchesCurrentSession(hostUUID, incomingSessionId)) {
            clearSession(true);
        }
    }

    public synchronized Set<UUID> getAcceptedMembers() {
        return members.values().stream()
                .filter(member -> !member.local && member.state.isAcceptedState())
                .map(member -> member.uuid)
                .collect(Collectors.toSet());
    }

    public synchronized boolean allMembersReady() {
        boolean hasMembers = false;
        for (SessionMember member : members.values()) {
            if (member.local) {
                continue;
            }
            if (member.state == StageMemberState.ACCEPTED || member.state == StageMemberState.READY) {
                hasMembers = true;
                if (member.state != StageMemberState.READY) {
                    return false;
                }
            }
        }
        return hasMembers;
    }

    public synchronized void onPlaybackStarted(UUID hostUUID, StageDescriptor descriptor) {
        watchingStage = true;
        hostPlayerId = hostUUID;
        watchingDescriptor = descriptor != null ? descriptor.copy() : null;
    }

    public synchronized void onPlaybackStopped(UUID hostUUID) {
        if (hostPlayerId != null && hostPlayerId.equals(hostUUID)) {
            stopWatchingStageOnly();
        }
    }

    public synchronized void stopWatching() {
        clearSession(true);
    }

    public synchronized void stopWatchingStageOnly() {
        watchingStage = false;
        watchingDescriptor = null;
    }

    public synchronized void notifyMembersStageEnd() {
        if (localRole != StageRole.HOST || sessionId == null) {
            return;
        }
        for (UUID memberUUID : getAcceptedMembers()) {
            StageNetworkHandler.sendStageWatchEnd(memberUUID, sessionId);
        }
    }

    public synchronized void closeHostedSession() {
        if (localRole == StageRole.HOST && sessionId != null) {
            StageNetworkHandler.sendSessionDissolve(sessionId);
        }
        clearSession(true);
    }

    public synchronized void onDisconnect() {
        clearSession(true);
        pendingInvite = null;
    }

    private void ensureHostedSession(UUID selfUUID) {
        if (localRole == StageRole.HOST && sessionId != null && hostPlayerId != null) {
            ensureHostSelfEntry(selfUUID);
            return;
        }
        clearSession(false);
        localRole = StageRole.HOST;
        sessionId = UUID.randomUUID();
        hostPlayerId = selfUUID;
        localReady = false;
        localCameraMode = StageCameraMode.HOST_CAMERA;
        ensureHostSelfEntry(selfUUID);
    }

    private void ensureHostSelfEntry(UUID selfUUID) {
        members.put(selfUUID, new SessionMember(
                selfUUID,
                StageClientContext.resolvePlayerName(selfUUID),
                StageMemberState.HOST,
                StageCameraMode.HOST_CAMERA,
                true
        ));
    }

    private void joinSessionAsMember(UUID hostUUID, UUID incomingSessionId, String hostName) {
        clearSession(false);
        UUID selfUUID = StageClientContext.getLocalPlayerUUID();
        localRole = StageRole.MEMBER;
        sessionId = incomingSessionId;
        hostPlayerId = hostUUID;
        localReady = false;
        localCameraMode = StageCameraMode.HOST_CAMERA;
        stopWatchingStageOnly();

        members.put(hostUUID, new SessionMember(
                hostUUID,
                hostName != null && !hostName.isEmpty() ? hostName : StageClientContext.resolvePlayerName(hostUUID),
                StageMemberState.HOST,
                StageCameraMode.HOST_CAMERA,
                false
        ));

        if (selfUUID != null) {
            members.put(selfUUID, new SessionMember(
                    selfUUID,
                    StageClientContext.resolvePlayerName(selfUUID),
                    StageMemberState.ACCEPTED,
                    localCameraMode,
                    true
            ));
        }
    }

    private void updateLocalMemberState(StageMemberState state) {
        UUID selfUUID = StageClientContext.getLocalPlayerUUID();
        if (selfUUID == null) {
            return;
        }
        SessionMember member = members.get(selfUUID);
        if (member != null && member.state != StageMemberState.HOST) {
            member.state = state;
            member.cameraMode = localCameraMode;
        }
    }

    private List<SessionMember> orderedMembers() {
        return members.values().stream()
                .sorted(Comparator
                        .comparingInt((SessionMember member) -> member.state == StageMemberState.HOST ? 0 : member.local ? 1 : 2)
                        .thenComparing(member -> member.name == null ? "" : member.name, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    private List<String> buildLocalMotionFiles() {
        if (!localPlaybackPreferences.isCustomMotionEnabled()) {
            return Collections.emptyList();
        }
        List<String> motionFiles = localPlaybackPreferences.getSelectedMotionFiles();
        return motionFiles.isEmpty() ? Collections.emptyList() : List.copyOf(motionFiles);
    }

    private String buildLocalMotionPackName() {
        if (!localPlaybackPreferences.isCustomMotionEnabled()) {
            return null;
        }
        return localPlaybackPreferences.getSelectedPackName();
    }

    private void clearSession(boolean resetCameraMode) {
        localPlaybackPreferences.reset();
        members.clear();
        localRole = StageRole.NONE;
        sessionId = null;
        hostPlayerId = null;
        localReady = false;
        stopWatchingStageOnly();
        if (resetCameraMode) {
            localCameraMode = StageCameraMode.HOST_CAMERA;
        }
    }

    private static UUID parseUUID(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static StageMemberState parseState(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return StageMemberState.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static StageCameraMode parseCameraMode(String raw) {
        if (raw == null || raw.isEmpty()) {
            return StageCameraMode.HOST_CAMERA;
        }
        try {
            return StageCameraMode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return StageCameraMode.HOST_CAMERA;
        }
    }

    private static final class SessionMember {
        private final UUID uuid;
        private String name;
        private StageMemberState state;
        private StageCameraMode cameraMode;
        private final boolean local;

        private SessionMember(UUID uuid, String name, StageMemberState state,
                              StageCameraMode cameraMode, boolean local) {
            this.uuid = uuid;
            this.name = name;
            this.state = state;
            this.cameraMode = cameraMode;
            this.local = local;
        }
    }

    private record PendingInvite(UUID hostUUID, UUID sessionId, String hostName) {
    }
}
