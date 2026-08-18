<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { DataAnalysis } from '@element-plus/icons-vue'
import { register } from '../api/user'

/**
 * 注册页。
 *
 * 风格与登录页完全一致（渐变背景 + 白色圆角卡片）。
 * 提交成功后不自动登录（后端注册接口只返回 UserVO、不含 token），
 * 提示用户前往登录页登录，这是后端能力决定的前端交互设计。
 */

const router = useRouter()

const formRef = ref<FormInstance>()
const submitting = ref(false)

/** 表单数据：确认密码仅用于前端校验，不提交给后端 */
const form = reactive({
  nickname: '',
  email: '',
  password: '',
  confirmPassword: '',
})

/**
 * 校验规则。密码长度对照后端 UserRegisterRequest（8-20 位），
 * 昵称最长 50。确认密码用自定义 validator 校验两次输入一致。
 */
const rules: FormRules<typeof form> = {
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 50, message: '昵称长度不能超过50', trigger: 'blur' },
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 20, message: '密码长度需在8到20位之间', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      // 自定义校验：两次密码必须一致
      validator: (_rule, value, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

/** 提交注册 */
async function handleRegister() {
  await formRef.value?.validate(async (valid) => {
    if (!valid) return

    submitting.value = true
    try {
      // 只提交后端需要的三个字段，确认密码留在前端校验即可
      await register({
        nickname: form.nickname,
        email: form.email,
        password: form.password,
      })
      ElMessage.success('注册成功，请登录')
      router.push('/login')
    } catch (error) {
      // 显示后端返回的业务 message（如"邮箱已被注册"）
      ElMessage.error((error as Error).message || '注册失败，请稍后重试')
    } finally {
      submitting.value = false
    }
  })
}
</script>

<template>
  <div class="register">
    <div class="card">
      <div class="logo">
        <el-icon :size="44" color="#409eff"><DataAnalysis /></el-icon>
      </div>

      <h1 class="title">创建账号</h1>
      <p class="subtitle">注册 KnowFlow 管理端</p>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="handleRegister"
      >
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" placeholder="请输入昵称" clearable />
        </el-form-item>

        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="请输入邮箱" clearable />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码（8-20位）"
            show-password
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            show-password
          />
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            class="submit-btn"
            :loading="submitting"
            native-type="submit"
          >
            {{ submitting ? '注册中…' : '注 册' }}
          </el-button>
        </el-form-item>
      </el-form>

      <p class="switch">
        已有账号？
        <router-link to="/login" class="link">去登录</router-link>
      </p>
    </div>
  </div>
</template>

<style scoped>
.register {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  /* 与登录页一致：柔和渐变背景 */
  background: linear-gradient(135deg, #f5f7fb 0%, #e8f0fe 50%, #d6e4ff 100%);
}

.card {
  width: 100%;
  max-width: 460px;
  background: #ffffff;
  border-radius: 20px;
  padding: 40px;
  box-shadow:
    0 20px 40px rgba(64, 158, 255, 0.12),
    0 4px 12px rgba(0, 0, 0, 0.04);
}

.logo {
  width: 72px;
  height: 72px;
  margin: 0 auto 20px;
  border-radius: 18px;
  background: #ecf5ff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.title {
  font-size: 24px;
  font-weight: 600;
  color: #1f2d3d;
  text-align: center;
  margin-bottom: 6px;
}

.subtitle {
  font-size: 14px;
  color: #909399;
  text-align: center;
  margin-bottom: 24px;
}

.submit-btn {
  width: 100%;
  margin-top: 4px;
}

.switch {
  margin-top: 20px;
  text-align: center;
  font-size: 14px;
  color: #606266;
}

.link {
  color: #409eff;
  text-decoration: none;
  font-weight: 500;
}

.link:hover {
  text-decoration: underline;
}
</style>