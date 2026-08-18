<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, FolderOpened } from '@element-plus/icons-vue'
import { useEnterpriseStore } from '../stores/enterprise'
import { createKnowledgeBase, getKnowledgeBases } from '../api/knowledgeBase'
import type { KnowledgeBaseVO, KnowledgeBaseAccessMode } from '../api/types'

/**
 * 知识库列表页。
 *
 * 知识库从属于当前企业，访问此页面时：
 * - 未选定企业 → 引导用户先选择企业
 * - 已选定企业 → 展示知识库列表，可创建新知识库
 */

const router = useRouter()
const enterpriseStore = useEnterpriseStore()

/** 是否已选定企业 */
const hasEnterprise = computed(() => enterpriseStore.hasEnterprise)

/** 知识库列表 */
const list = ref<KnowledgeBaseVO[]>([])
/** 加载中 */
const loading = ref(false)

// ---------- 创建知识库对话框 ----------
const dialogVisible = ref(false)
const creating = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  name: '',
  description: '',
  /**
   * 默认访问模式为 PRIVATE（私有）。
   *
   * 【为什么默认私有？】
   * 数据最小可见原则——知识库默认只对成员可见，
   * 创建者如果需要更多人访问，再显式改为公开（PUBLIC）。
   * 这是一种"默认安全"的设计：宁可让信息少暴露，也不要默认暴露过多。
   */
  accessMode: 'PRIVATE' as KnowledgeBaseAccessMode,
})

const rules: FormRules<typeof form> = {
  name: [
    { required: true, message: '请输入知识库名称', trigger: 'blur' },
    { max: 100, message: '名称长度不能超过100', trigger: 'blur' },
  ],
  description: [
    { max: 500, message: '描述长度不能超过500', trigger: 'blur' },
  ],
}

onMounted(() => {
  if (hasEnterprise.value) {
    loadList()
  }
})

/** 拉取知识库列表 */
async function loadList() {
  const id = enterpriseStore.currentEnterpriseId
  if (id === null) return
  loading.value = true
  try {
    list.value = await getKnowledgeBases(id)
  } catch {
    // 错误提示已由 http.ts 响应拦截器统一处理
  } finally {
    loading.value = false
  }
}

/** 提交创建知识库 */
async function handleCreate() {
  await formRef.value?.validate(async (valid) => {
    if (!valid) return
    creating.value = true
    try {
      await createKnowledgeBase(enterpriseStore.currentEnterpriseId!, {
        name: form.name,
        description: form.description || undefined,
        accessMode: form.accessMode,
      })
      ElMessage.success('知识库创建成功')
      dialogVisible.value = false
      form.name = ''
      form.description = ''
      form.accessMode = 'PRIVATE'
      await loadList()
    } catch {
      // 错误提示已由 http.ts 统一处理
    } finally {
      creating.value = false
    }
  })
}

/** 进入知识库详情 */
function handleEnter(kb: KnowledgeBaseVO) {
  router.push(`/knowledge-bases/${kb.id}`)
}

/** 访问模式显示文本 */
function accessModeLabel(mode: string): string {
  return mode === 'PUBLIC' ? '公开' : '私有'
}

/** 我的角色显示文本 */
function myRoleLabel(role: string | null): string {
  if (!role) return ''
  const map: Record<string, string> = { ADMIN: '管理', EDITOR: '可编辑', VIEWER: '只读' }
  return map[role] ?? ''
}

/** 格式化时间 */
function formatTime(iso: string | undefined): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

/** 图标渐变色 */
const gradients = [
  'linear-gradient(135deg, #409eff, #66b1ff)',
  'linear-gradient(135deg, #67c23a, #85ce61)',
  'linear-gradient(135deg, #e6a23c, #ebb563)',
  'linear-gradient(135deg, #f56c6c, #f78989)',
  'linear-gradient(135deg, #909399, #a8abb2)',
  'linear-gradient(135deg, #b37feb, #d3adf7)',
]
function iconBg(id: number): string {
  return gradients[id % gradients.length]
}
</script>

<template>
  <div class="knowledge-base">
    <!-- ============ 未选定企业：引导提示 ============ -->
    <template v-if="!hasEnterprise">
      <div class="guide-card">
        <el-icon :size="56" color="#c0c4cc"><FolderOpened /></el-icon>
        <h3 class="guide-title">请先选择企业</h3>
        <p class="guide-desc">知识库从属于企业，请先在企业管理中选择要操作的企业。</p>
        <el-button type="primary" @click="router.push('/enterprise')">
          前往企业管理
        </el-button>
      </div>
    </template>

    <!-- ============ 已选定企业：知识库列表 ============ -->
    <template v-else>
      <div class="list-head">
        <div class="list-head-left">
          <h2 class="list-title">知识库</h2>
          <el-tag type="primary" effect="light" size="small" class="ent-tag">
            {{ enterpriseStore.currentEnterpriseName }}
          </el-tag>
        </div>
        <el-button type="primary" :icon="Plus" @click="dialogVisible = true">
          创建知识库
        </el-button>
      </div>

      <!-- 空态 -->
      <el-empty
        v-if="!loading && list.length === 0"
        description="还没有知识库，创建第一个吧"
      />

      <!-- 知识库卡片网格 -->
      <div v-else-if="!loading" class="card-grid">
        <div
          v-for="item in list"
          :key="item.id"
          class="kb-card"
          @click="handleEnter(item)"
        >
          <div class="card-top">
            <span class="kb-icon" :style="{ background: iconBg(item.id) }">
              {{ item.name[0] }}
            </span>
            <div class="kb-tags">
              <!-- 访问模式 -->
              <el-tag
                :type="item.accessMode === 'PUBLIC' ? 'success' : 'info'"
                size="small"
                effect="light"
              >
                {{ accessModeLabel(item.accessMode) }}
              </el-tag>
              <!-- 我的角色 -->
              <el-tag
                v-if="item.myRole"
                :type="item.myRole === 'ADMIN' ? 'primary' : item.myRole === 'EDITOR' ? 'success' : 'info'"
                size="small"
              >
                {{ myRoleLabel(item.myRole) }}
              </el-tag>
              <!-- 已禁用 -->
              <el-tag
                v-if="item.status === 'DISABLED'"
                type="danger"
                size="small"
              >
                已禁用
              </el-tag>
            </div>
          </div>
          <div class="card-body">
            <div class="kb-name">{{ item.name }}</div>
            <div class="kb-desc" v-if="item.description">{{ item.description }}</div>
          </div>
          <div class="card-footer">
            <span class="kb-time">创建于 {{ formatTime(item.createdAt) }}</span>
          </div>
        </div>
      </div>
    </template>

    <!-- ============ 创建知识库对话框 ============ -->
    <el-dialog v-model="dialogVisible" title="创建知识库" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="名称" prop="name">
          <el-input
            v-model="form.name"
            placeholder="请输入知识库名称"
            clearable
            maxlength="100"
          />
        </el-form-item>

        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="请输入知识库描述（选填）"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="访问模式" prop="accessMode">
          <el-radio-group v-model="form.accessMode">
            <el-radio value="PRIVATE">
              <div class="radio-option">
                <span class="radio-label">私有</span>
                <span class="radio-desc">仅知识库成员可见</span>
              </div>
            </el-radio>
            <el-radio value="PUBLIC">
              <div class="radio-option">
                <span class="radio-label">公开</span>
                <span class="radio-desc">企业内所有成员可见</span>
              </div>
            </el-radio>
          </el-radio-group>
          <!--
            【为什么默认私有？】
            数据最小可见原则——知识库默认只对成员可见，
            如果确实需要更多人访问，创建者可以显式改为公开。
            这是一种"默认安全"的设计，避免敏感信息意外暴露。
          -->
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">
          创建
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.knowledge-base {
  max-width: 960px;
  margin: 0 auto;
}

/* ---------- 引导卡片 ---------- */
.guide-card {
  text-align: center;
  background: #ffffff;
  border-radius: 16px;
  padding: 60px 40px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
}

.guide-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin: 16px 0 8px;
}

.guide-desc {
  font-size: 14px;
  color: #909399;
  margin-bottom: 24px;
}

/* ---------- 列表头部 ---------- */
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.list-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.list-title {
  font-size: 20px;
  font-weight: 600;
  color: #1f2d3d;
}

.ent-tag {
  font-size: 12px;
}

/* ---------- 卡片网格 ---------- */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 20px;
}

.kb-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
  display: flex;
  flex-direction: column;
}

.kb-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 24px rgba(0, 0, 0, 0.08);
}

.card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 14px;
}

.kb-icon {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  color: #fff;
  font-size: 20px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.kb-tags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.card-body {
  flex: 1;
  min-height: 0;
}

.kb-name {
  font-size: 16px;
  font-weight: 600;
  color: #1f2d3d;
  margin-bottom: 6px;
}

.kb-desc {
  font-size: 13px;
  color: #909399;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-footer {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #f0f2f5;
}

.kb-time {
  font-size: 12px;
  color: #c0c4cc;
}

/* ---------- 对话框 radio 选项 ---------- */
.radio-option {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.radio-label {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.radio-desc {
  font-size: 12px;
  color: #909399;
}
</style>