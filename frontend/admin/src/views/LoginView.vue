<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { DataAnalysis } from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user'
import { login } from '../api/user'
import type { LoginParams } from '../api/types'

/**
 * 登录页。
 *
 * 全屏渐变背景 + 居中白色圆角卡片，视觉风格与首页欢迎卡片统一
 * （第 1 步已建立的 SaaS 风格：柔和渐变、白色卡片、蓝色主色）。
 */

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)

/** 表单数据 */
const form = reactive<LoginParams>({
  email: '',
  password: '',
})

/**
 * 表单校验规则。
 * 与后端 UserLoginRequest 校验保持一致（邮箱格式、密码 8-20 位），
 * 前端先校验一层，减少无效请求；后端仍会二次校验，前端校验不是安全边界。
 */
const rules: FormRules<LoginParams> = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 20, message: '密码长度需在8到20位之间', trigger: 'blur' },
  ],
}

/** 提交登录 */
async function handleLogin() {
  // 先触发前端校验，不通过直接 return
  await formRef.value?.validate(async (valid) => {
    if (!valid) return

    submitting.value = true
    try {
      const result = await login(form)
      // 登录成功：写入 store（同时持久化 token 到 localStorage），再写入用户信息
      userStore.setToken(result.token)
      userStore.setUser(result.user)
      ElMessage.success('登录成功')

      // 跳转：优先回到用户被拦截前的页面（query.redirect），否则回首页。
      // 只接受以 '/' 开头的站内路径，防止被恶意跳转到外部站点（开放重定向）。
      const redirect = route.query.redirect as string | undefined
      if (redirect && redirect.startsWith('/')) {
        router.push(redirect)
      } else {
        router.push('/')
      }
    } catch (error) {
      // 登录失败：error.message 已是 http.ts 归一化后的后端文案
      // （如"邮箱或密码错误"），直接展示即可。
      ElMessage.error((error as Error).message || '登录失败，请稍后重试')
    } finally {
      submitting.value = false
    }
  })
}
</script>

<template>
  <div class="login">
    <div class="card">
      <div class="logo">
        <el-icon :size="44" color="#409eff"><DataAnalysis /></el-icon>
      </div>

      <h1 class="title">欢迎回来</h1>
      <p class="subtitle">登录 KnowFlow 管理端</p>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="handleLogin"
      >
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

        <el-form-item>
          <el-button
            type="primary"
            class="submit-btn"
            :loading="submitting"
            native-type="submit"
          >
            {{ submitting ? '登录中…' : '登 录' }}
          </el-button>
        </el-form-item>
      </el-form>

      <p class="switch">
        还没有账号？
        <router-link to="/register" class="link">立即注册</router-link>
      </p>
    </div>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  /* 与第 1 步欢迎页一致：柔和渐变背景 */
  background: linear-gradient(135deg, #f5f7fb 0%, #e8f0fe 50%, #d6e4ff 100%);
}

.card {
  width: 100%;
  max-width: 420px;
  background: #ffffff;
  border-radius: 20px;
  padding: 44px 40px;
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
  margin-bottom: 28px;
}

/* 让表单按钮占满整行，观感更规整 */
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