<script setup>
/**
 * ECharts 包装组件。
 *
 * 【为什么要包一层,而不是每页自己 echarts.init】
 * 一段图表代码真正麻烦的不是 setOption,是另外三件事:窗口/容器变化时 resize、
 * 组件卸载时 dispose(不 dispose 会泄漏 canvas 和事件监听)、
 * 数据换了之后旧 series 要清干净。这三件事每个用图的页面都得写一遍,
 * 而漏掉 dispose 的表现是"切几次页面之后越来越卡"—— 很难归因。
 *
 * 【按需引入,不 import 整个 echarts】
 * `import * as echarts from 'echarts'` 会把所有图表类型和组件都打进包里,
 * 而后台一共就用三种图。实测过一次这次优化值多少钱 —— 只改这一行 import,
 * 其余不动,重新打包对比 chart 那个 chunk:
 *     全量引入  1121.19 kB(gzip 372.28 kB)
 *     按需引入   571.25 kB(gzip 194.07 kB)
 * 省下约 550 kB / gzip 178 kB,**差不多减半**。
 *
 * 注意别把它说成"砍到零头" —— 按需之后这一块仍然是整个前端最大的单个文件
 * (ECharts 的坐标系、动画、tooltip 这些骨架都得有)。它真正的意义是
 * "没有为三种图付全量 ECharts 的钱",不是"这一块变得可以忽略了"。
 */
import { ref, shallowRef, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts/core'
import { PieChart, BarChart, LineChart } from 'echarts/charts'
import {
  GridComponent, TooltipComponent, LegendComponent, TitleComponent,
  // graphic 是环形图中间那个总数用的 —— 漏注册的话它不会报"图表失败",
  // 而是静默不画出来,控制台里只有一行 warning。实测就是这样漏掉的
  GraphicComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  PieChart, BarChart, LineChart,
  GridComponent, TooltipComponent, LegendComponent, TitleComponent,
  GraphicComponent,
  CanvasRenderer
])

const props = defineProps({
  /** ECharts 的 option。变化时会重新 setOption */
  option: { type: Object, required: true },
  height: { type: String, default: '300px' },
  /** 数据还没回来时显示的骨架 */
  loading: { type: Boolean, default: false }
})

const el = ref(null)
/** 用 shallowRef:echarts 实例内部有大量可变状态,深层响应式会拖慢它 */
const chart = shallowRef(null)
let observer = null

function render() {
  if (!chart.value || !props.option) {
    return
  }
  // notMerge = true:数据换了就把旧的清掉。
  // 不加的话,从"5 个分类"变成"3 个分类"时,图里会留着两条不存在的柱子 ——
  // 而且它看起来还挺合理,不仔细对数字发现不了。
  chart.value.setOption(props.option, true)
}

onMounted(() => {
  chart.value = echarts.init(el.value)
  render()
  // 用 ResizeObserver 而不是监听 window.resize:侧边栏折叠、卡片换行
  // 都不会触发 window 事件,但容器尺寸确实变了,图表得跟着变
  observer = new ResizeObserver(() => chart.value?.resize())
  observer.observe(el.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  // 必须 dispose:否则 canvas 和内部的事件监听会跟着实例一起被"遗忘"在内存里
  chart.value?.dispose()
})

watch(() => props.option, render, { deep: true })
watch(() => props.loading, (v) => (v ? chart.value?.showLoading() : chart.value?.hideLoading()))
</script>

<template>
  <div ref="el" class="echart-box" :style="{ height }" />
</template>

<style scoped>
.echart-box {
  width: 100%;
}
</style>
