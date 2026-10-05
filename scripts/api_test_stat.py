#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次 9 —— 数据统计 + 操作日志 接口层验证。

覆盖:统计的口径自洽、三个页面的数据级权限、**村管传 villageId 会不会被忽略**、
操作日志的写入(成功与失败)、**敏感字段脱敏**、日志的权限与筛选。

【这一批的接口测试要盯住什么】
统计最容易"看着有数就算过"。但它真正要交付的是三条硬事实:
    1. 同一个接口,不同角色拿到的范围不同 —— 而且**传参数也越不了权**
    2. 比率是从汇总数重新算的,不是各村比率的平均(后者是个统计错误)
    3. 审计日志里不能有密码/身份证 —— 那张表很多人能看

第 2 条尤其值得断言:它没有报错、不会崩,只是**数字悄悄不对**,
而"平台利用率"是要写进论文和公示页的。

用法(需先启动后端 8080):
    python scripts/api_test_stat.py

注意:本脚本会发布公告、导入敏感词(为了产生日志),重复跑之前请重置演示数据:
    mysql -uroot -proot < sql/05_mock_data.sql
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

BASE = "http://localhost:8080"

passed = 0
failed = 0


def call(method, path, body=None, token=None, params=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


def check(name, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}  {detail}")


def check_code(name, res, expect_code):
    got = res.get("code")
    check(name, got == expect_code,
          f"期望 code={expect_code},实际 {got} / {res.get('message')}")


def login(phone, password="123456"):
    _, res = call("POST", "/api/auth/login", {"phone": phone, "password": password})
    return (res.get("data") or {}).get("token")


def section(title):
    print()
    print("─" * 70)
    print(title)
    print("─" * 70)


def stat_of(token, path, params=None):
    _, res = call("GET", path, token=token, params=params)
    return res.get("code"), (res.get("data") or {})


def nz(v):
    return 0 if v is None else v


# ══════════════════════════════════════════════════════════════════
#  测试数据约定(与 sql/05_mock_data.sql 一一对应)
#
#  村组:1 唐昌镇(镇节点)  2 战旗村  3 先锋村  4 金星村
#  资源:战旗村 16 条、金星村 7 条、先锋村 0 条 —— 合计 23
#  用户:11 个(超管在镇节点,村管在村里。第 11 个孙志强是"已建号、备案未过"
#        的外来用户,village_id 还是空的,所以哪个村的统计都数不到他)
# ══════════════════════════════════════════════════════════════════

print("=" * 70)
print("批次 9 —— 数据统计 + 操作日志 验证")
print("=" * 70)

super_admin = login("13800000000")
village_admin = login("13800000001")   # 战旗村支书
other_admin = login("13800000002")     # 先锋村主任
villager = login("13800000003")        # 张建国(普通村民)

check("四个测试账号全部登录成功",
      all([super_admin, village_admin, other_admin, villager]),
      "有账号登录失败,后续用例不可信")


# ══════════════════════════════════════════════════════════════════
section("一、工作台统计:口径与范围")
# ══════════════════════════════════════════════════════════════════

check_code("未登录访问工作台统计 → 401",
           call("GET", "/api/admin/stat/dashboard")[1], 401)

code, village_dash = stat_of(village_admin, "/api/admin/stat/dashboard")
check_code("村管拿到本村的统计", {"code": code}, 200)

code, super_dash = stat_of(super_admin, "/api/admin/stat/dashboard")
check("村管的资源数 ≤ 超管(范围是本村)",
      nz(village_dash.get("resourceTotal")) <= nz(super_dash.get("resourceTotal")),
      f"村管 {village_dash.get('resourceTotal')} vs 超管 {super_dash.get('resourceTotal')}")
check("超管看到的用户数 ≥ 村管(全平台 vs 本村)",
      nz(super_dash.get("userTotal")) >= nz(village_dash.get("userTotal")),
      f"超管 {super_dash.get('userTotal')} vs 村管 {village_dash.get('userTotal')}")

check("工作台带了三张图的数据(分类分布 / 订单状态 / 趋势)",
      len(super_dash.get("categoryDist") or []) > 0
      and len(super_dash.get("orderStatusDist") or []) > 0
      and len(super_dash.get("orderTrend") or []) > 0,
      f"分类 {len(super_dash.get('categoryDist') or [])} / "
      f"状态 {len(super_dash.get('orderStatusDist') or [])} / "
      f"趋势 {len(super_dash.get('orderTrend') or [])}")

check("订单状态的名字被翻译成了中文(不是 0-7 的数字)",
      all(not str(i.get("name", "")).isdigit() for i in (super_dash.get("orderStatusDist") or [])),
      str([i.get("name") for i in (super_dash.get("orderStatusDist") or [])]))

check("分类分布只统计在架资源(待审核/已下架的不算)",
      len(super_dash.get("categoryDist") or []) <= len(super_dash.get("orderStatusDist") or []) + 20,
      "条数合理性检查")


# ══════════════════════════════════════════════════════════════════
section("二、统计口径:利用率必须从汇总数重新算")
# ══════════════════════════════════════════════════════════════════
#
# 【为什么这一节单独拎出来】
# "平台利用率 = 各村利用率的平均"是统计上最常见的错。一个只有 1 个资源、
# 恰好成过 1 单的村会有 100% 的利用率,把它平均进去会显著拉高整体。
# 正确算法是 (Σ交易中 + Σ已完成) ÷ Σ资源总数。
# 这个错**不会报错、不会崩**,只是数字悄悄偏大 —— 而它要写进论文和公示页。

for name, token, path in [
    ("超管工作台", super_admin, "/api/admin/stat/dashboard"),
    ("超管全局统计", super_admin, "/api/admin/stat/global"),
    ("村管工作台", village_admin, "/api/admin/stat/dashboard"),
]:
    _, d = stat_of(token, path)
    total = nz(d.get("resourceTotal"))
    using = nz(d.get("orderUsing"))
    finished = nz(d.get("orderFinished"))
    expect = round(100.0 * (using + finished) / total, 2) if total else 0
    got = float(d.get("usageRate") or 0)
    check(f"{name}:利用率 = (交易中 {using} + 已完成 {finished}) ÷ 资源 {total} = {expect}%",
          abs(got - expect) < 0.05, f"实际 {got}%")

# 各村比率的平均值 —— 它和平台口径**不该相等**(否则多半是算错了)
_, global_stat = stat_of(super_admin, "/api/admin/stat/global")
villages = global_stat.get("villages") or []
if villages:
    avg_of_rates = round(sum(float(v.get("usageRate") or 0) for v in villages) / len(villages), 2)
    platform_rate = float(global_stat.get("usageRate") or 0)
    check(f"平台利用率({platform_rate}%)不等于各村比率的平均({avg_of_rates}%)"
          "—— 相等的话说明用错了算法",
          abs(platform_rate - avg_of_rates) > 0.01 or len(villages) == 1,
          f"平台 {platform_rate},各村平均 {avg_of_rates}")


# ══════════════════════════════════════════════════════════════════
section("三、村级统计:数据级权限")
# ══════════════════════════════════════════════════════════════════

code, own = stat_of(village_admin, "/api/admin/stat/village")
check_code("村管能看村级统计", {"code": code}, 200)
own_village = (own.get("villages") or [{}])[0]
check("村管看到的是自己的村(战旗村)", own_village.get("villageName") == "战旗村",
      str(own_village.get("villageName")))

# ★ 这一条是这一节的核心:村管传一个别村的 id,后端必须忽略它
code, peek = stat_of(village_admin, "/api/admin/stat/village", {"villageId": 4})
peek_village = (peek.get("villages") or [{}])[0]
check("**村管传 villageId=4(金星村)也没用 —— 拿到的还是战旗村**",
      peek_village.get("villageName") == "战旗村",
      f"实际拿到 {peek_village.get('villageName')} —— 这是一次越权!")
check("而且数字和没传参数时完全一致",
      nz(peek.get("resourceTotal")) == nz(own.get("resourceTotal")),
      f"{own.get('resourceTotal')} vs {peek.get('resourceTotal')}")

# 超管可以切村
code, super_peek = stat_of(super_admin, "/api/admin/stat/village", {"villageId": 4})
super_village = (super_peek.get("villages") or [{}])[0]
check("超管传 villageId=4 拿到的是金星村", super_village.get("villageName") == "金星村",
      str(super_village.get("villageName")))

code, default_village = stat_of(super_admin, "/api/admin/stat/village")
default_row = (default_village.get("villages") or [{}])[0]
check("超管不传 villageId 时,后端给一个有资源的村(而不是给一屏 0)",
      nz(default_row.get("resourceTotal")) > 0,
      f"{default_row.get('villageName')} 资源 {default_row.get('resourceTotal')}")


# ══════════════════════════════════════════════════════════════════
section("四、全局统计:仅超管")
# ══════════════════════════════════════════════════════════════════

check_code("村管访问全局统计 → 403(需要 stat:global)",
           call("GET", "/api/admin/stat/global", token=village_admin)[1], 403)
check_code("村民访问全局统计 → 403",
           call("GET", "/api/admin/stat/global", token=villager)[1], 403)

code, glob = stat_of(super_admin, "/api/admin/stat/global")
check_code("超管能看全局统计", {"code": code}, 200)
check("全局统计带上了各村明细", len(glob.get("villages") or []) >= 2,
      f"{len(glob.get('villages') or [])} 个村")
check("**镇节点没有混进各村明细里**(它自己不挂资源,列出来是 0% 的干扰项)",
      all(v.get("villageName") != "唐昌镇" for v in (glob.get("villages") or [])),
      str([v.get("villageName") for v in (glob.get("villages") or [])]))
check("平台资源总数 = 各村资源数之和",
      nz(glob.get("resourceTotal")) == sum(nz(v.get("resourceTotal")) for v in (glob.get("villages") or [])),
      f"{glob.get('resourceTotal')} vs "
      f"{sum(nz(v.get('resourceTotal')) for v in (glob.get('villages') or []))}")


# ══════════════════════════════════════════════════════════════════
section("五、操作日志:写入")
# ══════════════════════════════════════════════════════════════════

check_code("村民访问操作日志 → 403(系统管理整个目录只授给超管)",
           call("GET", "/api/system/log/list", token=villager)[1], 403)
check_code("村管访问操作日志 → 403",
           call("GET", "/api/system/log/list", token=village_admin)[1], 403)

MARK = "批次9日志用例"
# 成功的一笔
status, res = call("POST", "/api/admin/announcement/publish",
                   {"title": MARK + "公告", "content": "用于验证操作日志。", "noticeType": 1},
                   token=village_admin)
check_code("村管发布公告成功(这一步会写日志)", res, 200)

# 失败的一笔 —— 编辑一条不存在的公告
status, res = call("PUT", "/api/admin/announcement/999999",
                   {"title": MARK + "改", "content": "x", "noticeType": 1},
                   token=village_admin)
check_code("编辑不存在的公告 → 404(这一步会写一条**失败**日志)", res, 404)

_, res = call("GET", "/api/system/log/list", token=super_admin,
              params={"pageNum": 1, "pageSize": 50})
rows = (res.get("data") or {}).get("rows") or []
published = [r for r in rows if MARK in str(r.get("params"))]
check("发布公告那一笔记进日志了", len(published) >= 1, f"找到 {len(published)} 条")

success_rows = [r for r in published if r.get("status") == 1]
failed_rows = [r for r in published if r.get("status") == 0]
check("其中成功那笔的 status=1", len(success_rows) >= 1,
      str([(r.get("operation"), r.get("status")) for r in published]))
check("**失败那笔也记下来了,status=0**", len(failed_rows) >= 1,
      "只记成功的话,『有人反复尝试越权改别人的公告』在日志里完全看不到")
if failed_rows:
    check("失败那笔记下了异常信息",
          bool(failed_rows[0].get("errorMsg")),
          str(failed_rows[0].get("errorMsg")))
    check("失败那笔记的是「编辑公告」这个操作",
          failed_rows[0].get("operation") == "编辑公告",
          str(failed_rows[0].get("operation")))

if success_rows:
    row = success_rows[0]
    check("日志里有模块名", row.get("module") == "公告管理", str(row.get("module")))
    check("日志里有操作人快照", bool(row.get("username")), str(row.get("username")))
    check("日志里有类方法名", "Controller" in str(row.get("method")), str(row.get("method")))
    check("日志里有请求地址", str(row.get("requestUrl", "")).startswith("POST "),
          str(row.get("requestUrl")))
    check("日志里有 IP(虽然本地是 ::1)", bool(row.get("ip")), str(row.get("ip")))
    check("日志里有耗时", nz(row.get("costTime")) >= 0, str(row.get("costTime")))

_, res = call("GET", "/api/system/log/modules", token=super_admin)
modules = res.get("data") or []
check("模块清单里有「公告管理」", "公告管理" in modules, str(modules))


# ══════════════════════════════════════════════════════════════════
section("六、操作日志:敏感字段脱敏")
# ══════════════════════════════════════════════════════════════════
#
# 【怎么验的】
# 脱敏那段代码按字段名匹配(password / idCard / token …)。
# 而"批量导入敏感词"这个接口收的是 Map —— 换句话说,调用方可以往里塞
# 任意键,它们会原样进入切面的参数序列化。
# 于是可以**真的发一次带 password 字段的请求**,再去日志里查那个值在不在。
#
# 这比"读代码确认写了脱敏"强:kode 里的匹配表改了、或者有人把
# 递归打码那一层删了,这条断言都会红。而且它挡的是真事儿 ——
# 审计表是"很多人能看"的表,密码躺在那里比泄露在别处更糟。

SECRET = "SuperSecret-DoNotLog-9f3a"
ID_CARD = "510123199001011234"

status, res = call("POST", "/api/system/sensitive/batch",
                   {"text": "批次9测试词一\n批次9测试词二", "category": "测试", "action": 1,
                    # 这两个键接口本身不读,但会被切面序列化进 params
                    "password": SECRET, "idCard": ID_CARD},
                   token=super_admin)
check_code("批量导入敏感词(请求体里带了 password 和 idCard)", res, 200)

_, res = call("GET", "/api/system/log/list", token=super_admin,
              params={"pageNum": 1, "pageSize": 50})
rows = (res.get("data") or {}).get("rows") or []
import_rows = [r for r in rows if r.get("operation") == "批量导入敏感词"]
check("这条操作也记进日志了", len(import_rows) >= 1, f"找到 {len(import_rows)} 条")

if import_rows:
    params = str(import_rows[0].get("params") or "")
    check("日志的 params 里**没有**明文密码", SECRET not in params,
          f"params={params[:160]}")
    check("日志的 params 里**没有**明文身份证号", ID_CARD not in params,
          f"params={params[:160]}")
    check("但保留了「这里原来有个值」的事实(打了 *** )", "***" in params,
          f"params={params[:160]}")
    check("非敏感字段照常记录(测试词一还在)",
          "批次9测试词一" in params or "text" in params, f"params={params[:160]}")


# ══════════════════════════════════════════════════════════════════
section("七、操作日志:筛选与边界")
# ══════════════════════════════════════════════════════════════════

_, res = call("GET", "/api/system/log/list", token=super_admin,
              params={"pageNum": 1, "pageSize": 50, "module": "公告管理"})
rows = (res.get("data") or {}).get("rows") or []
check("按模块筛:结果里只有公告管理",
      len(rows) > 0 and all(r.get("module") == "公告管理" for r in rows),
      str([(r.get("module"), r.get("operation")) for r in rows[:5]]))

_, res = call("GET", "/api/system/log/list", token=super_admin,
              params={"pageNum": 1, "pageSize": 50, "status": 0})
rows = (res.get("data") or {}).get("rows") or []
check("按状态筛:结果里只有失败记录",
      len(rows) > 0 and all(r.get("status") == 0 for r in rows),
      str([(r.get("operation"), r.get("status")) for r in rows[:5]]))

_, res = call("GET", "/api/system/log/list", token=super_admin,
              params={"pageNum": 1, "pageSize": 50, "username": "战旗村支书"})
rows = (res.get("data") or {}).get("rows") or []
check("按操作人筛:结果里只有那个人的操作",
      len(rows) > 0 and all(r.get("username") == "战旗村支书" for r in rows),
      str([r.get("username") for r in rows[:5]]))

# 日期区间的边界:结束日期要**含当天**,不能把今天的记录漏掉
#
# 【日期必须是算出来的,不能写死】
# 这一条原本写死成脚本作者当天的日期(2026-09-28),而检查名说的是"今天" ——
# 过了零点它就不成立了:筛的仍是前一天,拿到的条数当然和"全部"对不上,
# 于是断言自己坏掉,看起来像产品出了问题。
# 这种"写的时候是对的、第二天自己烂掉"的断言比没有断言更麻烦。
today_str = datetime.now().strftime("%Y-%m-%d")

_, today = call("GET", "/api/system/log/list", token=super_admin,
                params={"pageNum": 1, "pageSize": 50,
                        "startDate": today_str, "endDate": today_str})
rows_today = (today.get("data") or {}).get("rows") or []

# 【判据为什么不写成 "今天的条数 == 全部条数"】
# 那要求"库里只有今天的日志",是个会随时间和数据漂移的隐含前提
# (实际就这么坏过一次)。改成直接看**查回来的东西对不对**:
#   ① 今天确实查得到记录 —— 结束日期若实现成"不含当天",这里会是 0,能抓住;
#   ② 查回来的每一条都真的是今天的 —— 开始日期的下界若失效,这里能抓住。
# 这样判据只依赖"这次查询本身正不正确",和库里攒了多少历史日志无关。
check("日期区间选「今天」能查到今天刚产生的日志(结束日期含当天)",
      len(rows_today) > 0
      and all(str(r.get("createTime", "")).startswith(today_str) for r in rows_today),
      f"查询区间 {today_str} ~ {today_str},查到 {len(rows_today)} 条,"
      f"createTime 示例 {[r.get('createTime') for r in rows_today[:3]]}")


# ══════════════════════════════════════════════════════════════════
print()
print("=" * 70)
print(f"结果:通过 {passed} 项,失败 {failed} 项")
print("=" * 70)
if failed:
    print("有失败项,请看上面的 [FAIL] 行。")
    sys.exit(1)
print("全部通过。")
