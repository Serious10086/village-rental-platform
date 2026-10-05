/**
 * 图表公共配置。
 *
 * 【为什么要有这个文件:让几张图看起来是一套东西】
 * 三个统计页会画五六张图,如果每处自己挑颜色、自己写 tooltip,
 * 最后的结果是同一份数据在不同页面颜色不一样 —— 用户会以为它们说的不是一件事。
 * 配色和提示框样式收在一处,页面只写"这张图要表达什么"。
 *
 * 配色取自设计令牌的主色系,顺序也是固定的:
 * 前三个是主色/成功/警告,业务上最常出现的三类正好排在最前。
 */

/** 分类色板。按顺序取用,不要跳着用 —— 跳着用会让两张图里同样的含义颜色不同 */
export const CHART_COLORS = [
  '#2563eb', // 主色
  '#059669', // 成功
  '#d97706', // 警告
  '#7c3aed',
  '#dc2626', // 危险
  '#0891b2',
  '#64748b',
  '#f59e0b'
]

/** 统一的文字样式——图表里的字要和页面上的字看起来同源 */
export const AXIS_LABEL = {
  color: '#64748b',
  fontSize: 12
}

/** 统一的提示框。trigger 默认按数据项(饼图);折线/柱状图传 'axis' */
export function baseTooltip(trigger = 'item') {
  return {
    trigger,
    backgroundColor: 'rgba(15, 23, 42, 0.92)',
    borderWidth: 0,
    textStyle: { color: '#fff', fontSize: 12 }
  }
}

/**
 * 空数据的占位。
 *
 * 【为什么要专门处理空数据】
 * ECharts 对空数组不会报错,它会画出一张**没有任何内容的坐标轴** ——
 * 看起来像"加载失败"。给一个"暂无数据"的图形提示,用户才知道是"真的没有",
 * 而不是"坏了"。
 */
export function emptyOption(text = '暂无数据') {
  return {
    title: {
      text,
      left: 'center',
      top: 'center',
      textStyle: { color: '#94a3b8', fontSize: 13, fontWeight: 'normal' }
    }
  }
}

/** 数据是否为空(饼图/柱状图的数据数组) */
export function isEmpty(items) {
  return !items || items.length === 0
}

// ══════════════════════════════════════════════════════════════
//  三个统计页共用的 option 构造器
//
//  收在这里而不是各页自己写:同一份数据(比如"资源分类分布")在工作台和
//  村级统计两个页面上都要画,如果各写一遍,调色顺序、标签位置、
//  是否显示图例这些细节迟早会不一致 —— 而用户看到的是"同一个东西
//  在两个页面长得不一样",会以为数据也对不上。
// ══════════════════════════════════════════════════════════════

/**
 * 分类分布(饼图/环形)。
 *
 * 用环形而不是实心饼:中间那块空白正好放"资源总数"这个数字,
 * 比在图旁边再摆一个卡片更紧凑,也更容易看懂"这些扇区加起来是多少"。
 */
export function pieOption(items) {
  if (isEmpty(items)) return emptyOption('暂无在架资源')
  const total = items.reduce((sum, i) => sum + Number(i.count || 0), 0)
  return {
    color: CHART_COLORS,
    tooltip: baseTooltip('item'),
    legend: {
      type: 'scroll',
      bottom: 0,
      itemWidth: 10,
      itemHeight: 10,
      textStyle: AXIS_LABEL
    },
    series: [{
      type: 'pie',
      radius: ['45%', '68%'],
      center: ['50%', '45%'],
      avoidLabelOverlap: true,
      itemStyle: { borderColor: '#fff', borderWidth: 2 },
      label: { show: false },
      labelLine: { show: false },
      data: items.map((i) => ({ name: i.name, value: i.count })),
      // 中间那个总数:直接写在环形里,省掉一个卡片的位置
      emphasis: {
        label: {
          show: true,
          fontSize: 16,
          fontWeight: 'bold'
        }
      }
    }],
    graphic: {
      type: 'text',
      left: 'center',
      top: '40%',
      style: { text: String(total), fontSize: 22, fontWeight: 'bold', fill: '#0f172a' }
    }
  }
}

/** 订单状态分布(柱状)。状态多、名字长,所以柱子横向摆 */
export function barOption(items) {
  if (isEmpty(items)) return emptyOption('暂无订单')
  return {
    color: [CHART_COLORS[0]],
    tooltip: baseTooltip('axis'),
    grid: { left: 90, right: 30, top: 20, bottom: 20 },
    xAxis: { type: 'value', axisLabel: AXIS_LABEL, splitLine: { lineStyle: { color: '#f1f5f9' } } },
    yAxis: {
      type: 'category',
      data: items.map((i) => i.name),
      axisLabel: AXIS_LABEL,
      axisLine: { lineStyle: { color: '#e2e8f0' } }
    },
    series: [{
      type: 'bar',
      barMaxWidth: 18,
      itemStyle: { borderRadius: [0, 4, 4, 0] },
      data: items.map((i) => i.count)
    }]
  }
}

/** 近 30 天订单趋势(折线)。面积渐变让"哪天多哪天少"更直观 */
export function lineOption(trend) {
  if (isEmpty(trend)) return emptyOption('近 30 天没有订单')
  return {
    color: [CHART_COLORS[0]],
    tooltip: baseTooltip('axis'),
    grid: { left: 40, right: 20, top: 20, bottom: 30 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: trend.map((t) => t.date),
      axisLabel: { ...AXIS_LABEL, formatter: (v) => String(v).slice(5) },
      axisLine: { lineStyle: { color: '#e2e8f0' } }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: AXIS_LABEL,
      splitLine: { lineStyle: { color: '#f1f5f9' } }
    },
    series: [{
      type: 'line',
      smooth: true,
      symbolSize: 6,
      data: trend.map((t) => t.count),
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(37, 99, 235, 0.22)' },
            { offset: 1, color: 'rgba(37, 99, 235, 0)' }
          ]
        }
      }
    }]
  }
}

/**
 * 各村对比(分组柱状)。全局统计页专用。
 *
 * 【为什么把"资源数"和"订单数"放在同一张图里,而不是画两张】
 * 这两个数的**比例**才是信息:资源多但订单少,说明这个村的资源没盘活;
 * 两者都少,说明它本来就没什么可租的东西。分成两张图,读者得自己心算比例,
 * 而放在一张图上,两根柱子的高矮差一眼就能看出来。
 *
 * 【为什么不用双 Y 轴】
 * 双轴会让"谁高谁低"取决于两个轴的刻度怎么定 —— 同一份数据能画出
 * 两种相反的结论。两个数都是"条数",量级也接近,同一个轴完全够用。
 */
export function villageCompareOption(villages) {
  if (isEmpty(villages)) return emptyOption('还没有村组数据')
  return {
    color: [CHART_COLORS[0], CHART_COLORS[1]],
    tooltip: baseTooltip('axis'),
    legend: { top: 0, itemWidth: 12, itemHeight: 8, textStyle: AXIS_LABEL },
    grid: { left: 40, right: 20, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      data: villages.map((v) => v.villageName),
      axisLabel: AXIS_LABEL,
      axisLine: { lineStyle: { color: '#e2e8f0' } }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: AXIS_LABEL,
      splitLine: { lineStyle: { color: '#f1f5f9' } }
    },
    series: [
      {
        name: '资源总数',
        type: 'bar',
        barMaxWidth: 26,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
        data: villages.map((v) => v.resourceTotal)
      },
      {
        name: '订单总数',
        type: 'bar',
        barMaxWidth: 26,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
        data: villages.map((v) => v.orderTotal)
      }
    ]
  }
}
