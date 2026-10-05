# -*- coding: utf-8 -*-
"""
生成演示用的资源图片(SVG)。

为什么不用真实照片:
  1. 演示数据需要几十张图,找不到也摆不平版权;
  2. 用外部图床(如 picsum)的话,答辩现场没网就全是破图;
  3. SVG 是纯文本,体积只有几 KB,随项目一起走,任何环境都能显示。

设计上按"类别"配色,同一类的资源视觉成组,列表页扫一眼就能分辨品类。
"""
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")

OUT = "D:/bishe/claude/frontend/public/demo"
os.makedirs(OUT, exist_ok=True)

# 每个大类一个配色:同类资源视觉成组
PALETTE = {
    "machine": ("#2563eb", "#1e40af"),   # 农机具   蓝
    "vehicle": ("#0891b2", "#0e7490"),   # 交通工具 青
    "place":   ("#7c3aed", "#5b21b6"),   # 房屋场地 紫
    "tool":    ("#d97706", "#b45309"),   # 工具设备 橙
    "power":   ("#dc2626", "#991b1b"),   # 电力设备 红(高风险)
}

TEMPLATE = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300" width="400" height="300">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="{c1}"/>
      <stop offset="100%" stop-color="{c2}"/>
    </linearGradient>
  </defs>
  <rect width="400" height="300" fill="url(#g)"/>
  <g stroke="#ffffff" stroke-opacity="0.07" stroke-width="1">{grid}</g>
  <g fill="#ffffff" fill-opacity="0.95">{icon}</g>
  <text x="200" y="252" text-anchor="middle"
        font-family="PingFang SC, Microsoft YaHei, sans-serif"
        font-size="22" font-weight="600" fill="#ffffff" fill-opacity="0.95">{label}</text>
  <text x="200" y="276" text-anchor="middle"
        font-family="PingFang SC, Microsoft YaHei, sans-serif"
        font-size="12" fill="#ffffff" fill-opacity="0.6">乡村闲置资源租售平台</text>
</svg>"""

GRID = "".join('<line x1="%d" y1="0" x2="%d" y2="300"/>' % (x, x) for x in range(0, 401, 25)) + \
       "".join('<line x1="0" y1="%d" x2="400" y2="%d"/>' % (y, y) for y in range(0, 301, 25))


def render(label, kind, icon):
    c1, c2 = PALETTE[kind]
    return TEMPLATE.format(c1=c1, c2=c2, grid=GRID, icon=icon, label=label)


def tractor():
    return ('<rect x="150" y="110" width="130" height="55" rx="8"/>'
            '<rect x="255" y="85" width="42" height="80" rx="6" opacity="0.75"/>'
            '<rect x="176" y="88" width="30" height="26" rx="4" opacity="0.6"/>'
            '<circle cx="170" cy="180" r="42"/>'
            '<circle cx="170" cy="180" r="20" fill-opacity="0.35"/>'
            '<circle cx="278" cy="188" r="28"/>'
            '<circle cx="278" cy="188" r="12" fill-opacity="0.35"/>')


def harvester():
    return ('<rect x="130" y="105" width="120" height="60" rx="8"/>'
            '<rect x="228" y="80" width="46" height="85" rx="6" opacity="0.75"/>'
            '<path d="M120 148 L84 168 L84 188 L120 176 Z"/>'
            '<circle cx="160" cy="182" r="38"/>'
            '<circle cx="160" cy="182" r="17" fill-opacity="0.35"/>'
            '<circle cx="252" cy="188" r="26"/>'
            '<circle cx="252" cy="188" r="11" fill-opacity="0.35"/>')


def seeder():
    lines = "".join('<line x1="%d" y1="176" x2="%d" y2="200"/>' % (x, x)
                    for x in (150, 176, 202, 228))
    return ('<rect x="140" y="112" width="96" height="52" rx="8"/>'
            '<rect x="238" y="96" width="34" height="68" rx="5" opacity="0.75"/>'
            '<path d="M132 138 L96 148 L96 156 L132 152 Z"/>'
            '<g stroke="#ffffff" stroke-width="6" stroke-linecap="round" opacity="0.8">'
            + lines + '</g>'
            '<circle cx="170" cy="180" r="30"/>'
            '<circle cx="170" cy="180" r="13" fill-opacity="0.35"/>')


def rotavator():
    lines = "".join('<line x1="%d" y1="182" x2="%d" y2="202"/>' % (x, x)
                    for x in (140, 164, 188, 212, 236, 260))
    return ('<rect x="146" y="98" width="108" height="50" rx="8"/>'
            '<rect x="246" y="86" width="30" height="62" rx="5" opacity="0.75"/>'
            '<rect x="126" y="152" width="150" height="30" rx="6" opacity="0.85"/>'
            '<g stroke="#ffffff" stroke-width="5" stroke-linecap="round" opacity="0.75">'
            + lines + '</g>')


def tricycle():
    return ('<rect x="130" y="112" width="112" height="46" rx="7"/>'
            '<path d="M242 132 L286 122 L286 158 L242 158 Z" opacity="0.8"/>'
            '<rect x="120" y="126" width="24" height="32" rx="4" opacity="0.7"/>'
            '<circle cx="166" cy="184" r="30"/>'
            '<circle cx="166" cy="184" r="12" fill-opacity="0.35"/>'
            '<circle cx="264" cy="188" r="24"/>'
            '<circle cx="264" cy="188" r="10" fill-opacity="0.35"/>')


def ebike():
    return ('<rect x="146" y="126" width="96" height="34" rx="7"/>'
            '<path d="M242 126 L282 116 L282 144 L242 144 Z" opacity="0.8"/>'
            '<rect x="128" y="112" width="20" height="26" rx="4" opacity="0.7"/>'
            '<circle cx="180" cy="182" r="28"/>'
            '<circle cx="180" cy="182" r="11" fill-opacity="0.35"/>'
            '<circle cx="266" cy="184" r="24"/>'
            '<circle cx="266" cy="184" r="10" fill-opacity="0.35"/>')


def warehouse():
    return ('<path d="M110 128 L200 92 L290 128 L290 196 L110 196 Z"/>'
            '<rect x="166" y="146" width="68" height="50" fill-opacity="0.35"/>'
            '<line x1="110" y1="160" x2="290" y2="160" stroke="#ffffff" '
            'stroke-width="4" opacity="0.5"/>')


def yard():
    lines = "".join('<line x1="%d" y1="%d" x2="%d" y2="144"/>' % (x, y, x)
                    for x, y in ((128, 122), (164, 116), (200, 122), (236, 114), (272, 124)))
    return ('<rect x="104" y="150" width="192" height="46" rx="6"/>'
            '<g stroke="#ffffff" stroke-width="5" stroke-linecap="round" opacity="0.7">'
            + lines + '</g>'
            '<circle cx="200" cy="112" r="18" opacity="0.85"/>')


def courtyard():
    return ('<path d="M126 140 L200 100 L274 140 L274 186 L126 186 Z"/>'
            '<rect x="184" y="152" width="32" height="34" fill-opacity="0.4"/>'
            '<line x1="126" y1="164" x2="274" y2="164" stroke="#ffffff" '
            'stroke-width="3" opacity="0.45"/>')


def land():
    return ('<rect x="112" y="140" width="176" height="56" rx="6"/>'
            '<g stroke="#ffffff" stroke-width="4" opacity="0.45">'
            '<line x1="112" y1="160" x2="288" y2="160"/>'
            '<line x1="112" y1="180" x2="288" y2="180"/></g>'
            '<path d="M148 132 L148 108 L176 122 Z" opacity="0.85"/>')


def welder():
    return ('<rect x="140" y="116" width="104" height="66" rx="8"/>'
            '<rect x="162" y="182" width="60" height="14" rx="4" opacity="0.6"/>'
            '<path d="M256 132 L296 108 M256 148 L300 148 M256 164 L296 172" '
            'stroke="#ffffff" stroke-width="6" stroke-linecap="round" fill="none" opacity="0.85"/>')


def pump():
    return ('<rect x="160" y="128" width="72" height="60" rx="8"/>'
            '<path d="M232 150 L282 122 L282 186 L232 158 Z" opacity="0.8"/>'
            '<rect x="140" y="146" width="22" height="24" rx="4" opacity="0.7"/>'
            '<g stroke="#ffffff" stroke-width="4" stroke-linecap="round" opacity="0.5">'
            '<line x1="176" y1="188" x2="176" y2="212"/>'
            '<line x1="216" y1="188" x2="216" y2="212"/></g>')


def generator():
    return ('<rect x="132" y="118" width="136" height="74" rx="9"/>'
            '<path d="M232 128 L206 152 L226 152 L200 182" stroke="#ffffff" '
            'stroke-width="9" stroke-linecap="round" stroke-linejoin="round" fill="none" opacity="0.9"/>'
            '<rect x="150" y="192" width="100" height="12" rx="4" opacity="0.55"/>')


def woodwork():
    return ('<rect x="126" y="150" width="150" height="30" rx="6"/>'
            '<rect x="146" y="120" width="26" height="30" rx="4" opacity="0.8"/>'
            '<rect x="186" y="112" width="22" height="38" rx="4" opacity="0.7"/>'
            '<path d="M240 118 L268 118 L268 146 L240 146 Z" opacity="0.75"/>'
            '<rect x="150" y="180" width="100" height="10" rx="4" opacity="0.5"/>')


def transformer():
    lines = "".join('<line x1="%d" y1="128" x2="%d" y2="180"/>' % (x, x)
                    for x in (160, 184, 216, 240))
    return ('<rect x="136" y="112" width="128" height="84" rx="8"/>'
            '<g stroke="#ffffff" stroke-width="5" opacity="0.55">' + lines + '</g>'
            '<path d="M206 96 L186 130 L202 130 L182 164" stroke="#ffffff" '
            'stroke-width="8" stroke-linecap="round" stroke-linejoin="round" fill="none"/>')


def panel():
    return ('<rect x="134" y="110" width="132" height="88" rx="8"/>'
            '<rect x="150" y="126" width="44" height="26" rx="4" fill-opacity="0.45"/>'
            '<rect x="206" y="126" width="44" height="26" rx="4" fill-opacity="0.45"/>'
            '<rect x="150" y="160" width="44" height="26" rx="4" fill-opacity="0.45"/>'
            '<circle cx="228" cy="173" r="13" fill-opacity="0.75"/>')


def tractor_side():
    return ('<rect x="120" y="118" width="150" height="52" rx="8"/>'
            '<rect x="240" y="92" width="38" height="78" rx="6" opacity="0.7"/>'
            '<circle cx="156" cy="182" r="40"/>'
            '<circle cx="156" cy="182" r="18" fill-opacity="0.35"/>'
            '<circle cx="268" cy="186" r="30"/>'
            '<circle cx="268" cy="186" r="13" fill-opacity="0.35"/>')


ITEMS = [
    ("tractor.svg",     "拖拉机",     "machine", tractor),
    ("tractor2.svg",    "拖拉机·侧面", "machine", tractor_side),
    ("harvester.svg",   "收割机",     "machine", harvester),
    ("seeder.svg",      "播种机",     "machine", seeder),
    ("rotavator.svg",   "旋耕机",     "machine", rotavator),
    ("tricycle.svg",    "农用三轮车", "vehicle", tricycle),
    ("ebike.svg",       "电动车",     "vehicle", ebike),
    ("warehouse.svg",   "仓库",       "place",   warehouse),
    ("yard.svg",        "晒场",       "place",   yard),
    ("courtyard.svg",   "院落",       "place",   courtyard),
    ("land.svg",        "场地",       "place",   land),
    ("welder.svg",      "电焊机",     "tool",    welder),
    ("pump.svg",        "水泵",       "tool",    pump),
    ("generator.svg",   "发电机",     "tool",    generator),
    ("woodwork.svg",    "木工工具",   "tool",    woodwork),
    ("transformer.svg", "变压器",     "power",   transformer),
    ("panel.svg",       "配电箱",     "power",   panel),
]

for name, label, kind, fn in ITEMS:
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        f.write(render(label, kind, fn()))

print("已生成 %d 个演示图片 → %s" % (len(ITEMS), OUT))
for name, label, _, _ in ITEMS:
    print("   %-18s %s" % (name, label))
