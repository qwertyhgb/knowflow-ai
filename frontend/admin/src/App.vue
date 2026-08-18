<script setup lang="ts">
// 应用根组件：最小壳，只保留路由出口。
// 所有页面内容由 router-view 根据当前路由渲染，根组件本身不承载任何业务 UI。
</script>

<template>
  <router-view />
</template>