package com.shiroha.mmdskin.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * YSM (Yes Steve Model) 兼容性辅助类 (Forge)
 * 通过反射访问 YSM 内部 API，用于检测玩家是否正在使用 YSM 模型
 */
public class YsmCompat {
    private static boolean ysmChecked = false;
    private static boolean ysmPresent = false;

    private static Method isModelActiveMethod = null;
    private static Method getCapabilityMethod = null;
    private static Object playerAnimatableCap = null;

    // YSM 配置项字段
    private static Object disableSelfModelValue = null;
    private static Object disableOtherModelValue = null;
    private static Object disableSelfHandsValue = null;

    private static Method booleanValueGetMethod = null;

    /**
     * 综合判断：实体是否应显示 YSM 模型（而非原版/MMD）
     */
    public static boolean isYsmActive(LivingEntity entity) {
        if (!isYsmModelActive(entity)) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        boolean isLocalPlayer = mc.player != null && mc.player.getUUID().equals(entity.getUUID());
        if (isLocalPlayer) {
            return !isDisableSelfModel();
        } else {
            return !isDisableOtherModel();
        }
    }

    /**
     * 检查实体是否配置了 YSM 模型（无论当前是否显示）
     */
    public static boolean isYsmModelActive(LivingEntity entity) {
        if (!ysmChecked) {
            ysmPresent = ModList.get().isLoaded("yes_steve_model");
            if (ysmPresent) {
                try {
                    Class<?> entityWithCapsClass = Class.forName("com.elfmcys.yesstevemodel.ooO00oo00o0o0OoO0oO00oO");
                    getCapabilityMethod = entityWithCapsClass.getMethod("oOO0000ooo0oOOo0OO0oo0Oo", Object.class);
                    Class<?> capsClass = Class.forName("com.elfmcys.yesstevemodel.oO000o0O0O0Oo0o00o000oOo.Oo000Oo0oo0oOOOo00oOoOoo");
                    Field capField = capsClass.getDeclaredField("ooO0000oO0o0o0o000Oooo0O");
                    playerAnimatableCap = capField.get(null);
                    Class<?> dataClass = Class.forName("com.elfmcys.yesstevemodel.oO000oOo0OoOOoooooO0oO00");
                    isModelActiveMethod = dataClass.getMethod("OO0o0OOoOOO00OoOoOo0oOOO");

                    Class<?> ysmConfigClass = Class.forName("com.elfmcys.yesstevemodel.oO0oo0oooOo0O0ooooOOooOo");
                    disableSelfModelValue = ysmConfigClass.getDeclaredField("O0OOoooOOOO0oo0o0OoO0oO0").get(null);
                    disableOtherModelValue = ysmConfigClass.getDeclaredField("oOO0ooO00OOooOO0oOo0oO0O").get(null);
                    disableSelfHandsValue = ysmConfigClass.getDeclaredField("Oo0OoOO000OooOoooOoo0ooO").get(null);

                    if (disableSelfModelValue != null) {
                        booleanValueGetMethod = disableSelfModelValue.getClass().getMethod("get");
                    }
                } catch (Exception e) {
                    System.err.println("[MMDSkin] Failed to initialize YSM Forge compatibility: " + e.getMessage());
                    ysmPresent = false;
                }
            }

            ysmChecked = true;
        }

        if (ysmPresent && getCapabilityMethod != null && playerAnimatableCap != null && isModelActiveMethod != null) {
            try {
                Object optional = getCapabilityMethod.invoke(entity, playerAnimatableCap);
                if (optional != null) {
                    Method isPresentMethod = optional.getClass().getMethod("isPresent");
                    if ((Boolean) isPresentMethod.invoke(optional)) {
                        Method getMethod = optional.getClass().getMethod("get");
                        Object data = getMethod.invoke(optional);
                        if (data != null) {
                            return (Boolean) isModelActiveMethod.invoke(data);
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略调用异常
            }
        }

        return false;
    }

    public static boolean isDisableSelfModel() {
        return getBooleanValue(disableSelfModelValue);
    }

    public static boolean isDisableOtherModel() {
        return getBooleanValue(disableOtherModelValue);
    }

    public static boolean isDisableSelfHands() {
        return getBooleanValue(disableSelfHandsValue);
    }

    private static boolean getBooleanValue(Object valueObj) {
        if (ysmPresent && valueObj != null && booleanValueGetMethod != null) {
            try {
                return (Boolean) booleanValueGetMethod.invoke(valueObj);
            } catch (Exception e) {
                return false;
            }
        } else {
            return false;
        }
    }
}
