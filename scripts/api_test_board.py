#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次 6c(留言板 + 公告)接口验证脚本。

验证点:
   1. **公告的可见范围** —— 全平台(village_id IS NULL)人人可见;
      村级公告只有本村人看得见;草稿只有后台看得见
   2. **无权查看返回 404 而不是 403** —— 拿 403 当探针遍历 id 能数出
      别村发了几条公告;404 什么都不透露
   3. 浏览量自增且返回自增后的值;越权访问不给别人刷浏览量
   4. 公告发布 —— 「系统公告」只有超管能发;归属由服务端决定;
      敏感词只拦截不替换
   5. **留言板按村分开** —— 读可以跨村,写必须本村
   6. **游客可读、写操作 401** —— 白名单只放开了 list / replies 两个读接口
   7. 敏感词两级处理 —— 拦截类整条拒绝;替换类存原文、读时打码
   8. 点赞 toggle 与 **并发点赞下计数不虚高**(唯一约束顶掉重复插入)
   9. 删除的连带关系 —— 删主帖连带删回复;删回复要把父帖计数减回来
  10. 后台巡查的**数据级权限** —— 村管只看得到本村,删不了别村的
  11. 回复通知 —— 只发给被回复的人,自己回自己不打扰

【重要:本脚本会创建真实数据(公告、留言、回复、点赞)】
跑完请重新执行以下命令恢复干净的演示数据:
    mysql -uroot -proot < sql/05_mock_data.sql
重复运行本脚本前也必须先重置 —— 上一轮留下的留言会让
"新帖排在列表第一条""计数等于几"这类断言全部失真。

【断言口径】本项目所有接口的 HTTP 状态码基本都是 200,业务结果
(200/400/401/403/404/1010)在响应体的 code 字段里。所以判断"该不该被拒"
一律用 check_code(期望的是业务码),只有想知道"是不是连包都没解析"
才去看 HTTP 状态码。唯一的例外是前端直连的详情接口 —— 它按 HTTP 语义
返回 404,所以那几处写成 code 或 HTTP 二者其一即可。

用法(需先启动后端):
    python scripts/api_test_board.py
"""
import json
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

# 业务返回码(对应后端 common/result/ResultCode.java)
SENSITIVE_CONTENT = 1010

passed = 0
failed = 0


def call(method, path, body=None, token=None, params=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json;charset=UTF-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
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


def check_code(name, res, expect_code):
    """断言业务返回码。res 既可以是 call() 的整体返回值,也可以是解包后的响应体。"""
    body = res[1] if isinstance(res, tuple) else res
    actual = body.get("code")
    check(name, actual == expect_code,
          f"期望 code={expect_code},实际 {actual} / {body.get('message')}")


def login(phone, password="123456"):
    status, res = call("POST", "/api/auth/login", {"phone": phone, "password": password})
    if status == 200 and res.get("code") == 200:
        return res["data"]["token"]
    return None


def section(title):
    print()
    print("─" * 70)
    print(title)
    print("─" * 70)


def rows_of(res):
    """从响应体里取分页数据。注意 PageResult 的字段叫 rows,不是 records。"""
    return (res.get("data") or {}).get("rows") or []


def total_of(res):
    return (res.get("data") or {}).get("total") or 0


def ids_of(res):
    return sorted(r["id"] for r in rows_of(res))


def find_row(res, row_id):
    for r in rows_of(res):
        if r["id"] == row_id:
            return r
    return None


# ══════════════════════════════════════════════════════════════════
#  测试数据约定(与 sql/05_mock_data.sql 一一对应)
#
#  用户:1 平台管理员(超管, village 1)
#        2 战旗村支书(村管, village 2)  3 先锋村主任(村管, village 3)
#        4 张建国 5 李秀兰 7 赵春梅 8 刘小明(village 2)
#        6 王大山(village 4)   9 陈晓芳 10 周伟(village 3)
#
#  公告:1 全平台·系统公告·置顶     2-5 战旗村(2)   6 先锋村(3)
#        7 战旗村·<b>草稿</b>(status=0)
#
#  留言:主帖 1-6 属战旗村(2)、7 属金星村(4)、8 属先锋村(3)
#        其中 5 命中替换类敏感词(is_filtered=1)
#             6 已被隐藏(status=2)
#        回复 9-15,parent_id 指回主帖
# ══════════════════════════════════════════════════════════════════

BOARD_V2_LATEST = 5      # 战旗村最新主帖(2026-09-23 09:20)
BOARD_V2_HIDDEN = 6      # 战旗村已隐藏主帖
BOARD_V2_FILTERED = 5    # 战旗村命中敏感词的主帖
BOARD_V4_ONLY = 7        # 金星村唯一主帖(王大山发)
BOARD_V3 = 8             # 先锋村主帖(陈晓芳发)
BOARD_WITH_TWO_REPLIES = 1  # 战旗村主帖①,下面有 2 条回复

print("=" * 70)
print("批次 6c —— 留言板 + 公告 验证")
print("=" * 70)

super_admin = login("13800000000")     # 平台管理员,village 1
village_admin = login("13800000001")   # 战旗村支书,village 2
other_admin = login("13800000002")     # 先锋村主任,village 3
villager = login("13800000003")        # 张建国,village 2
villager2 = login("13800000004")       # 李秀兰,village 2
outsider = login("13800000007")        # 王大山,village 4
zhao = login("13800000009")            # 赵春梅,village 2
liu = login("13800000005")             # 刘小明,village 2

check("八个测试账号全部登录成功",
      all([super_admin, village_admin, other_admin, villager, villager2,
           outsider, zhao, liu]),
      "有账号登录失败,后续用例不可信")


# ══════════════════════════════════════════════════════════════════
section("一、公告:可见范围(前台公开读)")
# ══════════════════════════════════════════════════════════════════

# 访客没有村组,只能看到全平台公告 —— 这是"访客应得的可见范围"
# 在后端由 villageId=null 让 SQL 的 OR 条件自然退化成 village_id IS NULL,
# 不需要为未登录单独写一条分支
status, res = call("GET", "/api/announcement/list", params={"pageNum": 1, "pageSize": 20})
check("访客能打开公告列表(白名单放开了读)", status == 200, f"实际 HTTP {status}")
check("访客只看到 1 条全平台公告", total_of(res) == 1, f"实际 {total_of(res)} 条")
check("访客看到的就是全平台那条(id=1)", ids_of(res) == [1], f"实际 {ids_of(res)}")

# 战旗村用户:全平台 1 条 + 本村 4 条(草稿不算)
status, res = call("GET", "/api/announcement/list", token=villager,
                   params={"pageNum": 1, "pageSize": 20})
check("战旗村用户看到 5 条公告(全平台 1 + 本村 4)",
      total_of(res) == 5, f"实际 {total_of(res)} 条,{ids_of(res)}")
check("草稿(id=7)对前台不可见 —— status 由 SQL 硬编码成 1",
      7 not in ids_of(res), f"实际 {ids_of(res)}")
check("先锋村的公告(id=6)不在战旗村用户的列表里",
      6 not in ids_of(res), f"实际 {ids_of(res)}")
check("置顶的排最前(is_top DESC, publish_time DESC)",
      rows_of(res)[0]["id"] == 2 and rows_of(res)[0]["isTop"] == 1,
      f"实际首条 id={rows_of(res)[0]['id'] if rows_of(res) else None}")

# 类型与关键词筛选,两种口径都要能叠在可见范围之上
status, res = call("GET", "/api/announcement/list", token=villager,
                   params={"pageNum": 1, "pageSize": 20, "noticeType": 3})
check("按类型筛选:农机补贴政策只有 1 条",
      ids_of(res) == [4], f"实际 {ids_of(res)}")
status, res = call("GET", "/api/announcement/list", token=villager,
                   params={"pageNum": 1, "pageSize": 20, "keyword": "补贴"})
check("按标题关键词筛选生效", ids_of(res) == [4], f"实际 {ids_of(res)}")


# ══════════════════════════════════════════════════════════════════
section("二、公告详情:404 而不是 403,以及浏览量")
# ══════════════════════════════════════════════════════════════════

status, res = call("GET", "/api/announcement/detail/1")
check("访客能读全平台公告详情", res.get("code") == 200, f"{res.get('message')}")
check("详情返回了正文", bool((res.get("data") or {}).get("content")),
      "content 为空")
check("返回的浏览量是自增后的值(演示数据 486,这是第一次读,应为 487)",
      (res.get("data") or {}).get("viewCount") == 487,
      f"实际 {(res.get('data') or {}).get('viewCount')} —— 不是 487 多半是"
      "演示库没重置干净,先跑一遍 sql/05_mock_data.sql 再重跑本脚本")

# 越权读别村公告必须是 404。用 403 等于告诉对方"这条公告存在,只是你看不了",
# 拿它当探针遍历 id 就能数出别村发了多少条
status, res = call("GET", "/api/announcement/detail/2")
check("访客读战旗村公告 → 404(不是 403)",
      status == 404 or res.get("code") == 404, f"实际 HTTP {status} / code={res.get('code')}")
status, res = call("GET", "/api/announcement/detail/2", token=outsider)
check("金星村用户读战旗村公告 → 404",
      status == 404 or res.get("code") == 404, f"实际 HTTP {status} / code={res.get('code')}")

status, res = call("GET", "/api/announcement/detail/2", token=villager)
check("战旗村用户读本村公告 → 200", res.get("code") == 200, f"{res.get('message')}")

status, res = call("GET", "/api/announcement/detail/7", token=villager)
check("草稿的详情 → 404(未发布的公告不能靠猜 id 读到)",
      status == 404 or res.get("code") == 404, f"实际 HTTP {status} / code={res.get('code')}")

status, res = call("GET", "/api/announcement/detail/99999")
check("不存在的公告 → 404",
      status == 404 or res.get("code") == 404, f"实际 HTTP {status} / code={res.get('code')}")

# 浏览量:自增必须发生在权限校验之后,否则每次失败的越权尝试
# 都会给别人的公告刷一个浏览量 —— 那是个白送的数字入口
_, res = call("GET", "/api/announcement/detail/2", token=villager)
before_vc = (res.get("data") or {}).get("viewCount")
for _ in range(3):
    call("GET", "/api/announcement/detail/2")   # 访客,越权 → 404
_, res = call("GET", "/api/announcement/detail/2", token=villager)
check("越权失败的访问不给别人的公告刷浏览量(3 次越权只等于 1 次正常访问)",
      (res.get("data") or {}).get("viewCount") == before_vc + 1,
      f"{before_vc} → {(res.get('data') or {}).get('viewCount')},期望 +1")


# ══════════════════════════════════════════════════════════════════
section("三、公告:后台列表的数据级权限")
# ══════════════════════════════════════════════════════════════════

check_code("未登录访问后台公告列表 → 401",
           call("GET", "/api/admin/announcement/list"), 401)

check_code("村民访问后台公告列表 → 403",
           call("GET", "/api/admin/announcement/list", token=villager), 403)

status, res = call("GET", "/api/admin/announcement/list", token=village_admin,
                   params={"pageNum": 1, "pageSize": 20})
check("村管看到本村 4 条 + 草稿 1 条 = 5 条",
      total_of(res) == 5, f"实际 {total_of(res)} 条,{ids_of(res)}")
check("草稿(id=7)在后台列表里看得到",
      7 in ids_of(res), f"实际 {ids_of(res)}")
# 全平台公告的 village_id 是 NULL,村管的村组白名单是 [2],
# IN (2) 匹配不上 NULL —— 所以村管的后台列表里没有全平台公告。
# 这是数据级权限的必然结果:村管不该去"管理"一条不属于任何村的公告
check("全平台公告(id=1)不在村管的后台列表里(它不属于任何村)",
      1 not in ids_of(res), f"实际 {ids_of(res)}")

status, res = call("GET", "/api/admin/announcement/list", token=village_admin,
                   params={"pageNum": 1, "pageSize": 20, "status": 0})
check("后台能按状态筛出草稿:村管 > 状态=草稿 → 1 条",
      ids_of(res) == [7], f"实际 {ids_of(res)}")

status, res = call("GET", "/api/admin/announcement/list", token=super_admin,
                   params={"pageNum": 1, "pageSize": 20})
check("超管(数据范围=全部)看到全部 7 条",
      total_of(res) == 7, f"实际 {total_of(res)} 条")

status, res = call("GET", "/api/admin/announcement/list", token=other_admin,
                   params={"pageNum": 1, "pageSize": 20})
check("先锋村村管只看到本村的 1 条(id=6)",
      ids_of(res) == [6], f"实际 {ids_of(res)}")


# ══════════════════════════════════════════════════════════════════
section("四、公告发布:归属由服务端决定,敏感词只拦截")
# ══════════════════════════════════════════════════════════════════

check_code("未登录发布公告 → 401",
           call("POST", "/api/admin/announcement/publish",
                {"title": "未登录发的公告", "content": "正文", "noticeType": 1}), 401)

check_code("村民发布公告 → 403(没有 announcement:add 权限)",
           call("POST", "/api/admin/announcement/publish",
                {"title": "村民发的公告", "content": "正文", "noticeType": 1},
                token=villager), 403)

status, res = call("POST", "/api/admin/announcement/publish",
                   {"title": "冒用平台的公告", "content": "正文", "noticeType": 5},
                   token=village_admin)
check_code("村管发「系统公告」→ 403「只能由平台管理员发布」", res, 403)

status, res = call("POST", "/api/admin/announcement/publish",
                   {"title": "涉及诈骗的公告", "content": "正文", "noticeType": 1},
                   token=village_admin)
check_code("含拦截类敏感词的标题被整条拒绝(1010)", res, SENSITIVE_CONTENT)

check_code("标题与正文为空 → 400(参数校验)",
           call("POST", "/api/admin/announcement/publish",
                {"title": "", "content": "", "noticeType": 1}, token=village_admin), 400)

# 正常发布:归属必须由服务端从登录态推出来
status, res = call("POST", "/api/admin/announcement/publish",
                   {"title": "批次6c接口测试公告", "content": "这是一条接口测试公告。",
                    "noticeType": 1, "isTop": 0},
                   token=village_admin)
check_code("村管发布村级公告成功", res, 200)
new_ann_id = res["data"]
_, res = call("GET", f"/api/announcement/detail/{new_ann_id}", token=village_admin)
check("新公告的 villageId 被服务端写成村管自己的村(2)",
      (res.get("data") or {}).get("villageId") == 2,
      f"实际 {res.get('data', {}).get('villageId')}")
check("新公告的 publisherId 是发布人自己(2)",
      (res.get("data") or {}).get("publisherId") == 2,
      f"实际 {res.get('data', {}).get('publisherId')}")
check("发布即生效:状态直接是「已发布」(1)",
      (res.get("data") or {}).get("status") == 1,
      f"实际 {res.get('data', {}).get('status')}")
check("发布时间已填写", bool((res.get("data") or {}).get("publishTime")))

status, res = call("POST", "/api/admin/announcement/publish",
                   {"title": "批次6c平台公告", "content": "全平台测试公告。", "noticeType": 5},
                   token=super_admin)
check_code("超管发布「系统公告」成功", res, 200)
_, res = call("GET", f"/api/announcement/detail/{res['data']}", token=super_admin)
check("超管发的公告 village_id 为 NULL(全平台)",
      (res.get("data") or {}).get("villageId") is None,
      f"实际 {res.get('data', {}).get('villageId')}")


# ══════════════════════════════════════════════════════════════════
section("五、留言板:游客可读、写操作要登录")
# ══════════════════════════════════════════════════════════════════

status, res = call("GET", "/api/board/list", params={"pageNum": 1, "pageSize": 20})
check_code("访客不指定村组 → 列表要求先选村组", res, 400)

status, res = call("GET", "/api/board/list",
                   params={"pageNum": 1, "pageSize": 20, "villageId": 2})
check("访客指定村组后能读战旗村留言板", res.get("code") == 200, f"{res.get('message')}")
check("战旗村前台可见 5 条主帖(已隐藏的那条不算)",
      total_of(res) == 5, f"实际 {total_of(res)} 条")
check("已隐藏的主帖(id=6)不在前台列表里",
      BOARD_V2_HIDDEN not in ids_of(res), f"实际 {ids_of(res)}")
check("最新的一条排在最前(create_time DESC)",
      rows_of(res)[0]["id"] == BOARD_V2_LATEST,
      f"实际首条 id={rows_of(res)[0]['id'] if rows_of(res) else None}")
check("列表里没有回复(parent_id IS NULL)",
      all(r.get("parentId") is None for r in rows_of(res)))
check("访客的 liked 全是 false(没登录就没有'我点没点过')",
      all(r.get("liked") is False for r in rows_of(res)))

status, res = call("GET", "/api/board/list",
                   params={"pageNum": 1, "pageSize": 20, "villageId": 4})
check("金星村的留言板是独立的一份(id=7)", ids_of(res) == [BOARD_V4_ONLY],
      f"实际 {ids_of(res)}")

# 读可以跨村:战旗村的人想看先锋村在聊什么,这是正常的
status, res = call("GET", "/api/board/list", token=villager,
                   params={"pageNum": 1, "pageSize": 20, "villageId": 3})
check("战旗村用户可以读先锋村的留言板(读可以跨村)",
      ids_of(res) == [BOARD_V3], f"实际 {ids_of(res)}")

# 回复列表
status, res = call("GET", "/api/board/replies",
                   params={"parentId": BOARD_WITH_TWO_REPLIES, "pageNum": 1, "pageSize": 10})
check("主帖①下有 2 条回复", len(rows_of(res)) == 2, f"实际 {len(rows_of(res))}")
check("回复按时间正序(create_time ASC)",
      [r["id"] for r in rows_of(res)] == [9, 10],
      f"实际 {[r['id'] for r in rows_of(res)]}")
check("回复都带着 parentId",
      all(r.get("parentId") == BOARD_WITH_TWO_REPLIES for r in rows_of(res)))

status, res = call("GET", "/api/board/replies",
                   params={"parentId": BOARD_V2_HIDDEN, "pageNum": 1, "pageSize": 10})
check("已隐藏主帖的回复 → 404(否则知道 id 就能读到隐藏帖的回复)",
      status == 404 or res.get("code") == 404, f"实际 HTTP {status} / code={res.get('code')}")

status, res = call("GET", "/api/board/replies",
                   params={"parentId": 99999, "pageNum": 1, "pageSize": 10})
check("不存在的主帖的回复 → 404",
      status == 404 or res.get("code") == 404, f"实际 HTTP {status} / code={res.get('code')}")

# 白名单只放开了 list 和 replies,写操作一律要登录
check_code("未登录发帖 → 401",
           call("POST", "/api/board/post", {"content": "游客发的帖"}), 401)
check_code("未登录回复 → 401",
           call("POST", "/api/board/reply", {"parentId": 1, "content": "游客的回复"}), 401)
check_code("未登录点赞 → 401", call("POST", "/api/board/1/like"), 401)
check_code("未登录删帖 → 401", call("DELETE", "/api/board/1"), 401)


# ══════════════════════════════════════════════════════════════════
section("六、后台留言巡查的只读口径与数据级权限")
# ══════════════════════════════════════════════════════════════════
#
# 这一段刻意排在所有写操作之前。下面的用例会新建、删除留言,
# 那之后再校验"总数应该是几",就变成了一道算术题而不是在验证后端。

check_code("未登录访问后台留言列表 → 401", call("GET", "/api/admin/board/list"), 401)
check_code("村民访问后台留言列表 → 403",
           call("GET", "/api/admin/board/list", token=villager), 403)

# 战旗村在演示数据里是 6 条主帖 + 6 条回复 = 12 条。
# 第 7 条主帖属于金星村(4)、第 8 条属于先锋村(3),都不该出现在这里
status, res = call("GET", "/api/admin/board/list", token=village_admin,
                   params={"pageNum": 1, "pageSize": 50})
admin_ids = set(ids_of(res))
check("村管看到本村 12 条(主帖 1-6 + 回复 9-14)",
      admin_ids == {1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14},
      f"实际 {sorted(admin_ids)}")
check("已隐藏的主帖 id=6 在后台看得到(隐藏是'不展示',不是'删除')",
      6 in admin_ids, f"实际 {sorted(admin_ids)}")
check("金星村的 id=7 不在村管的后台列表里",
      7 not in admin_ids, f"实际 {sorted(admin_ids)}")
check("后台列表里既有主帖也有回复(回复同样可能违规,巡查不能只看主帖)",
      any(r.get("parentId") is not None for r in rows_of(res)),
      "一条回复都没有,说明后台巡查漏了回复")

status, res = call("GET", "/api/admin/board/list", token=village_admin,
                   params={"pageNum": 1, "pageSize": 50, "onlyRoot": "true"})
check("只看主帖 → 6 条",
      all(r.get("parentId") is None for r in rows_of(res)) and len(rows_of(res)) == 6,
      f"实际 {len(rows_of(res))} 条")

status, res = call("GET", "/api/admin/board/list", token=village_admin,
                   params={"pageNum": 1, "pageSize": 50, "onlyFiltered": "true"})
check("只看命中敏感词 → 主帖 5 和主帖 6 两条",
      set(ids_of(res)) == {5, 6}, f"实际 {ids_of(res)}")

status, res = call("GET", "/api/admin/board/list", token=village_admin,
                   params={"pageNum": 1, "pageSize": 50, "status": 2})
check("按状态筛已隐藏 → 只有 id=6", ids_of(res) == [6], f"实际 {ids_of(res)}")

status, res = call("GET", "/api/admin/board/list", token=other_admin,
                   params={"pageNum": 1, "pageSize": 50})
check("先锋村村管看到 2 条(主帖 8 + 它的那条回复 15)",
      set(ids_of(res)) == {8, 15}, f"实际 {ids_of(res)}")

status, res = call("GET", "/api/admin/board/list", token=super_admin,
                   params={"pageNum": 1, "pageSize": 50})
check("超管(数据范围=全部)看到全部 15 条(8 条主帖 + 7 条回复)",
      total_of(res) == 15, f"实际 {total_of(res)} 条")


# ══════════════════════════════════════════════════════════════════
section("七、留言板:敏感词的两级处理")
# ══════════════════════════════════════════════════════════════════

# 替换类(加微信/私聊我):库里存原文,读的时候打码
status, res = call("GET", "/api/board/list",
                   params={"pageNum": 1, "pageSize": 20, "villageId": 2})
row = find_row(res, BOARD_V2_FILTERED)
check("命中替换类敏感词的主帖依然可见(不是被拦掉)",
      row is not None, "列表里找不到 id=5")
check("该帖带上了 isFiltered=1 的标记", row and row.get("isFiltered") == 1,
      f"实际 {row.get('isFiltered') if row else None}")
check("读回来的是打码后的内容(前台看不到「加微信」)",
      row and "加微信" not in row.get("content", ""),
      f"实际内容:{row.get('content') if row else None}")
check("打码留下了星号痕迹", row and "*" in row.get("content", ""),
      f"实际内容:{row.get('content') if row else None}")

# 拦截类(诈骗/赌博):整条拒绝并告诉用户命中了哪个词
status, res = call("POST", "/api/board/post", {"content": "专业办证,顺便搞点诈骗业务"},
                   token=villager)
check_code("发帖含拦截类敏感词 → 1010", res, SENSITIVE_CONTENT)
check("返回的提示里点出了命中的词",
      "诈骗" in (res.get("message") or ""), f"实际 {res.get('message')}")

status, res = call("POST", "/api/board/post", {"content": "有没有人一起赌博的"},
                   token=villager)
check_code("另一条拦截类词同样被拒", res, SENSITIVE_CONTENT)

# 替换类词发帖:成功,且 is_filtered 被标上
status, res = call("POST", "/api/board/post",
                   {"content": "批次6c测试:想聊的直接加微信私聊我"}, token=villager)
check_code("含替换类敏感词的发帖成功(不拦截)", res, 200)
filtered_id = res["data"]
_, res = call("GET", "/api/board/list",
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
row = find_row(res, filtered_id)
check("新帖被标成 isFiltered=1", row and row.get("isFiltered") == 1,
      f"实际 {row.get('isFiltered') if row else None}")
check("新帖读回来同样是打码的",
      row and "加微信" not in row.get("content", ""),
      f"实际内容:{row.get('content') if row else None}")
# 作者本人看到的也是打码版本 —— 同一个字段对不同人返回不同内容,
# 前端就得知道"我是作者",多一个会出错的分支
_, res = call("GET", "/api/board/list", token=villager,
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
row = find_row(res, filtered_id)
check("作者本人看到的也是打码版本(口径统一)",
      row and "加微信" not in row.get("content", ""),
      f"实际内容:{row.get('content') if row else None}")


# ══════════════════════════════════════════════════════════════════
section("八、留言板:写操作的村组约束")
# ══════════════════════════════════════════════════════════════════

# 发帖的村组只认登录态 —— BoardPostDTO 里根本没有 villageId 字段
status, res = call("POST", "/api/board/post",
                   {"content": "批次6c测试:金星村王大山发的帖"}, token=outsider)
check_code("金星村用户发帖成功", res, 200)
outsider_post = res["data"]
_, res = call("GET", "/api/board/list",
              params={"pageNum": 1, "pageSize": 20, "villageId": 4})
check("他的帖出现在金星村的列表里(不是战旗村)",
      outsider_post in ids_of(res), f"实际 {ids_of(res)}")
_, res = call("GET", "/api/board/list",
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
check("他的帖没有出现在战旗村的列表里(村组只认登录态)",
      outsider_post not in ids_of(res), f"实际 {ids_of(res)}")

status, res = call("POST", "/api/board/reply",
                   {"parentId": BOARD_V4_ONLY, "content": "跨村回复"}, token=villager)
check_code("跨村回复 → 403「只能回复本村留言板上的留言」", res, 403)

status, res = call("POST", f"/api/board/{BOARD_V4_ONLY}/like", token=villager)
check_code("跨村点赞 → 403「只能点赞本村留言板上的留言」", res, 403)

status, res = call("POST", f"/api/board/{BOARD_V2_HIDDEN}/like", token=villager)
check_code("点赞已隐藏的帖子 → 400", res, 400)

status, res = call("POST", "/api/board/reply",
                   {"parentId": BOARD_V2_HIDDEN, "content": "回复隐藏帖"}, token=villager)
check_code("回复已隐藏的帖子 → 400", res, 400)

status, res = call("POST", "/api/board/reply", {"parentId": 9, "content": "回复一条回复"},
                   token=villager)
check_code("回复一条回复 → 400(层级压到两级)", res, 400)

status, res = call("POST", "/api/board/reply", {"parentId": 99999, "content": "回复不存在的帖"},
                   token=villager)
check_code("回复不存在的主帖 → 404", res, 404)

check_code("内容为空 → 400(参数校验)",
           call("POST", "/api/board/post", {"content": ""}, token=villager), 400)

check_code("内容超过 1000 字 → 400",
           call("POST", "/api/board/post",
                {"content": "批次6c测试:" + "超长" * 600}, token=villager), 400)


# ══════════════════════════════════════════════════════════════════
section("九、留言板:回复与通知")
# ══════════════════════════════════════════════════════════════════

# 回复前先数一遍张建国的未读通知(主帖①是他的,李秀兰回复会通知他)
_, res = call("GET", "/api/notice/list", token=villager,
              params={"pageNum": 1, "pageSize": 50})
before_notices = len([r for r in rows_of(res) if r.get("noticeType") == 10])

status, res = call("POST", "/api/board/reply",
                   {"parentId": BOARD_WITH_TWO_REPLIES, "content": "批次6c测试:李秀兰的第三条回复"},
                   token=villager2)
check_code("同村回复成功", res, 200)
new_reply_id = res["data"]

_, res = call("GET", "/api/board/replies",
              params={"parentId": BOARD_WITH_TWO_REPLIES, "pageNum": 1, "pageSize": 10})
check("主帖①下的回复变成 3 条", len(rows_of(res)) == 3, f"实际 {len(rows_of(res))}")

_, res = call("GET", "/api/board/list",
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
row = find_row(res, BOARD_WITH_TWO_REPLIES)
check("主帖①的 reply_count 也从 2 变成了 3(冗余计数跟上)",
      row and row.get("replyCount") == 3, f"实际 {row.get('replyCount') if row else None}")

_, res = call("GET", "/api/notice/list", token=villager,
              params={"pageNum": 1, "pageSize": 50})
after_notices = [r for r in rows_of(res) if r.get("noticeType") == 10]
check("被回复的张建国收到了 1 条「留言互动」通知",
      len(after_notices) == before_notices + 1,
      f"回复前 {before_notices} 条,回复后 {len(after_notices)} 条")
notice = next((n for n in after_notices if n.get("bizId") == BOARD_WITH_TWO_REPLIES), None)
check("通知的 bizType=5 / bizId 指向那条主帖(前端据此跳 /board?postId=N)",
      notice is not None and notice.get("bizType") == 5,
      f"实际 {notice}")
check("通知正文里带了回复内容", notice and "批次6c测试" in (notice.get("content") or ""),
      f"实际 {notice.get('content') if notice else None}")

# 自己回自己不通知 —— 那是一句自言自语
before_self = len(after_notices)
status, res = call("POST", "/api/board/reply",
                   {"parentId": BOARD_WITH_TWO_REPLIES, "content": "批次6c测试:张建国自己回自己"},
                   token=villager)
check_code("回复自己的主帖成功", res, 200)
_, res = call("GET", "/api/notice/list", token=villager,
              params={"pageNum": 1, "pageSize": 50})
after_self = len([r for r in rows_of(res) if r.get("noticeType") == 10])
check("自己回自己不产生通知", after_self == before_self,
      f"自回前 {before_self} 条,自回后 {after_self} 条")


# ══════════════════════════════════════════════════════════════════
section("十、留言板:点赞与并发")
# ══════════════════════════════════════════════════════════════════

# 赵春梅发的主帖④(like_count 演示数据里是 0),拿来跑 toggle
TARGET = 4

_, res = call("GET", "/api/board/list",
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
row = find_row(res, TARGET)
base_like = row.get("likeCount")
base_liked = row.get("liked")
check("测试前主帖④的 like_count 与 liked 是干净的", base_like == 0 and base_liked is False,
      f"likeCount={base_like}, liked={base_liked}")

status, res = call("POST", f"/api/board/{TARGET}/like", token=villager)
check("首次点赞返回 true", res.get("code") == 200 and res.get("data") is True,
      f"实际 {res}")
_, res = call("GET", "/api/board/list", token=villager,
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
row = find_row(res, TARGET)
check("like_count 变成 1", row.get("likeCount") == 1, f"实际 {row.get('likeCount')}")
check("liked 变成 true(这个字段只有登录用户才有意义)",
      row.get("liked") is True, f"实际 {row.get('liked')}")
_, res = call("GET", "/api/board/list",
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
check("访客看同一条帖,liked 仍是 false", find_row(res, TARGET).get("liked") is False)

status, res = call("POST", f"/api/board/{TARGET}/like", token=villager)
check("再点一次(取消点赞)返回 false", res.get("data") is False, f"实际 {res}")
_, res = call("GET", "/api/board/list", token=villager,
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
row = find_row(res, TARGET)
check("like_count 回到 0", row.get("likeCount") == 0, f"实际 {row.get('likeCount')}")
check("liked 回到 false", row.get("liked") is False, f"实际 {row.get('liked')}")

# ── 并发之一:5 个不同的人同时点同一条帖 ──────────────────────────
#
# 这是"计数必须准确"的核心场景:并发点赞下 like_count 要正好等于点赞人数。
# 少了说明更新丢了(比如"读出来加一写回去"),多了说明重复自增了一次。
#
# 这一段最初是不过的:5 个请求里 2 个报"系统繁忙",计数只加到 3。
# 根因是外键检查在"插点赞行"那一刻给主帖行加了共享锁,而紧接着的"计数 +1"
# 要的是同一行的排他锁 —— 两个事务都攥着共享锁不肯放手、又都想升级,
# 环就闭合了。现在事务开头用 SELECT ... FOR UPDATE 先锁住主帖行,同一个帖上的
# 点赞依次排队,这两处失败都不该再出现。完整推导见 BizBoardMapper.xml 里
# selectByIdForUpdate 上方的注释。
_, res = call("POST", "/api/board/post",
              {"content": "批次6c测试:用来跑并发点赞的帖"}, token=villager)
CONCUR_POST = res["data"]

LIKE_USERS = [("张建国", villager), ("李秀兰", villager2),
              ("战旗村支书", village_admin), ("赵春梅", zhao), ("刘小明", liu)]
like_results = [None] * len(LIKE_USERS)
barrier = threading.Barrier(len(LIKE_USERS))


def like_as(idx, token):
    try:
        barrier.wait(timeout=10)
    except Exception:
        pass
    like_results[idx] = call("POST", f"/api/board/{CONCUR_POST}/like", token=token)


threads = [threading.Thread(target=like_as, args=(i, tk))
           for i, (_, tk) in enumerate(LIKE_USERS)]
for t in threads:
    t.start()
for t in threads:
    t.join()

failed_likes = [(LIKE_USERS[i][0], like_results[i][1].get("message"))
                for i in range(len(LIKE_USERS))
                if like_results[i] and like_results[i][1].get("code") != 200]
check(f"{len(LIKE_USERS)} 个不同用户并发点赞,个个都成功(没有死锁、没有 500)",
      not failed_likes, f"失败的请求:{failed_likes}")

_, res = call("GET", "/api/board/list", token=villager,
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
row = find_row(res, CONCUR_POST)
check(f"like_count 正好等于 {len(LIKE_USERS)}(一个不多、一个不少)",
      row.get("likeCount") == len(LIKE_USERS),
      f"实际 {row.get('likeCount')} —— 少了是丢了更新,多了是重复自增")
check("发起人自己看到 liked=true", row.get("liked") is True, f"实际 {row.get('liked')}")

# ── 并发之二:同一个人连点六下 ────────────────────────────────────
#
# 同一用户的并发点赞走的是唯一约束那条路:DELETE 判分支 + INSERT 撞
# uk_board_user。这一段也真的死锁过 —— REPEATABLE READ 下 DELETE 没删到行时
# 会在索引间隙上加间隙锁,几条并发的 INSERT 各自申请插入意向锁,互相等成环,
# 表现是"计数最后是对的,但 6 个请求里 5 个报系统繁忙"。
#
# 现在这些事务在第一步就被主帖行的排他锁串起来了,DELETE 和 INSERT 不再并发,
# 间隙那一环自然形不成。所以这一段的首要断言是:一个都不能报错。
CONCUR = 6
results = [None] * CONCUR
barrier2 = threading.Barrier(CONCUR)


def like_once(idx):
    try:
        barrier2.wait(timeout=10)
    except Exception:
        pass
    results[idx] = call("POST", f"/api/board/{CONCUR_POST}/like", token=villager)


threads = [threading.Thread(target=like_once, args=(i,)) for i in range(CONCUR)]
for t in threads:
    t.start()
for t in threads:
    t.join()

# 变量名不要叫 failed —— 那是模块级的失败计数器,覆盖掉它会让 check() 里的
# global failed 变成一个 int,下一处 failed += 1 直接 TypeError。
failed_reqs = [r[1].get("message") for r in results if r and r[1].get("code") != 200]
check(f"同一个人连点 {CONCUR} 下,没有一次报错(并发点赞的回归用例)",
      not failed_reqs, f"实际失败的请求:{failed_reqs}")

# 同一个人的 6 次 toggle 是"点开—取消—点开—取消…",6 是偶数,理论上会回到
# 出发时的状态。但测试不该假定它停在哪一边 —— 那等于把断言绑在实现细节上,
# 将来少跑一次或多跑一次就红。
#
# 真正该断言的是那条不变式:**明细表里有几行,主帖上就该写着几。**
# 逐个人问一遍"这条你点过吗",数出来说"点过"的人数,必须正好等于 like_count。
# 这个判据与 6 次 toggle 的落库顺序无关,任何顺序下都成立 ——
# 少一个说明丢了更新,多一个说明重复自增。
likers = []
for name, tk in LIKE_USERS:
    _, r = call("GET", "/api/board/list", token=tk,
                params={"pageNum": 1, "pageSize": 20, "villageId": 2})
    target_row = find_row(r, CONCUR_POST)
    if target_row and target_row.get("liked") is True:
        likers.append(name)

_, res = call("GET", "/api/board/list", token=villager,
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
count_after = find_row(res, CONCUR_POST).get("likeCount")
check(f"逐个人问过:{len(likers)} 人说点过,like_count 也正好写着 {count_after}",
      len(likers) == count_after,
      f"说点过的人={likers},但计数写着 {count_after}")


# ══════════════════════════════════════════════════════════════════
section("十一、留言板:删除与连带关系")
# ══════════════════════════════════════════════════════════════════

# 场景:张建国发一条主帖,李秀兰回复两条,然后张建国删主帖
_, res = call("POST", "/api/board/post", {"content": "批次6c测试:待会儿要被删掉的帖"}, token=villager)
victim = res["data"]
r1 = call("POST", "/api/board/reply", {"parentId": victim, "content": "第一批次6c回复"}, token=villager2)[1]["data"]
r2 = call("POST", "/api/board/reply", {"parentId": victim, "content": "第二批次6c回复"}, token=villager2)[1]["data"]

_, res = call("GET", "/api/board/replies", params={"parentId": victim, "pageNum": 1, "pageSize": 10})
check("删之前,两条回复都读得到", len(rows_of(res)) == 2, f"实际 {len(rows_of(res))}")

status, res = call("DELETE", f"/api/board/{victim}", token=villager2)
check_code("删别人的帖 → 403「只能删除自己的留言」", res, 403)

status, res = call("DELETE", f"/api/board/{victim}", token=villager)
check_code("删自己的帖成功", res, 200)
_, res = call("GET", "/api/board/list",
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
check("被删的主帖不在列表里了", victim not in ids_of(res), f"实际 {ids_of(res)}")
status, res = call("GET", "/api/board/replies",
                   params={"parentId": victim, "pageNum": 1, "pageSize": 10})
check("它下面的回复也一起读不到了(没有孤儿回复)",
      status == 404 or res.get("code") == 404 or len(rows_of(res)) == 0,
      f"实际 HTTP {status} / code={res.get('code')} / {len(rows_of(res))} 行")

# 删一条回复:父帖的 reply_count 必须减回来
_, res = call("GET", "/api/board/list",
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
before_count = find_row(res, BOARD_WITH_TWO_REPLIES).get("replyCount")
status, res = call("DELETE", f"/api/board/{new_reply_id}", token=villager2)
check_code("删自己的回复成功", res, 200)
_, res = call("GET", "/api/board/list",
              params={"pageNum": 1, "pageSize": 20, "villageId": 2})
after_count = find_row(res, BOARD_WITH_TWO_REPLIES).get("replyCount")
check("删回复后父帖的 reply_count 减 1",
      after_count == before_count - 1, f"{before_count} → {after_count}")
_, res = call("GET", "/api/board/replies",
              params={"parentId": BOARD_WITH_TWO_REPLIES, "pageNum": 1, "pageSize": 10})
check("被删的回复不在回复列表里",
      new_reply_id not in [r["id"] for r in rows_of(res)],
      f"实际 {[r['id'] for r in rows_of(res)]}")

status, res = call("DELETE", "/api/board/99999", token=villager)
check_code("删不存在的留言 → 404", res, 404)

status, res = call("DELETE", f"/api/board/{victim}", token=villager)
check_code("重复删同一条 → 404(逻辑删除后再查不到)", res, 404)


# ══════════════════════════════════════════════════════════════════
section("十二、后台删除的数据级权限")
# ══════════════════════════════════════════════════════════════════

# 后台删除同样要过数据级权限 —— 只在列表里过滤、写接口不校验的话,
# 村管改一下请求里的 ID 就能删掉别村的留言
status, res = call("DELETE", f"/api/admin/board/{BOARD_V4_ONLY}", token=village_admin)
check_code("战旗村村管删金星村的留言 → 403「只能管理本村的留言」", res, 403)

check_code("战旗村村管删金星村的新帖 → 403",
           call("DELETE", f"/api/admin/board/{outsider_post}", token=village_admin), 403)

status, res = call("DELETE", f"/api/admin/board/{outsider_post}", token=other_admin)
check_code("先锋村村管也删不了金星村的帖子 → 403(数据范围是本村)", res, 403)

status, res = call("DELETE", f"/api/admin/board/{victim}", token=super_admin)
check_code("超管删已被本人删掉的那条 → 404", res, 404)

status, res = call("DELETE", f"/api/admin/board/{BOARD_V4_ONLY}", token=super_admin)
check_code("超管能删任何村的留言", res, 200)

status, res = call("GET", "/api/board/list",
                   params={"pageNum": 1, "pageSize": 20, "villageId": 4})
check("被超管删掉的帖子从金星村前台列表消失",
      BOARD_V4_ONLY not in ids_of(res), f"实际 {ids_of(res)}")


# ══════════════════════════════════════════════════════════════════
section("十三、公告:编辑 / 发布 / 下架")
# ══════════════════════════════════════════════════════════════════

# 这一节的三个动作都按 id 操作,走不到列表查询里那条村组白名单 ——
# 所以归属校验必须单独来一次。"只在列表里过滤"是最常见的漏法:
# 改一下 URL 里的 id 就能改别村的公告。
DRAFT_ANN = 7        # 战旗村的草稿(status=0)
V2_PUBLISHED = 5     # 战旗村已发布公告
V3_PUBLISHED = 6     # 先锋村公告
GLOBAL_ANN = 1       # 全平台公告(village_id 为 NULL)

check_code("未登录编辑公告 → 401",
           call("PUT", f"/api/admin/announcement/{V2_PUBLISHED}",
                {"title": "x", "content": "y", "noticeType": 1}), 401)

check_code("村民编辑公告 → 403(没有 announcement:edit 权限)",
           call("PUT", f"/api/admin/announcement/{V2_PUBLISHED}",
                {"title": "x", "content": "y", "noticeType": 1}, token=villager), 403)

check_code("村民下架公告 → 403(没有 announcement:offline 权限)",
           call("PUT", f"/api/admin/announcement/{V2_PUBLISHED}/offline",
                token=villager), 403)

status, res = call("PUT", f"/api/admin/announcement/{V2_PUBLISHED}",
                   {"title": "越村编辑", "content": "y", "noticeType": 1},
                   token=other_admin)
check_code("先锋村村管编辑战旗村的公告 → 403「只能管理本村发布的公告」", res, 403)

status, res = call("PUT", f"/api/admin/announcement/{V3_PUBLISHED}/offline",
                   token=village_admin)
check_code("战旗村村管下架先锋村的公告 → 403", res, 403)

# 全平台公告的受众是所有村,村管整条都不该碰 —— 不是"只能改本村那一半"
status, res = call("PUT", f"/api/admin/announcement/{GLOBAL_ANN}",
                   {"title": "村管改全平台", "content": "y", "noticeType": 5},
                   token=village_admin)
check_code("村管编辑全平台公告 → 403「全平台公告只能由平台管理员维护」", res, 403)

check_code("村管下架全平台公告 → 403",
           call("PUT", f"/api/admin/announcement/{GLOBAL_ANN}/offline",
                token=village_admin), 403)

check_code("超管编辑全平台公告 → 200",
           call("PUT", f"/api/admin/announcement/{GLOBAL_ANN}",
                {"title": "国庆假期平台服务安排", "content": "正文(超管编辑过)",
                 "noticeType": 5, "isTop": 1}, token=super_admin), 200)

check_code("编辑不存在的公告 → 404",
           call("PUT", "/api/admin/announcement/99999",
                {"title": "x", "content": "y", "noticeType": 1}, token=village_admin), 404)

# ── 后台详情接口存在的理由 ────────────────────────────────────
# 前台的详情接口只认「已发布」,草稿去问它是 404 —— 编辑草稿时拿不到正文。
status, res = call("GET", f"/api/announcement/detail/{DRAFT_ANN}", token=village_admin)
check_code("前台详情接口读草稿 → 404(它只认已发布)", res, 404)

status, res = call("GET", f"/api/admin/announcement/{DRAFT_ANN}", token=village_admin)
check("后台详情接口读得到草稿的正文(编辑功能靠它)",
      res.get("code") == 200 and bool((res.get("data") or {}).get("content")),
      f"code={res.get('code')}, content={(res.get('data') or {}).get('content')!r}"[:120])

check_code("别村村管读后台详情 → 403",
           call("GET", f"/api/admin/announcement/{V2_PUBLISHED}", token=other_admin), 403)

# ── 状态机:哪些动作在哪个状态上合法 ──────────────────────────
status, res = call("PUT", f"/api/admin/announcement/{DRAFT_ANN}/offline",
                   token=village_admin)
check_code("草稿直接下架 → 400(没有「已发布」过的东西谈不上撤下)", res, 400)

status, res = call("PUT", f"/api/admin/announcement/{V2_PUBLISHED}/publish",
                   token=village_admin)
check_code("把已发布的再发布一次 → 400", res, 400)

# ── 编辑:改内容,不改别的东西 ────────────────────────────────
_, before = call("GET", f"/api/admin/announcement/{V2_PUBLISHED}", token=village_admin)
before = before.get("data") or {}

# 请求体里塞 villageId / publisherId / status / asDraft —— DTO 里没有的字段
# 框架会静默忽略,这是"批量赋值"(mass assignment)那类越权的入口:
# 只要 DTO 里有一个字段名对得上,前端就能改归属、改署名。
#
# 【为什么这四种都要试,尤其 asDraft】
# villageId 和 publisherId 是"想改归属",status 是"想改状态",
# 但 status 在 AnnouncementDTO 里**根本不存在** —— 塞它只能证明
# "塞一个不存在的字段没用"。真正的风险在 asDraft:它是 DTO 上真实存在、
# 而且唯一能影响状态机语义的字段(publish 会按它落 drafted/published)。
# 如果哪天有人把 update() 改写成整体拷贝 DTO,唯一会被误改的状态入口
# 就是它。所以这里必须真的塞一次。
status, res = call("PUT", f"/api/admin/announcement/{V2_PUBLISHED}",
                   {"title": "战旗村闲置农机共享倡议书(修订)",
                    "content": "各位乡亲:\n\n(本段为批次6c接口测试的编辑结果)",
                    "noticeType": 1, "isTop": 1,
                    "villageId": 3, "publisherId": 1, "status": 0, "viewCount": 9999,
                    "asDraft": True},
                   token=village_admin)
check_code("村管编辑本村公告成功", res, 200)

_, after = call("GET", f"/api/admin/announcement/{V2_PUBLISHED}", token=village_admin)
after = after.get("data") or {}

check("标题确实改掉了", after.get("title") == "战旗村闲置农机共享倡议书(修订)",
      f"实际 {after.get('title')!r}")
check("归属改不动:villageId 仍是 2(请求体里塞了 3)",
      after.get("villageId") == 2, f"实际 {after.get('villageId')}")
check("署名改不动:publisherId 仍是 2(请求体里塞了 1)",
      after.get("publisherId") == 2, f"实际 {after.get('publisherId')}")
check("状态改不动:仍是已发布(请求体里塞了 status=0)",
      after.get("status") == 1, f"实际 {after.get('status')}")
# 这条是上面那句注释要钉住的东西:编辑接口不看 asDraft ——
# 「改内容」和「改状态」是两件事,一个接口只做一件
check("状态改不动:编辑接口不看 asDraft(请求体里塞了 asDraft=true)",
      after.get("status") == 1, f"实际 {after.get('status')}")
check("浏览量改不动:仍是原来的数(请求体里塞了 9999)",
      after.get("viewCount") == before.get("viewCount"),
      f"{before.get('viewCount')} → {after.get('viewCount')}")
check("发布时间没有被重置(编辑不等于重新发布)",
      after.get("publishTime") == before.get("publishTime"),
      f"{before.get('publishTime')} → {after.get('publishTime')}")

# 编辑不能把一条村级公告变成"系统公告" —— 落款还是本村,看起来却像平台发的
status, res = call("PUT", f"/api/admin/announcement/{V2_PUBLISHED}",
                   {"title": "冒充平台", "content": "y", "noticeType": 5},
                   token=village_admin)
check_code("村管把村级公告的类型改成「系统公告」→ 403", res, 403)

status, res = call("PUT", f"/api/admin/announcement/{V2_PUBLISHED}",
                   {"title": "关于诈骗的公告", "content": "y", "noticeType": 1},
                   token=village_admin)
check_code("编辑时命中拦截类敏感词 → 1010(和发布同一个口径)", res, SENSITIVE_CONTENT)

# ── 编辑不重发通知 ────────────────────────────────────────────
# 这条断言分成两半才有意义:先证明"编辑没发",再证明"发布发了"。
# 只测前一半的话,如果通知功能整个坏了,它也照样通过。
_, res = call("GET", "/api/notice/list", token=villager,
              params={"pageNum": 1, "pageSize": 50})
notice_before = total_of(res)

call("PUT", f"/api/admin/announcement/{V2_PUBLISHED}",
     {"title": "战旗村闲置农机共享倡议书(改标点)", "content": "正文再改一次。",
      "noticeType": 1, "isTop": 1}, token=village_admin)

_, res = call("GET", "/api/notice/list", token=villager,
              params={"pageNum": 1, "pageSize": 50})
notice_after_edit = total_of(res)
check(f"编辑已发布的公告不给村民重发通知({notice_before} → {notice_after_edit})",
      notice_after_edit == notice_before,
      f"通知数从 {notice_before} 变成了 {notice_after_edit}")

# ── 发布草稿:这才该发通知 ────────────────────────────────────
status, res = call("PUT", f"/api/admin/announcement/{DRAFT_ANN}/publish",
                   token=village_admin)
check_code("村管发布本村的草稿公告成功", res, 200)

_, res = call("GET", f"/api/admin/announcement/{DRAFT_ANN}", token=village_admin)
draft = res.get("data") or {}
check("草稿的 status 变成了已发布(1)", draft.get("status") == 1,
      f"实际 {draft.get('status')}")
check("草稿的发布时间被盖上了", bool(draft.get("publishTime")),
      f"实际 {draft.get('publishTime')}")

_, res = call("GET", "/api/notice/list", token=villager,
              params={"pageNum": 1, "pageSize": 50})
notice_after_publish = total_of(res)
check(f"发布草稿给村民发了通知({notice_after_edit} → {notice_after_publish})",
      notice_after_publish > notice_after_edit,
      f"通知数没变,还是 {notice_after_publish}")

_, res = call("GET", "/api/announcement/list", token=villager,
              params={"pageNum": 1, "pageSize": 20})
check("发布后的草稿出现在前台公告列表里", DRAFT_ANN in ids_of(res),
      f"前台列表 {ids_of(res)}")

# ── 下架:前台读不到,后台还看得到 ────────────────────────────
status, res = call("PUT", f"/api/admin/announcement/{DRAFT_ANN}/offline",
                   token=village_admin)
check_code("村管下架本村公告成功", res, 200)

_, res = call("GET", f"/api/admin/announcement/{DRAFT_ANN}", token=village_admin)
check("下架后后台详情里状态是「已下架」(2)",
      (res.get("data") or {}).get("status") == 2,
      f"实际 {(res.get('data') or {}).get('status')}")

check_code("下架后前台详情 → 404",
           call("GET", f"/api/announcement/detail/{DRAFT_ANN}", token=villager), 404)

_, res = call("GET", "/api/announcement/list", token=villager,
              params={"pageNum": 1, "pageSize": 20})
check("下架后它从村民的前台列表里消失了", DRAFT_ANN not in ids_of(res),
      f"前台列表 {ids_of(res)}")

_, res = call("GET", "/api/admin/announcement/list", token=village_admin,
              params={"pageNum": 1, "pageSize": 20})
check("下架后后台列表里仍然找得到(后台不带 status = 1 这个条件)",
      DRAFT_ANN in ids_of(res), f"后台列表 {ids_of(res)}")

check_code("重复下架同一条 → 400",
           call("PUT", f"/api/admin/announcement/{DRAFT_ANN}/offline",
                token=village_admin), 400)

# 下架不是死路:改完还能再发。这条断言是"下架可逆"的证据 ——
# 一个只能下架、不能再发布的按钮,等于给后台留了一批谁也动不了的公告。
call("PUT", f"/api/admin/announcement/{DRAFT_ANN}",
     {"title": "战旗村冬季农田水利检修安排(定稿)",
      "content": "拟于 11 月上旬开展全村农田水利设施检修,时段已与各组核对完毕。",
      "noticeType": 1, "isTop": 0}, token=village_admin)

status, res = call("PUT", f"/api/admin/announcement/{DRAFT_ANN}/publish",
                   token=village_admin)
check_code("下架的公告改完再发布 → 200(下架可逆)", res, 200)

_, res = call("GET", "/api/announcement/list", token=villager,
              params={"pageNum": 1, "pageSize": 20})
check("重新发布后它又回到村民的前台列表里", DRAFT_ANN in ids_of(res),
      f"前台列表 {ids_of(res)}")

check_code("下架不存在的公告 → 404",
           call("PUT", "/api/admin/announcement/99999/offline",
                token=village_admin), 404)

check_code("发布不存在的公告 → 404",
           call("PUT", "/api/admin/announcement/99999/publish",
                token=village_admin), 404)

# ── 新建时的「存草稿」 ────────────────────────────────────────
# 存草稿和直接发布共用 POST /publish 一个接口,靠请求体里的 asDraft 分叉。
# 不额外造一个接口或权限码:「存草稿」不是一种新能力,它只是「发布公告」
# 这个动作的一个分支 —— 能新建公告的人,就有权决定现在发还是晚点发。
_, res = call("GET", "/api/notice/list", token=villager,
              params={"pageNum": 1, "pageSize": 50})
notice_before_draft = total_of(res)

status, res = call("POST", "/api/admin/announcement/publish",
                   {"title": "批次6c新建的草稿", "content": "这条先存着,还没写完。",
                    "noticeType": 1, "isTop": 0, "asDraft": True},
                   token=village_admin)
check_code("村管存草稿成功", res, 200)
new_draft_id = res["data"]

_, res = call("GET", f"/api/admin/announcement/{new_draft_id}", token=village_admin)
draft = res.get("data") or {}
check("草稿的状态是 0(草稿)", draft.get("status") == 0, f"实际 {draft.get('status')}")
check("草稿没有发布时间 —— 它还没发过", draft.get("publishTime") is None,
      f"实际 {draft.get('publishTime')}")
check("草稿的归属仍然是村管自己的村(2)", draft.get("villageId") == 2,
      f"实际 {draft.get('villageId')}")

_, res = call("GET", "/api/notice/list", token=villager,
              params={"pageNum": 1, "pageSize": 50})
check(f"存草稿不给任何人发通知({notice_before_draft} → {total_of(res)})",
      total_of(res) == notice_before_draft,
      f"通知数从 {notice_before_draft} 变成了 {total_of(res)}")

_, res = call("GET", "/api/announcement/list", token=villager,
              params={"pageNum": 1, "pageSize": 20})
check("草稿不出现在村民的前台列表里", new_draft_id not in ids_of(res),
      f"前台列表 {ids_of(res)}")
check_code("草稿的前台详情 → 404(前台只认已发布)",
           call("GET", f"/api/announcement/detail/{new_draft_id}", token=villager), 404)

_, res = call("GET", "/api/admin/announcement/list", token=village_admin,
              params={"pageNum": 1, "pageSize": 20})
check("草稿在后台列表里找得到(否则这条就没人管了)",
      new_draft_id in ids_of(res), f"后台列表 {ids_of(res)}")

# 草稿要能编辑 —— 存草稿的整个意义就是"还没写完,回头接着改",
# 编辑不了的话这个功能只剩一半。update() 目前不按状态分叉(三种状态都能编辑),
# 这条断言是把这条规则钉住:哪天有人给它加一道"只有某些状态可编辑"的门,
# 草稿的编辑会先在这里变红。
status, res = call("PUT", f"/api/admin/announcement/{new_draft_id}",
                   {"title": "批次6c新建的草稿(改过一次)",
                    "content": "补完了,可以发了。", "noticeType": 1,
                    "isTop": 0, "asDraft": True},
                   token=village_admin)
check_code("编辑草稿成功(存草稿→接着改是一条正常路径)", res, 200)

_, res = call("GET", f"/api/admin/announcement/{new_draft_id}", token=village_admin)
edited_draft = res.get("data") or {}
check("编辑草稿后内容变了", edited_draft.get("title") == "批次6c新建的草稿(改过一次)",
      f"实际 {edited_draft.get('title')!r}")
check("编辑草稿不会把它变成已发布 —— 编辑和发布是两个动作",
      edited_draft.get("status") == 0, f"实际 {edited_draft.get('status')}")
check("编辑草稿仍然没有发布时间", edited_draft.get("publishTime") is None,
      f"实际 {edited_draft.get('publishTime')}")

# 存草稿也要过敏感词和「系统公告」两道关 —— 草稿同样是写下来的东西,
# 不能让它先绕过检查、等发布那一步再变成一个没有归属的怪东西
check_code("存草稿同样拦敏感词 → 1010",
           call("POST", "/api/admin/announcement/publish",
                {"title": "草稿里的诈骗", "content": "正文", "noticeType": 1,
                 "asDraft": True}, token=village_admin), SENSITIVE_CONTENT)

check_code("村管存「系统公告」草稿 → 403",
           call("POST", "/api/admin/announcement/publish",
                {"title": "草稿冒用平台", "content": "正文", "noticeType": 5,
                 "asDraft": True}, token=village_admin), 403)

# 不传 asDraft 的调用方行为不变 —— 这个字段是加出来的,不是改出来的
status, res = call("POST", "/api/admin/announcement/publish",
                   {"title": "批次6c向后兼容公告", "content": "不传 asDraft 应当直接发布。",
                    "noticeType": 1, "isTop": 0},
                   token=village_admin)
check_code("不传 asDraft → 行为不变,仍然是直接发布", res, 200)
_, res = call("GET", f"/api/admin/announcement/{res['data']}", token=village_admin)
compat = res.get("data") or {}
check("不传 asDraft 时状态是 1 且发布时间有值",
      compat.get("status") == 1 and bool(compat.get("publishTime")),
      f"status={compat.get('status')}, publishTime={compat.get('publishTime')}")

# 草稿发布走的是「发布已有」那个接口,和 list 上点「发布」是同一条路径
status, res = call("PUT", f"/api/admin/announcement/{new_draft_id}/publish",
                   token=village_admin)
check_code("新建的草稿能直接发布出去", res, 200)
_, res = call("GET", "/api/announcement/list", token=villager,
              params={"pageNum": 1, "pageSize": 20})
check("发布之后村民就看到了", new_draft_id in ids_of(res), f"前台列表 {ids_of(res)}")


# ══════════════════════════════════════════════════════════════════
print()
print("=" * 70)
print(f"结果:通过 {passed} 项,失败 {failed} 项")
print("=" * 70)
if failed:
    print("有失败项,请看上面的 [FAIL] 行。")
    sys.exit(1)
print("全部通过。")
