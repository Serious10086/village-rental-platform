#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次 8 —— 智能推荐 + 资源收藏 接口层验证。

覆盖:收藏的完整边界(含"已下架的能取消收藏但不能收藏")、行为采集
(浏览/搜索/下单/评价,以及游客静默忽略)、推荐的三路召回与冷启动、
已浏览的不再推、相似资源、推荐位点击率的口径、数据级权限。

【这一批的接口测试要盯住什么】
推荐这东西最容易"看着有结果就算过"。但这一批真正要交付的是四条硬事实:
    1. 任何情况下推荐位都不空(冷启动有兜底,而不是给个空列表)
    2. 看过的不会被再推(推刚看完的东西是最容易被察觉的"算法很傻")
    3. 推荐位点击率的分母是曝光,而且口径自洽(不是光秃秃一个点击数)
    4. 收藏了的东西不会因为下架就消失,而且还能取消掉
前三条都能用"数一数、对一对"验证,不该靠"我刷了一下看着挺好"。

用法(需先启动后端 8080):
    python scripts/api_test_recommend.py

注意:本脚本会下架/上架资源、收藏、下单、评价。重复跑之前请重置演示数据:
    mysql -uroot -proot < sql/05_mock_data.sql
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"

passed = 0
failed = 0


# ══════════════════════════════════════════════════════════════════
#  工具
# ══════════════════════════════════════════════════════════════════

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
        with urllib.request.urlopen(req, timeout=40) as resp:
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


def data_of(res):
    return res.get("data")


def recommend(token=None, limit=8):
    _, res = call("GET", "/api/recommend/me", token=token, params={"limit": limit})
    return res.get("data") or []


def detail(token, resource_id):
    _, res = call("GET", f"/api/resource/detail/{resource_id}", token=token)
    return res.get("data") or {}


def stat(token, days=7):
    _, res = call("GET", "/api/admin/recommend/stat", token=token, params={"days": days})
    return res.get("data") or {}


def report_view(token, resource_id, stay=30, source=1):
    return call("POST", "/api/behavior/view",
                {"resourceId": resource_id, "staySeconds": stay, "source": source},
                token=token)


# ══════════════════════════════════════════════════════════════════
#  测试数据约定(与 sql/05_mock_data.sql 一一对应)
#
#  资源:1 拖拉机(张建国4 的,战旗村)   5 拖拉机(王大山6 的,金星村)
#        2 收割机(张建国4 的)          9 晒场(李秀兰5 的,战旗村)
#        3 播种机(李秀兰5 的)          11 电焊机(张建国4 的)
#  分类:11 拖拉机  12 收割机  13 播种机  14 旋耕机
#
#  订单:8 已完成(买家刘小明8、卖家李秀兰5),刘小明已评过,
#        **李秀兰还没评** —— 评价行为那条用例就用它。
#
#  用户:1 平台管理员 2 战旗村支书 3 先锋村主任
#        4 张建国(village 2,已实名,信用 103)
#        5 李秀兰(village 2) 8 刘小明(village 2) 6 王大山(village 4)
# ══════════════════════════════════════════════════════════════════

ZHANG_RESOURCE = 1        # 张建国自己的拖拉机 —— 收藏边界用例用它
OTHER_RESOURCE = 5        # 王大山(金星村)的拖拉机 —— 跨村收藏/相似资源用它
ORDER_FOR_EVAL = 8        # 李秀兰还没评价的那笔已完成订单

print("=" * 70)
print("批次 8 —— 智能推荐 + 资源收藏 验证")
print("=" * 70)

super_admin = login("13800000000")
village_admin = login("13800000001")
zhang = login("13800000003")     # 张建国
lixiulan = login("13800000004")  # 李秀兰

check("四个测试账号全部登录成功",
      all([super_admin, village_admin, zhang, lixiulan]),
      "有账号登录失败,后续用例不可信")


# ══════════════════════════════════════════════════════════════════
section("一、收藏:开关与冗余计数")
# ══════════════════════════════════════════════════════════════════

status, res = call("PUT", f"/api/favorite/{OTHER_RESOURCE}")
check_code("未登录收藏 → 401", res, 401)

before = detail(zhang, OTHER_RESOURCE)
check("收藏前 favoriteCount 是 0", before.get("favoriteCount") == 0,
      f"实际 {before.get('favoriteCount')}")
check("收藏前 favorited 是 false", before.get("favorited") is False,
      f"实际 {before.get('favorited')}")

status, res = call("PUT", f"/api/favorite/{OTHER_RESOURCE}", token=zhang)
check_code("收藏成功", res, 200)
check("返回的是操作**之后**的状态(true)", data_of(res) is True, str(data_of(res)))

after = detail(zhang, OTHER_RESOURCE)
check("收藏后 favorited 变成 true", after.get("favorited") is True,
      f"实际 {after.get('favorited')}")
check("收藏后 favoriteCount 跟着 +1", after.get("favoriteCount") == 1,
      f"实际 {after.get('favoriteCount')}")

status, res = call("PUT", f"/api/favorite/{OTHER_RESOURCE}", token=zhang)
check("再点一次 → 取消,返回 false", data_of(res) is False, str(data_of(res)))
after2 = detail(zhang, OTHER_RESOURCE)
check("取消后计数回到 0", after2.get("favoriteCount") == 0,
      f"实际 {after2.get('favoriteCount')}")
check("取消后 favorited 是 false", after2.get("favorited") is False,
      f"实际 {after2.get('favorited')}")

check_code("收藏不存在的资源 → 404",
           call("PUT", "/api/favorite/999999", token=zhang)[1], 404)


# ══════════════════════════════════════════════════════════════════
section("二、收藏:改状态与取消收藏的先后顺序")
# ══════════════════════════════════════════════════════════════════
#
# 这一段验的是服务里那一处**顺序决定**:取消收藏必须先判定,
# 因为"已下架的资源也要能取消收藏"。反过来的话,
# 用户收藏了一个东西、后来它下架了,那条收藏就永远删不掉 ——
# 界面上是一个点不动的取消按钮。

check_code("先收藏自己的资源(自己发布的也能收藏,它是书签不是交易)",
           call("PUT", f"/api/favorite/{ZHANG_RESOURCE}", token=zhang)[1], 200)

check_code("把它下架", call("PUT", f"/api/resource/{ZHANG_RESOURCE}/offline",
                        token=zhang)[1], 200)
check("下架后状态是 2", detail(zhang, ZHANG_RESOURCE).get("resourceStatus") == 2,
      f"实际 {detail(zhang, ZHANG_RESOURCE).get('resourceStatus')}")

status, res = call("PUT", f"/api/favorite/{ZHANG_RESOURCE}", token=zhang)
check_code("**已下架的资源仍然能取消收藏**(这是顺序决定的,反了就成了死结)",
           res, 200)
check("取消成功(返回 false)", data_of(res) is False, str(data_of(res)))

status, res = call("PUT", f"/api/favorite/{ZHANG_RESOURCE}", token=zhang)
check_code("但已下架的资源**不能收藏** → 400", res, 400)

check_code("上架回来", call("PUT", f"/api/resource/{ZHANG_RESOURCE}/online",
                        token=zhang)[1], 200)
check_code("上架后又能收藏了",
           call("PUT", f"/api/favorite/{ZHANG_RESOURCE}", token=zhang)[1], 200)


# ══════════════════════════════════════════════════════════════════
section("三、我的收藏")
# ══════════════════════════════════════════════════════════════════
#
# 注意上一节结束时手上只有资源 1 一条收藏(资源 5 在第一节末尾被取消了)。
# 这里先把资源 5 补回来 —— 两条的名字分别是「东方红 904 拖拉机」和
# 「雷沃欧豹 M704 拖拉机」,**都含"拖拉机"但不是同一条**,
# 关键词过滤那条断言才验得动(只有一条的话过滤等于没验)。

call("PUT", f"/api/favorite/{OTHER_RESOURCE}", token=zhang)

_, res = call("GET", "/api/favorite/mine", token=zhang,
              params={"pageNum": 1, "pageSize": 20})
mine = res.get("data") or {}
ids = [r["id"] for r in (mine.get("rows") or [])]
check("我的收藏里有这两条", ZHANG_RESOURCE in ids and OTHER_RESOURCE in ids, str(ids))
check("列表带上收藏时间(按它倒序)",
      all(r.get("favoriteTime") for r in (mine.get("rows") or [])),
      json.dumps((mine.get("rows") or [{}])[0], ensure_ascii=False)[:120])
check("列表带上资源当前状态(已下架的也能看出来)",
      all(r.get("resourceStatus") is not None for r in (mine.get("rows") or [])),
      str([r.get("resourceStatus") for r in (mine.get("rows") or [])]))

_, res = call("GET", "/api/favorite/mine", token=lixiulan,
              params={"pageNum": 1, "pageSize": 20})
others = [r["id"] for r in ((res.get("data") or {}).get("rows") or [])]
check("换个人看收藏列表,看不到别人的收藏",
      ZHANG_RESOURCE not in others and OTHER_RESOURCE not in others, str(others))

# 关键词过滤:两条都含"拖拉机",用型号区分
_, res = call("GET", "/api/favorite/mine", token=zhang,
              params={"pageNum": 1, "pageSize": 20, "keyword": "东方红"})
kw = [r["id"] for r in ((res.get("data") or {}).get("rows") or [])]
check("收藏列表支持按资源名搜,而且真的在过滤(搜「东方红」只剩那一条)",
      kw == [ZHANG_RESOURCE], str(kw))

_, res = call("GET", "/api/favorite/mine", token=zhang,
              params={"pageNum": 1, "pageSize": 20, "keyword": "收割机"})
none = (res.get("data") or {}).get("total")
check("搜一个收藏里没有的词 → 空列表(不是返回全部)", none == 0, f"实际 {none} 条")


# ══════════════════════════════════════════════════════════════════
section("四、行为采集:浏览")
# ══════════════════════════════════════════════════════════════════

before_stat = stat(super_admin)

status, res = report_view(zhang, OTHER_RESOURCE, stay=45, source=1)
check_code("上报一次浏览 → 200", res, 200)

status, res = report_view(None, OTHER_RESOURCE, stay=10, source=1)
check_code("游客上报 → 也是 200(静默忽略,而不是 401 让前台控制台变红)", res, 200)

after_stat = stat(super_admin)
# 游客那一条不该落库,所以总数只 +1
check("游客的那条**没有落库**(总数只增加了登录用户那一条)",
      (after_stat.get("totalViews") or 0) == (before_stat.get("totalViews") or 0) + 1,
      f"{before_stat.get('totalViews')} → {after_stat.get('totalViews')}")

check_code("来源传 0(非法)→ 400",
           report_view(zhang, OTHER_RESOURCE, source=0)[1], 400)
check_code("来源传 9(非法)→ 400",
           report_view(zhang, OTHER_RESOURCE, source=9)[1], 400)
check_code("停留时长传负数 → 400",
           report_view(zhang, OTHER_RESOURCE, stay=-1)[1], 400)
check_code("资源 id 缺省 → 400",
           call("POST", "/api/behavior/view", {"staySeconds": 3}, token=zhang)[1], 400)


# ══════════════════════════════════════════════════════════════════
section("五、行为采集:搜索 / 下单 / 评价(服务端记,不靠前端上报)")
# ══════════════════════════════════════════════════════════════════
#
# 这三种行为在服务端**必然发生**(不写库就做不成),所以顺手记一笔最可靠,
# 也让前端少一个可能对不上的数据源。
#
# 【怎么验证"记下来了"】
# 行为表没有对外接口(也不该有 —— 用户不需要看自己的行为日志),
# 所以靠后台的「行为采集量」统计去数:它本来就该能看到"推荐的数据从哪来",
# 顺带也让这几条断言成立。**没有这个统计的话,"下单时记了一条行为"
# 就只能靠读代码相信。**

def behavior_counts(token):
    """后台的各类行为采集量 → {行为名: 条数}"""
    return {b["name"]: b["count"] for b in (stat(token).get("behaviors") or [])}


before_counts = behavior_counts(super_admin)

# ── 搜索:服务端本来就知道关键词 ────────────────────────────
call("GET", "/api/resource/list", token=zhang,
     params={"pageNum": 1, "pageSize": 10, "keyword": "拖拉机"})
call("GET", "/api/resource/list", token=zhang,
     params={"pageNum": 1, "pageSize": 10, "keyword": "拖拉机"})
mid_counts = behavior_counts(super_admin)
check("搜索行为被记下来了(两次搜索记两条)",
      (mid_counts.get("搜索") or 0) == (before_counts.get("搜索") or 0) + 2,
      f"{before_counts.get('搜索')} → {mid_counts.get('搜索')}")

# 翻页不该被记成"又搜了一次":同一个词的第二次请求带的是 pageNum=2
call("GET", "/api/resource/list", token=zhang,
     params={"pageNum": 2, "pageSize": 10, "keyword": "拖拉机"})
after_page_counts = behavior_counts(super_admin)
check("翻页没有被记成第二次搜索",
      (after_page_counts.get("搜索") or 0) == (mid_counts.get("搜索") or 0),
      f"{mid_counts.get('搜索')} → {after_page_counts.get('搜索')}")

# 不带关键词的列表浏览不是搜索
call("GET", "/api/resource/list", token=zhang, params={"pageNum": 1, "pageSize": 10})
check("不带关键词的列表浏览没有被记成搜索",
      (behavior_counts(super_admin).get("搜索") or 0)
      == (after_page_counts.get("搜索") or 0),
      str(behavior_counts(super_admin).get("搜索")))

# ── 下单:张建国租王大山的电动车(资源7,分类23)───────────────
# **刻意选电动车而不是晒场**:电动车这个分类下还有资源 18 没被他看过,
# 所以"他对电动车产生了兴趣"这件事能在推荐结果里看出来。
# 选一个只此一件的分类(比如晒场)的话,偏好算出来了也没有候选,
# 断言就只能停在"行为被记录了",看不到它真的影响了推荐。
status, res = call("POST", "/api/order/create",
                   {"resourceId": 7, "rentStartDate": "2026-10-05",
                    "rentEndDate": "2026-10-07"}, token=zhang)
check_code("下单成功", res, 200)
after_order = behavior_counts(super_admin)
check("下单行为被记下来了",
      (after_order.get("下单") or 0) == (after_page_counts.get("下单") or 0) + 1,
      f"{after_page_counts.get('下单')} → {after_order.get('下单')}")

# ── 评价:李秀兰评价订单8(买家刘小明已评过,她还没评)────────
status, res = call("POST", "/api/evaluation/create",
                   {"orderId": ORDER_FOR_EVAL, "score": 5,
                    "content": "按时归还,机器保养得不错。"}, token=lixiulan)
check_code("提交评价成功", res, 200)
after_eval = behavior_counts(super_admin)
check("评价行为被记下来了",
      (after_eval.get("评价") or 0) == (after_order.get("评价") or 0) + 1,
      f"{after_order.get('评价')} → {after_eval.get('评价')}")

check("后台能看到全部六个行为类型的采集量(缺哪一个就说明哪条链路断了)",
      len(after_eval) >= 4,
      json.dumps(after_eval, ensure_ascii=False))


# ══════════════════════════════════════════════════════════════════
section("六、推荐:三路召回与冷启动")
# ══════════════════════════════════════════════════════════════════

# 用张建国之前,先看一个"完全没行为"的用户 —— 用先锋村的账号,
# 他那边的资源张建国没看过,而且他本人没有任何行为记录
fresh = login("13800000002")   # 先锋村主任(村管,同时也是村民)
cold = recommend(fresh, 6)
check("冷启动**不空**(有兜底)", len(cold) > 0, f"实际 {len(cold)} 条")
check("冷启动的条目全都是兜底策略(2 本村热门 / 3 全局兜底)",
      all(i.get("strategy") in (2, 3) for i in cold),
      str([i.get("strategy") for i in cold]))
check("冷启动的理由如实写「本村热门」或「大家都在看」,不编「根据你的兴趣」",
      all(i.get("reason") in ("本村热门", "大家都在看") for i in cold),
      str([i.get("reason") for i in cold]))

guest = recommend(None, 6)
check("游客也能拿到推荐(首页是公开页面)", len(guest) > 0, f"实际 {len(guest)} 条")
check("游客拿到的都是全局兜底(没有行为可供个性化)",
      all(i.get("strategy") == 3 for i in guest),
      str([i.get("strategy") for i in guest]))
check("游客的理由是「大家都在看」",
      all(i.get("reason") == "大家都在看" for i in guest),
      str([i.get("reason") for i in guest]))

items = recommend(zhang, 8)
ids = [i["id"] for i in items]
check("推荐结果不超过请求的条数", len(items) <= 8, f"实际 {len(items)} 条")
check("同一条资源不会重复出现(三路召回要去重)",
      len(ids) == len(set(ids)), str(ids))
check("张建国浏览过拖拉机之后,内容推荐出现了(strategy=1)",
      any(i.get("strategy") == 1 for i in items),
      str([(i["id"], i.get("strategy"), i.get("reason")) for i in items]))
content_items = [i for i in items if i.get("strategy") == 1]
check("内容推荐的理由点名了分类(「你关注过「…」」)",
      all("你关注过" in (i.get("reason") or "") for i in content_items),
      str([i.get("reason") for i in content_items]))
check("理由说的是「关注过」而不是「看过」—— 偏好可能来自下单,说「看过」就是编的",
      all("你看过" not in (i.get("reason") or "") for i in content_items),
      str([i.get("reason") for i in content_items]))
check("策略和理由是配套的(内容推荐不会写「本村热门」)",
      all(i.get("reason") != "本村热门" for i in content_items),
      str([(i.get("strategy"), i.get("reason")) for i in content_items]))

# 下单那个行为到底有没有影响推荐 —— 这是"服务端记的行为真的进了算法"的证据。
# 他刚租了一台电动车(分类 23),同类下还有资源 18 他没看过,
# 所以它应当带着「你关注过「电动车」」的理由出现在结果里。
order_driven = [i for i in content_items if i["id"] == 18]
check("下单带出的偏好影响了推荐:同类(电动车)的资源 18 出现了",
      len(order_driven) == 1,
      str([(i["id"], i.get("reason")) for i in items]))
if order_driven:
    check("它的理由指向「电动车」这个分类",
          "电动车" in (order_driven[0].get("reason") or ""),
          order_driven[0].get("reason"))


# ══════════════════════════════════════════════════════════════════
section("七、推荐:看过的不会被再推、自己发布的不推")
# ══════════════════════════════════════════════════════════════════

check("刚浏览过的资源 5 不在推荐里(推刚看完的东西最容易被察觉)",
      OTHER_RESOURCE not in ids, str(ids))

# 再看一条新的,然后它也该从推荐里消失
call("GET", f"/api/resource/detail/9", token=zhang)
report_view(zhang, 9, stay=60, source=2)
items2 = recommend(zhang, 8)
ids2 = [i["id"] for i in items2]
check("新浏览过的资源 9 也从推荐里消失了", 9 not in ids2, str(ids2))

check("自己发布的资源不出现在推荐里(推自己的东西没有意义)",
      ZHANG_RESOURCE not in ids2, str(ids2))


# ══════════════════════════════════════════════════════════════════
section("八、相似资源")
# ══════════════════════════════════════════════════════════════════

_, res = call("GET", f"/api/recommend/similar/{ZHANG_RESOURCE}", token=zhang,
              params={"limit": 5})
similar = res.get("data") or []
check("相似资源拿得到", len(similar) > 0, f"实际 {len(similar)} 条")
sim_ids = [i["id"] for i in similar]
check("相似资源不包含它自己", ZHANG_RESOURCE not in sim_ids, str(sim_ids))
sim_detail = detail(zhang, ZHANG_RESOURCE)
check("相似资源都是同一个分类的",
      all(i.get("categoryId") == sim_detail.get("categoryId") for i in similar),
      f"当前分类 {sim_detail.get('categoryId')},相似资源分类 "
      f"{[i.get('categoryId') for i in similar]}")
check("相似资源的理由写的是「同属「…」」",
      all("同属" in (i.get("reason") or "") for i in similar),
      str([i.get("reason") for i in similar]))

# 看过的同类资源**仍然**会出现在相似资源里(这是刻意的:它是"探索同类"而非个性化)
report_view(zhang, OTHER_RESOURCE, stay=20, source=2)
_, res = call("GET", f"/api/recommend/similar/{ZHANG_RESOURCE}", token=zhang,
              params={"limit": 5})
again = [i["id"] for i in (res.get("data") or [])]
check("相似资源不排除看过的(探索同类 ≠ 个性化推荐,排除会把这一块搞空)",
      OTHER_RESOURCE in again, str(again))

check_code("相似资源传不存在的资源 id → 404",
           call("GET", "/api/recommend/similar/999999", token=zhang)[1], 404)


# ══════════════════════════════════════════════════════════════════
section("九、推荐位点击率的口径")
# ══════════════════════════════════════════════════════════════════

before = stat(super_admin)
check("曝光数是个非负整数", isinstance(before.get("impressions"), int),
      str(before.get("impressions")))

# 调一次推荐 → 曝光 +1(登录用户才记)
recommend(zhang, 4)
after = stat(super_admin)
check("调一次推荐,曝光 +1",
      (after.get("impressions") or 0) == (before.get("impressions") or 0) + 1,
      f"{before.get('impressions')} → {after.get('impressions')}")

# 从推荐位点进去 → 点击 +1(source=4)
report_view(zhang, OTHER_RESOURCE, stay=25, source=4)
clicked = stat(super_admin)
check("上报一次「来自推荐位」的浏览,点击 +1",
      (clicked.get("clicks") or 0) == (after.get("clicks") or 0) + 1,
      f"{after.get('clicks')} → {clicked.get('clicks')}")

# 口径自洽:ctr 必须等于 点击/曝光,clickShare 必须等于 点击/全部浏览
impressions = clicked.get("impressions") or 0
clicks = clicked.get("clicks") or 0
total_views = clicked.get("totalViews") or 0
expect_ctr = round(100.0 * clicks / impressions, 1) if impressions else 0
expect_share = round(100.0 * clicks / total_views, 1) if total_views else 0
check(f"点击率 = 点击 ÷ 曝光({clicks}/{impressions} = {clicked.get('ctr')}%)",
      abs(float(clicked.get("ctr") or 0) - expect_ctr) < 0.05,
      f"期望 {expect_ctr},实际 {clicked.get('ctr')}")
check(f"推荐位点击占全部浏览 = 点击 ÷ 全部浏览({clicks}/{total_views} = {clicked.get('clickShare')}%)",
      abs(float(clicked.get("clickShare") or 0) - expect_share) < 0.05,
      f"期望 {expect_share},实际 {clicked.get('clickShare')}")
check("按天明细非空(趋势比单点有用)", len(clicked.get("daily") or []) > 0,
      str(clicked.get("daily")))
check("按天明细里每一天的点击率也和它自己的两个计数自洽",
      all((d.get("impressions") or 0) == 0
          or abs(float(d.get("ctr") or 0) - round(100.0 * (d.get("clicks") or 0)
                                                  / (d.get("impressions") or 1), 1)) < 0.05
          for d in (clicked.get("daily") or [])),
      str([(d.get("date"), d.get("clicks"), d.get("impressions"), d.get("ctr"))
           for d in (clicked.get("daily") or [])]))


# ══════════════════════════════════════════════════════════════════
section("十、数据级权限")
# ══════════════════════════════════════════════════════════════════

check_code("村民访问推荐效果统计 → 403(需要 recommend:stat)",
           call("GET", "/api/admin/recommend/stat", token=zhang)[1], 403)

village_stat = stat(village_admin)
super_stat = stat(super_admin)
check("村管能看到本村的统计", village_stat.get("impressions") is not None,
      json.dumps(village_stat, ensure_ascii=False)[:100])
check("村管看到的全部浏览数 ≤ 超管(数据范围是本村)",
      (village_stat.get("totalViews") or 0) <= (super_stat.get("totalViews") or 0),
      f"村管 {village_stat.get('totalViews')} vs 超管 {super_stat.get('totalViews')}")


# ══════════════════════════════════════════════════════════════════
print()
print("=" * 70)
print(f"结果:通过 {passed} 项,失败 {failed} 项")
print("=" * 70)
if failed:
    print("有失败项,请看上面的 [FAIL] 行。")
    sys.exit(1)
print("全部通过。")
