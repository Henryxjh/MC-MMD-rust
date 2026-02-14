package com.shiroha.mmdskin.renderer.model;

import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.renderer.resource.MMDTextureManager;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.world.entity.Entity;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 使用 Minecraft 原生渲染系统的 MMD 模型渲染器
 * 
 * 关键点：
 * 1. 蒙皮计算在 Rust 引擎完成（高性能）
 * 2. P2-9: Rust 直接输出 MC NEW_ENTITY 顶点格式（含矩阵变换），消除 Java 逐顶点循环
 * 3. 使用自定义 VAO/VBO + MC ShaderInstance，Iris 可正确拦截
 */
public class MMDModelNativeRender extends AbstractMMDModel {
    int vertexCount;
    
    // P2-9: 自定义 VAO/VBO（替代 BufferBuilder 逐顶点循环）
    private int vao;
    private int[] subMeshVBOs;
    private int subMeshCount;
    
    // P2-9: Rust 顶点构建缓冲区（预分配，每帧复用）
    private ByteBuffer mcVertexBuf;    // 输出：MC NEW_ENTITY 交错顶点数据
    private ByteBuffer poseMatBuf;     // 4×4 pose 矩阵（64 字节）
    private ByteBuffer normalMatBuf;   // 3×3 normal 矩阵（36 字节）
    
    // G3 优化：批量子网格元数据缓冲区（每子网格 20 字节，每帧复用）
    private ByteBuffer subMeshDataBuf;
    
    // 材质
    MMDMaterial[] mats;
    
    // 预分配临时对象
    private final Matrix4f tempModelView = new Matrix4f();
    
    MMDModelNativeRender() {}
    
    
    public static void Init(NativeFunc nativeFunc) {
        nf = nativeFunc;
    }
    
    /**
     * 从 PMX 文件加载模型（使用 Minecraft 原生渲染）
     */
    public static MMDModelNativeRender LoadModel(String pmxPath, String modelDirectory, long layerCount) {
        try {
            long model = getNf().LoadModelPMX(pmxPath, modelDirectory, layerCount);
            if (model == 0) {
                logger.error("加载模型失败: {}", pmxPath);
                return null;
            }
            MMDModelNativeRender result = createFromHandle(model, modelDirectory);
            if (result == null) {
                getNf().DeleteModel(model);
            }
            return result;
        } catch (Exception e) {
            logger.error("加载模型异常", e);
            return null;
        }
    }
    
    /**
     * 从已加载的模型句柄创建渲染实例（Phase 2：GL 资源创建，必须在渲染线程调用）
     * Phase 1（nf.LoadModelPMX）已在后台线程完成
     */
    public static MMDModelNativeRender createFromHandle(long model, String modelDirectory) {
        NativeFunc nf = getNf();
        
        // 资源追踪变量（用于异常时清理）
        FloatBuffer matMorphResultsBuf = null;
        ByteBuffer matMorphResultsByteBuf = null;
        int vao = 0;
        int[] vbos = null;
        ByteBuffer mcVertexBuf = null, poseMatBuf = null, normalMatBuf = null;
        ByteBuffer subMeshDataBufLocal = null;
        
        try {
            int vertexCount = (int) nf.GetVertexCount(model);
            
            // 加载材质
            int matCount = (int) nf.GetMaterialCount(model);
            MMDMaterial[] mats = new MMDMaterial[matCount];
            for (int i = 0; i < matCount; i++) {
                mats[i] = new MMDMaterial();
                String texPath = nf.GetMaterialTex(model, i);
                if (texPath != null && !texPath.isEmpty()) {
                    MMDTextureManager.Texture tex = MMDTextureManager.GetTexture(texPath);
                    if (tex != null) {
                        mats[i].tex = tex.tex;
                        mats[i].hasAlpha = tex.hasAlpha;
                    }
                }
            }
            
            // P2-9: 创建自定义 VAO + 每子网格 VBO（替代 MC VertexBuffer + BufferBuilder）
            BufferUploader.reset();
            int subMeshCount = (int) nf.GetSubMeshCount(model);
            vao = GL46C.glGenVertexArrays();
            vbos = new int[subMeshCount];
            int maxVertCount = 0;
            for (int i = 0; i < subMeshCount; i++) {
                vbos[i] = GL46C.glGenBuffers();
                int vc = nf.GetSubMeshVertexCount(model, i);
                maxVertCount = Math.max(maxVertCount, vc);
                // 预分配 VBO（后续使用 glBufferSubData 更新）
                GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, vbos[i]);
                GL46C.glBufferData(GL46C.GL_ARRAY_BUFFER, (long) vc * 36, GL46C.GL_DYNAMIC_DRAW);
            }
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, 0);
            
            // 预分配 Rust 顶点构建缓冲区
            mcVertexBuf = MemoryUtil.memAlloc(maxVertCount * 36);
            poseMatBuf = MemoryUtil.memAlloc(64);   // 4×4 float 矩阵
            normalMatBuf = MemoryUtil.memAlloc(36);  // 3×3 float 矩阵
            
            // 组装结果
            MMDModelNativeRender result = new MMDModelNativeRender();
            result.model = model;
            result.modelDir = modelDirectory;
            result.vertexCount = vertexCount;
            result.mats = mats;
            result.vao = vao;
            result.subMeshVBOs = vbos;
            result.subMeshCount = subMeshCount;
            result.mcVertexBuf = mcVertexBuf;
            result.poseMatBuf = poseMatBuf;
            result.normalMatBuf = normalMatBuf;
            subMeshDataBufLocal = MemoryUtil.memAlloc(subMeshCount * 20);
            subMeshDataBufLocal.order(ByteOrder.LITTLE_ENDIAN);
            result.subMeshDataBuf = subMeshDataBufLocal;
            
            // 初始化材质 Morph 结果缓冲区
            int matMorphCount = nf.GetMaterialMorphResultCount(model);
            if (matMorphCount > 0) {
                int floatCount = matMorphCount * 56;
                result.materialMorphResultCount = matMorphCount;
                matMorphResultsBuf = MemoryUtil.memAllocFloat(floatCount);
                matMorphResultsByteBuf = MemoryUtil.memAlloc(floatCount * 4);
                matMorphResultsByteBuf.order(ByteOrder.LITTLE_ENDIAN);
                result.materialMorphResultsBuffer = matMorphResultsBuf;
                result.materialMorphResultsByteBuffer = matMorphResultsByteBuf;
            }
            
            // 启用自动眨眼
            nf.SetAutoBlinkEnabled(model, true);
            
            logger.info("原生渲染模型加载成功 (P2-9): 顶点={}, 子网格={}, 最大子网格顶点={}", vertexCount, subMeshCount, maxVertCount);
            return result;
            
        } catch (Exception e) {
            logger.error("原生渲染模型创建失败，清理资源", e);
            
            if (matMorphResultsBuf != null) MemoryUtil.memFree(matMorphResultsBuf);
            if (matMorphResultsByteBuf != null) MemoryUtil.memFree(matMorphResultsByteBuf);
            if (mcVertexBuf != null) MemoryUtil.memFree(mcVertexBuf);
            if (poseMatBuf != null) MemoryUtil.memFree(poseMatBuf);
            if (normalMatBuf != null) MemoryUtil.memFree(normalMatBuf);
            if (subMeshDataBufLocal != null) MemoryUtil.memFree(subMeshDataBufLocal);
            if (vao != 0) GL46C.glDeleteVertexArrays(vao);
            if (vbos != null) {
                for (int vbo : vbos) {
                    if (vbo != 0) GL46C.glDeleteBuffers(vbo);
                }
            }
            
            return null;
        }
    }
    
    @Override
    public void dispose() {
        if (model == 0) return;
        if (mcVertexBuf != null) { MemoryUtil.memFree(mcVertexBuf); mcVertexBuf = null; }
        if (poseMatBuf != null) { MemoryUtil.memFree(poseMatBuf); poseMatBuf = null; }
        if (normalMatBuf != null) { MemoryUtil.memFree(normalMatBuf); normalMatBuf = null; }
        if (subMeshDataBuf != null) { MemoryUtil.memFree(subMeshDataBuf); subMeshDataBuf = null; }
        disposeMaterialMorphBuffers();
        if (subMeshVBOs != null) {
            for (int vbo : subMeshVBOs) {
                if (vbo != 0) GL46C.glDeleteBuffers(vbo);
            }
            subMeshVBOs = null;
        }
        if (vao != 0) { GL46C.glDeleteVertexArrays(vao); vao = 0; }
        disposeModelHandle();
    }
    
    @Override
    protected void onUpdate(float deltaTime) {
        getNf().UpdateModel(model, deltaTime);
    }
    
    @Override
    protected void doRenderModel(Entity entityIn, float entityYaw, float entityPitch, Vector3f entityTrans, PoseStack poseStack, int packedLight) {
        Minecraft mc = Minecraft.getInstance();
        
        // 变换矩阵
        poseStack.pushPose();
        try {
            poseStack.mulPose(tempQuat.identity().rotateY(-entityYaw * ((float) Math.PI / 180F)));
            poseStack.mulPose(tempQuat.identity().rotateX(entityPitch * ((float) Math.PI / 180F)));
            poseStack.translate(entityTrans.x, entityTrans.y, entityTrans.z);
            float baseScale = getModelScale();
            poseStack.scale(baseScale, baseScale, baseScale);
            
            // 获取材质 Morph 结果
            fetchMaterialMorphResults();
            
            // P2-9: 将 pose/normal 矩阵写入 ByteBuffer，供 Rust 侧矩阵变换使用
            poseMatBuf.clear();
            poseStack.last().pose().get(poseMatBuf);
            normalMatBuf.clear();
            poseStack.last().normal().get(normalMatBuf);
            
            // 启用混合和深度测试
            BufferUploader.reset();
            RenderSystem.enableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.defaultBlendFunc();
            
            // 设置着色器（一次，Iris 会正确拦截）
            RenderSystem.setShader(GameRenderer::getRendertypeEntityCutoutNoCullShader);
            ShaderInstance shader = RenderSystem.getShader();
            if (shader == null) return;
            
            // 设置 uniform（一次，替代每子网格重复设置）
            Matrix4f modelView = tempModelView.set(RenderSystem.getModelViewMatrix());
            Matrix4f projection = RenderSystem.getProjectionMatrix();
            for (int i = 0; i < 12; ++i) {
                int j = RenderSystem.getShaderTexture(i);
                shader.setSampler("Sampler" + i, j);
            }
            if (shader.MODEL_VIEW_MATRIX != null) shader.MODEL_VIEW_MATRIX.set(modelView);
            if (shader.PROJECTION_MATRIX != null) shader.PROJECTION_MATRIX.set(projection);
            if (shader.INVERSE_VIEW_ROTATION_MATRIX != null)
                shader.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
            if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
            if (shader.FOG_START != null) shader.FOG_START.set(RenderSystem.getShaderFogStart());
            if (shader.FOG_END != null) shader.FOG_END.set(RenderSystem.getShaderFogEnd());
            if (shader.FOG_COLOR != null) shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
            if (shader.FOG_SHAPE != null) shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
            if (shader.TEXTURE_MATRIX != null) shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
            if (shader.GAME_TIME != null) shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
            
            // 绑定 VAO（一次）并应用着色器
            GL46C.glBindVertexArray(vao);
            shader.apply();
            RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
            
            // G3 优化：批量获取所有子网格元数据（1 次 JNI 替代 ~4×N 次/帧）
            subMeshDataBuf.clear();
            nf.BatchGetSubMeshData(model, subMeshDataBuf);
            
            // 按子网格渲染
            for (int i = 0; i < subMeshCount; i++) {
                int base = i * 20;
                int materialID  = subMeshDataBuf.getInt(base);
                float alpha     = subMeshDataBuf.getFloat(base + 12);
                boolean visible = subMeshDataBuf.get(base + 16) != 0;
                boolean bothFace= subMeshDataBuf.get(base + 17) != 0;
                
                if (!visible) continue;
                if (getEffectiveMaterialAlpha(materialID, alpha) < 0.001f) continue;
                
                renderSubMesh(packedLight, materialID, bothFace, i, mc);
            }
            
            // === 清理 ===
            DefaultVertexFormat.NEW_ENTITY.clearBufferState();
            GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, 0);
            GL46C.glBindVertexArray(0);
            RenderSystem.activeTexture(GL46C.GL_TEXTURE0);
            shader.clear();
            BufferUploader.reset();
        } finally {
            poseStack.popPose();
        }
    }
    
    /**
     * P2-9: 使用 Rust 构建的 MC 顶点数据 + 自定义 VAO/VBO 渲染子网格
     * 
     * 着色器和 VAO 由调用者（RenderModel）管理（一次 apply/clear），此方法仅处理：
     * - 纹理绑定（RenderSystem 更新 Iris 纹理追踪 + glBindTexture 更新实际 GL 绑定）
     * - 面剔除设置
     * - Rust 顶点构建 + VBO 上传 + 绘制
     */
    private void renderSubMesh(int packedLight, int materialID, boolean bothFace, int subMeshIndex, Minecraft mc) {
        // 设置纹理（RenderSystem 更新 Iris 纹理追踪 + glBindTexture 更新实际 GL 绑定）
        int texId;
        if (mats[materialID].tex != 0) {
            texId = mats[materialID].tex;
        } else {
            texId = mc.getTextureManager().getTexture(TextureManager.INTENTIONAL_MISSING_TEXTURE).getId();
        }
        RenderSystem.setShaderTexture(0, texId);
        GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, texId);
        
        // 双面渲染（bothFace 已从 subMeshDataBuf 批量获取）
        if (bothFace) {
            RenderSystem.disableCull();
        } else {
            RenderSystem.enableCull();
        }
        
        // P2-9 核心：Rust 直接构建 MC NEW_ENTITY 格式的交错顶点数据
        int overlayPacked = 0 | (10 << 16); // OverlayTexture.pack(0, 10)
        mcVertexBuf.clear();
        int written = nf.BuildMCVertexBuffer(
            model, subMeshIndex, mcVertexBuf,
            poseMatBuf, normalMatBuf,
            0xFFFFFFFF, overlayPacked, packedLight
        );
        if (written <= 0) return;
        mcVertexBuf.position(0).limit(written * 36);
        
        // 上传到 VBO 并设置顶点属性指针（stride = 36 bytes）
        GL46C.glBindBuffer(GL46C.GL_ARRAY_BUFFER, subMeshVBOs[subMeshIndex]);
        GL46C.glBufferSubData(GL46C.GL_ARRAY_BUFFER, 0, mcVertexBuf);
        DefaultVertexFormat.NEW_ENTITY.setupBufferState();
        
        // 绘制（不使用索引，顶点已由 Rust 展开）
        GL46C.glDrawArrays(GL46C.GL_TRIANGLES, 0, written);
    }
    
}
