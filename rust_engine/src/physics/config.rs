//! MMD 物理配置
//!
//! 所有参数扁平化，直接在代码中修改默认值即可。

use once_cell::sync::Lazy;
use std::sync::RwLock;

/// 物理配置（扁平化，不嵌套）
#[derive(Debug, Clone)]
pub struct PhysicsConfig {
    // ========== 重力 ==========
    /// 重力 Y 分量（负数向下），默认 -98.0（MMD 标准）
    pub gravity_y: f32,

    // ========== 模拟参数 ==========
    /// 物理 FPS，默认 120.0
    pub physics_fps: f32,
    /// 每帧最大子步数，默认 10
    pub max_substep_count: i32,
    /// 求解器迭代次数，默认 8
    pub solver_iterations: usize,
    /// 内部 PGS 迭代次数，默认 2
    pub pgs_iterations: usize,
    /// 最大修正速度，默认 5.0
    pub max_corrective_velocity: f32,

    // ========== 刚体阻尼（让头发更柔顺的关键！）==========
    /// 线性阻尼缩放（乘以 PMX 原值），默认 1.0
    /// 增大此值让头发移动更慢、更柔顺
    pub linear_damping_scale: f32,
    /// 角速度阻尼缩放（乘以 PMX 原值），默认 1.0
    /// 增大此值让头发旋转更慢、更柔顺
    pub angular_damping_scale: f32,

    // ========== 质量 ==========
    /// 质量缩放（乘以 PMX 原值），默认 1.0
    /// 增大质量让头发更"重"，惯性更大
    pub mass_scale: f32,

    // ========== 6DOF 弹簧（移植自 Bullet3 btGeneric6DofSpringConstraint）==========
    /// 弹簧刚度缩放因子（乘以 PMX 原值），默认 1.0
    /// 与 Bullet3 原版一致时应为 1.0。
    /// 游戏中可适当调整，因为重力和尺寸与 MMD 原版不同。
    pub spring_stiffness_scale: f32,

    // ========== 惯性效果 ==========
    /// 惯性效果强度，默认 1.0
    /// 控制人物移动时头发被拖拽的程度
    /// 0.0 = 无惯性，1.0 = 正常，2.0 = 双倍效果
    pub inertia_strength: f32,

    // ========== 速度限制 ==========
    /// 最大线速度 (m/s)，默认 50.0
    pub max_linear_velocity: f32,
    /// 最大角速度 (rad/s)，默认 20.0
    pub max_angular_velocity: f32,

    // ========== Bullet3 Additional Damping ==========
    /// 是否启用 Bullet3 的 additional damping（消除低速微振荡）
    pub additional_damping: bool,
    /// additional damping 系数，默认 0.005
    pub additional_damping_factor: f32,
    /// additional damping 速度阈值平方，默认 0.01
    pub additional_damping_threshold_sqr: f32,

    // ========== 调试 ==========
    /// 是否启用关节，默认 true
    pub joints_enabled: bool,
    /// 是否输出调试日志，默认 false
    pub debug_log: bool,
}

impl Default for PhysicsConfig {
    fn default() -> Self {
        Self {
            // ====== 重力 ======
            // MMD 标准重力（与 babylon-mmd 一致）
            // PMX 模型的弹簧/质量/阻尼参数都是为此重力设计的
            gravity_y: -98.0,

            // ====== 模拟参数 ======
            // 物理模拟的帧率（每秒计算多少次物理）
            // 越高 → 模拟越精确、越稳定，但 CPU 消耗越大
            // 建议范围: 30~120，60 是平衡点
            physics_fps: 60.0,
            
            // 每帧最多分成几个子步骤（与 babylon-mmd 默认 5 一致）
            max_substep_count: 5,
            
            // 约束求解器迭代次数
            // 越大 → 关节约束越精确（头发不会穿模），但更慢
            // 建议范围: 4~16
            solver_iterations: 8,
            
            // 内部 PGS（投影高斯-赛德尔）迭代次数
            // 配合 solver_iterations 使用，一般不用改
            pgs_iterations: 2,
            
            // 穿透修正速度（适配 -98 重力下的速度范围）
            max_corrective_velocity: 5.0,

            // ====== 刚体阻尼 ======
            // 直接使用 PMX 原值（与 babylon-mmd/Bullet3 一致）
            // Bullet3 指数衰减阻尼由我们自己实现，不依赖 Rapier
            linear_damping_scale: 1.0,
            angular_damping_scale: 1.0,

            // ====== 质量 ======
            // 直接使用 PMX 原值（与 babylon-mmd 一致）
            mass_scale: 1.0,

            // ====== 6DOF 弹簧（移植自 Bullet3）======
            // 弹簧刚度缩放因子，乘以 PMX 模型中的原始弹簧刚度
            // 1.0 = 与 Bullet3/MMD 原版一致
            // 游戏中因为重力和尺寸与 MMD 不同，可能需要调整
            spring_stiffness_scale: 1.0,

            // ====== 惯性效果 ======
            // 惯性效果强度
            // 0.0 = 无惯性，1.0 = 正常，>1.0 = 更强的拖拽感
            inertia_strength: 1.0,

            // ====== 速度限制（适配 -98 重力）======
            max_linear_velocity: 100.0,
            max_angular_velocity: 50.0,

            // ====== Bullet3 Additional Damping ======
            // 消除低速微振荡，与 babylon-mmd 的 setAdditionalDamping(true) 一致
            additional_damping: true,
            additional_damping_factor: 0.005,
            additional_damping_threshold_sqr: 0.01,

            // ====== 调试 ======
            joints_enabled: true,
            debug_log: false,
        }
    }
}

/// 全局配置实例
static PHYSICS_CONFIG: Lazy<RwLock<PhysicsConfig>> = Lazy::new(|| {
    RwLock::new(PhysicsConfig::default())
});

/// 获取当前配置（只读）
pub fn get_config() -> PhysicsConfig {
    PHYSICS_CONFIG.read().unwrap_or_else(|e| e.into_inner()).clone()
}

/// 手动设置配置（用于运行时调试）
pub fn set_config(config: PhysicsConfig) {
    *PHYSICS_CONFIG.write().unwrap_or_else(|e| e.into_inner()) = config;
}

/// 重置为默认配置
pub fn reset_config() {
    *PHYSICS_CONFIG.write().unwrap_or_else(|e| e.into_inner()) = PhysicsConfig::default();
}
