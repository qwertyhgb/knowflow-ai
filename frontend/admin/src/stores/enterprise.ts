import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * localStorage 保存当前企业 ID 的键名。
 * 供 http.ts 请求拦截器读取，避免硬编码字符串不一致。
 */
export const ENTERPRISE_STORAGE_KEY = 'knowflow_current_enterprise_id'

/**
 * 保存当前企业名称的键名。
 * 名称随 ID 一起持久化，供顶部栏免网络请求直接显示企业名。
 */
export const ENTERPRISE_NAME_STORAGE_KEY = 'knowflow_current_enterprise_name'

/**
 * 企业上下文 store。
 *
 * 【什么是"企业上下文"？】
 * KnowFlow 是多租户 SaaS：一个用户可能属于多个企业。对于企业作用域接口
 * （如 /api/enterprises/1/members），后端必须知道"当前用户正在操作哪个企业"，
 * 才能做两件事：
 *   1. 数据隔离——只返回该企业下的数据，不会串到其他企业；
 *   2. 权限校验——判断该用户是否是这个企业的成员、有没有对应权限。
 * 这个"当前正在操作的企业"就是企业上下文，通过请求头 X-Enterprise-Id 声明。
 * 本 store 就是前端"选定企业 → 持久化"的唯一数据源。
 *
 * 【为什么 currentEnterpriseId 存 localStorage？】
 * 与 token 同理：context 是跨页面、跨刷新都需要保持的状态。
 * 若只存内存，用户刷新页面后上下文丢失，企业作用域请求就会因缺头而 400。
 * 持久化保证"进入企业 → 刷新 → 仍在该企业"，体验连续。
 */
export const useEnterpriseStore = defineStore('enterprise', () => {
  /**
   * 当前选定的企业 ID（number 或 null）。
   * 初始化时从 localStorage 读取（解析成数字），实现刷新后上下文不丢。
   */
  const currentEnterpriseId = ref<number | null>(
    Number(localStorage.getItem(ENTERPRISE_STORAGE_KEY)) || null,
  )

  /** 当前企业名称：随 ID 持久化，供顶部栏即时展示 */
  const currentEnterpriseName = ref<string | null>(
    localStorage.getItem(ENTERPRISE_NAME_STORAGE_KEY),
  )

  /** 是否已选定企业：以 ID 是否为 null 为准 */
  const hasEnterprise = computed(() => currentEnterpriseId.value !== null)

  /**
   * 选定企业并持久化 ID。
   * 仅写 ID；名称单独用 setCurrentEnterpriseName 写入，二者保持同步调用。
   */
  function setCurrentEnterpriseId(id: number) {
    currentEnterpriseId.value = id
    localStorage.setItem(ENTERPRISE_STORAGE_KEY, String(id))
  }

  /** 持久化当前企业名称，配合 setCurrentEnterpriseId 一起调用 */
  function setCurrentEnterpriseName(name: string) {
    currentEnterpriseName.value = name
    localStorage.setItem(ENTERPRISE_NAME_STORAGE_KEY, name)
  }

  /**
   * 清除企业上下文（离开企业时调用）。
   * 内存态与持久态两处都要清，否则刷新后 id/name 从 localStorage 复活，上下文"死而复生"。
   */
  function clearCurrentEnterpriseId() {
    currentEnterpriseId.value = null
    currentEnterpriseName.value = null
    localStorage.removeItem(ENTERPRISE_STORAGE_KEY)
    localStorage.removeItem(ENTERPRISE_NAME_STORAGE_KEY)
  }

  return {
    currentEnterpriseId,
    currentEnterpriseName,
    hasEnterprise,
    setCurrentEnterpriseId,
    setCurrentEnterpriseName,
    clearCurrentEnterpriseId,
  }
})