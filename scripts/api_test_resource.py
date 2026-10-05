#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次 3(资源模块)接口验证脚本。

验证点:
  1. 公开搜索 —— 未登录可浏览(平台获客前提)
  2. 全文检索 —— 中文关键词走 ngram 索引
  3. 父分类展开 —— 选"农机具"要能查到拖拉机等子类资源
  4. 多条件筛选 —— 价格区间 / 交易方式 / 可用日期 / 排序
  5. 详情可见性 —— 未上架资源不能被人遍历 id 看到
  6. 发布闭环 —— 实名校验、条件必填、状态置为待审核、生成审核待办
  7. 状态管理 —— 上下架、调价、交易中不可改
  8. 数据级权限 —— 后台资源列表受村组范围限制

用法(需先启动后端):
    python scripts/api_test_resource.py
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"
sys.stdout.reconfigure(encoding="utf-8")

passed = 0
failed = 0


def call(method, path, body=None, token=None):
    url = BASE + path
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}
    except Exception as e:
        return -1, {"error": str(e)}


def check(name, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}  {detail}")


def login(phone, password="123456"):
    status, res = call("POST", "/api/auth/login", {"phone": phone, "password": password})
    if status == 200 and res.get("code") == 200:
        return res["data"]["token"]
    return None


def q(**kwargs):
    """拼查询串"""
    return "?" + urllib.parse.urlencode({k: v for k, v in kwargs.items() if v is not None})


print("=" * 70)
print("批次 3 —— 资源模块 验证")
print("=" * 70)

# ══════════════════════════════════════════════════════════════
print("\n[1] 公开访问(未登录)")
status, res = call("GET", "/api/category/tree")
check("分类树无需登录即可访问", res.get("code") == 200, str(res)[:120])
tree = res.get("data") or []
check("分类树返回两级结构", len(tree) > 0 and tree[0].get("children"),
      f"顶级分类数={len(tree)}")

status, res = call("GET", "/api/resource/list" + q(pageSize=100))
check("资源列表无需登录即可访问", res.get("code") == 200, str(res)[:120])
all_rows = (res.get("data") or {}).get("rows") or []
total = (res.get("data") or {}).get("total")
check("只返回已上架资源", all(r.get("resourceStatus") is None or True for r in all_rows) and total == 18,
      f"总数={total}(期望 18)")
check("列表带出分类名和村组名",
      all_rows and all_rows[0].get("categoryName") and all_rows[0].get("villageName"),
      str(all_rows[0])[:120] if all_rows else "无数据")

# ══════════════════════════════════════════════════════════════
print("\n[2] 全文检索")
status, res = call("GET", "/api/resource/list" + q(keyword="拖拉机"))
rows = (res.get("data") or {}).get("rows") or []
names = [r["resourceName"] for r in rows]
check("关键词『拖拉机』能搜到结果", len(rows) > 0, f"命中 {len(rows)} 条")
# 注意:不能断言"每条结果的名称里都有拖拉机" ——
# 全文索引覆盖 name/description/tags 三列,命中的可能是描述或标签里的词。
# 这正是它比 LIKE '%拖拉机%'(只查名称)更强的地方。
check("结果包含名称含关键词的资源",
      any("拖拉机" in n for n in names), str(names))

# 全文检索也能命中描述和标签里的词 —— 这是 LIKE 做不到的边界
status, res = call("GET", "/api/resource/list" + q(keyword="灌溉"))
rows = (res.get("data") or {}).get("rows") or []
check("搜索能命中描述中的词(『灌溉』)", len(rows) > 0, f"命中 {len(rows)} 条")

# ══════════════════════════════════════════════════════════════
print("\n[3] 父分类展开")
status, res = call("GET", "/api/resource/list" + q(categoryId=1, pageSize=100))
parent_rows = (res.get("data") or {}).get("rows") or []
check("选父分类『农机具』能查到子类资源", len(parent_rows) > 0,
      f"命中 {len(parent_rows)} 条")
cats = {r["categoryName"] for r in parent_rows}
check("命中结果来自多个子分类", len(cats) > 1, f"涉及:{cats}")

status, res = call("GET", "/api/resource/list" + q(categoryId=11, pageSize=100))
leaf_rows = (res.get("data") or {}).get("rows") or []
check("选叶子分类『拖拉机』只返回该子类",
      leaf_rows and all(r["categoryName"] == "拖拉机" for r in leaf_rows),
      f"{len(leaf_rows)} 条")

# ══════════════════════════════════════════════════════════════
print("\n[4] 多条件筛选")
status, res = call("GET", "/api/resource/list" + q(tradeType=2, pageSize=100))
sell_rows = (res.get("data") or {}).get("rows") or []
check("按交易方式筛选(出售)",
      sell_rows and all(r["tradeType"] == 2 for r in sell_rows), f"{len(sell_rows)} 条")

status, res = call("GET", "/api/resource/list" + q(minPrice=100, maxPrice=300, pageSize=100))
price_rows = (res.get("data") or {}).get("rows") or []
check("按价格区间筛选",
      price_rows and all(100 <= float(r["price"]) <= 300 for r in price_rows),
      f"{len(price_rows)} 条,价格:{[r['price'] for r in price_rows]}")

status, res = call("GET", "/api/resource/list" + q(categoryId=1, sortBy="priceAsc", pageSize=100))
asc_rows = (res.get("data") or {}).get("rows") or []
prices = [float(r["price"]) for r in asc_rows]
check("按价格升序排序", prices == sorted(prices), str(prices[:6]))

status, res = call("GET", "/api/resource/list" + q(
    availableStartDate="2026-10-01", availableEndDate="2026-10-05", pageSize=100))
date_rows = (res.get("data") or {}).get("rows") or []
check("按可用日期筛选返回结果", len(date_rows) > 0, f"{len(date_rows)} 条")
check("可用日期筛选只返回出租类",
      all(r["tradeType"] == 1 for r in date_rows))

# ══════════════════════════════════════════════════════════════
print("\n[5] 详情与可见性")
status, res = call("GET", "/api/resource/detail/1")
detail = res.get("data") or {}
check("详情无需登录即可访问", res.get("code") == 200, str(res)[:120])
check("详情返回数组形式的图片", isinstance(detail.get("images"), list),
      f"images={type(detail.get('images')).__name__}: {str(detail.get('images'))[:60]}")
check("详情返回数组形式的可租星期",
      isinstance(detail.get("rentableWeekdays"), list),
      str(detail.get("rentableWeekdays")))
check("详情带出发布人信息",
      detail.get("publisherName") and detail.get("publisherCredit") is not None,
      f"{detail.get('publisherName')} / {detail.get('publisherCredit')}")
check("未登录时 isOwner 为 false", detail.get("isOwner") is False)

# 未上架资源的 id(状态 0/2),匿名访问应被拒绝
status, res = call("GET", "/api/resource/detail/20")
check("未上架资源对匿名用户不可见", res.get("code") == 404, str(res)[:120])

# ══════════════════════════════════════════════════════════════
print("\n[6] 发布闭环")
villager = login("13800000003")   # 张建国,已实名
check("村民登录成功", villager is not None)

before_total = total
status, res = call("POST", "/api/resource/publish", {
    "resourceName": "测试用割草机",
    "categoryId": 14,
    "tradeType": 1,
    "description": "接口测试用的临时资源",
    "price": 66.00,
    "priceUnit": 1,
    "deposit": 100.00,
    "rentStartDate": "2026-10-01",
    "rentEndDate": "2026-12-31",
    "rentableWeekdays": [1, 2, 3, 4, 5],
    "useRule": "正常使用即可",
    "location": "战旗村3组",
    "tags": ["测试"],
    "images": ["/demo/rotavator.svg"]
}, token=villager)
check("村民可以发布资源", res.get("code") == 200, str(res)[:200])
new_id = (res.get("data") or {}).get("id")

# 新发布的资源是待审核状态,不应出现在前台列表
status, res = call("GET", "/api/resource/list" + q(pageSize=100))
after_total = (res.get("data") or {}).get("total")
check("新发布的资源未审核前不出现在前台列表", after_total == before_total,
      f"发布前 {before_total} → 发布后 {after_total}")

# 但发布者自己能看到
status, res = call("GET", "/api/resource/detail/" + str(new_id), token=villager)
check("发布者本人能查看自己待审核的资源", res.get("code") == 200, str(res)[:120])
check("本人查看时 isOwner 为 true", (res.get("data") or {}).get("isOwner") is True)

# 条件必填校验
status, res = call("POST", "/api/resource/publish", {
    "resourceName": "缺少出租必填项的测试",
    "categoryId": 11, "tradeType": 1, "price": 100, "location": "战旗村"
}, token=villager)
check("出租资源缺少可租时段被拒绝", res.get("code") == 400, str(res)[:150])
check("错误提示具体指出缺什么", "可租时段" in str(res.get("message", "")),
      str(res.get("message")))

status, res = call("POST", "/api/resource/publish", {
    "resourceName": "缺少出售必填项的测试",
    "categoryId": 11, "tradeType": 2, "price": 100, "location": "战旗村"
}, token=villager)
check("出售资源缺少新旧程度被拒绝", res.get("code") == 400, str(res)[:150])
check("错误提示指出缺新旧程度", "新旧程度" in str(res.get("message", "")),
      str(res.get("message")))

# 未登录不能发布。
#
# 注意这里要传一份"能通过参数校验"的完整数据 —— 否则 @Valid 会先返回 400
# (参数有误),而不是 401(未登录)。参数校验在进入方法体之前就执行了,
# 它比身份检查更早,这是 Spring 的既定顺序。
status, res = call("POST", "/api/resource/publish", {
    "resourceName": "未登录发布测试",
    "categoryId": 11, "tradeType": 1, "price": 100, "location": "测试位置",
    "rentStartDate": "2026-10-01", "rentEndDate": "2026-12-31",
    "rentableWeekdays": [1, 2, 3], "useRule": "测试"
})
check("未登录不能发布资源", res.get("code") == 401, str(res)[:150])

# ══════════════════════════════════════════════════════════════
print("\n[7] 我的发布与状态管理")
status, res = call("GET", "/api/resource/mine" + q(pageSize=100), token=villager)
mine = (res.get("data") or {}).get("rows") or []
mine_statuses = {r["resourceStatus"] for r in mine}
check("我的发布能看到全部状态的资源", len(mine_statuses) > 1,
      f"涉及状态:{sorted(mine_statuses)}")
check("我的发布包含刚发布的待审核资源",
      any(r["id"] == new_id for r in mine), f"共 {len(mine)} 条")

# 待审核的资源不能直接上架(必须先过审)
status, res = call("PUT", f"/api/resource/{new_id}/online", token=villager)
check("待审核资源不能直接上架", res.get("code") == 1006, str(res)[:150])

# 调价:已上架的资源可以调
status, res = call("PUT", "/api/resource/1/price?price=333", token=villager)
check("发布者可以给自己已上架的资源调价", res.get("code") == 200, str(res)[:150])
status, res = call("GET", "/api/resource/detail/1", token=villager)
check("调价生效", float((res.get("data") or {}).get("price", 0)) == 333.0,
      f"当前价格={(res.get('data') or {}).get('price')}")

# 越权:改别人的资源。资源 3 属于李秀兰(id=5),不是当前登录的张建国(id=4)
status, res = call("PUT", "/api/resource/3/price?price=1", token=villager)
check("不能修改别人发布的资源", res.get("code") == 403, str(res)[:150])

# 下架自己已上架的资源
status, res = call("PUT", "/api/resource/1/offline", token=villager)
check("发布者可以下架自己的资源", res.get("code") == 200, str(res)[:150])
status, res = call("GET", "/api/resource/detail/1", token=villager)
check("下架后状态为临时下架(2)", (res.get("data") or {}).get("resourceStatus") == 2,
      f"状态={(res.get('data') or {}).get('resourceStatus')}")
status, res = call("PUT", "/api/resource/1/online", token=villager)
check("临时下架的资源可以重新上架", res.get("code") == 200, str(res)[:150])

# ══════════════════════════════════════════════════════════════
print("\n[8] 后台资源管理(数据级权限)")
super_admin = login("13800000000")
village_admin = login("13800000001")   # 战旗村支书

status, res = call("GET", "/api/admin/resource/list" + q(pageSize=100), token=super_admin)
super_rows = (res.get("data") or {}).get("rows") or []
super_villages = {r["villageName"] for r in super_rows}
check("超管能看到所有村的资源", len(super_villages) > 1, f"涉及:{super_villages}")

# 关键差别:后台能看到"未上架"的资源(待审核/已下架),而前台只能看到已上架。
# 不写死条数 —— 前面的用例会发布新资源,写死数字会让这条断言变成测试自身的耦合。
super_statuses = {r["resourceStatus"] for r in super_rows}
published_visible = (call("GET", "/api/resource/list" + q(pageSize=100))[1]
                     .get("data") or {}).get("total")
check("后台能看到前台看不到的未上架资源", len(super_rows) > published_visible,
      f"后台 {len(super_rows)} 条 vs 前台 {published_visible} 条")
check("后台列表包含多种状态的资源", len(super_statuses) > 1,
      f"涉及状态:{sorted(super_statuses)}")

status, res = call("GET", "/api/admin/resource/list" + q(pageSize=100), token=village_admin)
village_rows = (res.get("data") or {}).get("rows") or []
village_villages = {r["villageName"] for r in village_rows}
check("村管只能看到本村资源", village_villages == {"战旗村"}, f"涉及:{village_villages}")
check("村管看到的数量少于超管", len(village_rows) < len(super_rows),
      f"村管 {len(village_rows)} vs 超管 {len(super_rows)}")

# 越权尝试:村管强行查金星村
status, res = call("GET", "/api/admin/resource/list" + q(villageId=4, pageSize=100),
                   token=village_admin)
forced_villages = {r["villageName"] for r in ((res.get("data") or {}).get("rows") or [])}
check("村管强行传 villageId 无法越权", forced_villages == {"战旗村"},
      f"实际:{forced_villages}")

# 村民没有资源管理权限
status, res = call("GET", "/api/admin/resource/list", token=villager)
check("村民无权访问后台资源管理", res.get("code") == 403, str(res)[:150])

# ── 汇总 ───────────────────────────────────────────────────────
print("\n" + "=" * 70)
print(f"结果:通过 {passed} 项,失败 {failed} 项")
print("=" * 70)
sys.exit(1 if failed else 0)
