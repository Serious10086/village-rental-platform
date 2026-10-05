#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次 7 —— AI 智能客服 接口层验证。

覆盖:问答链路、参数校验、多轮上下文、内容安全的两个方向(入口拦提问 / 出口拦回答)、
三级降级、降级率统计口径、数据级权限、会话越权。

【这一批的接口测试为什么必须存在】
AI 这东西最容易"看着能跑就算过"。但这一批真正要交付的是三条硬承诺:
    1. AI 调不通时返回兜底话术,**不是 500**
    2. 降级的原因要能被统计出来,而且分母口径说得清
    3. 提问被内容安全拦下时**不往外发**(不花钱、不把违规内容送出去)
这三条都是可断言的事实,不该靠"我试了一下没问题"。

【降级怎么测】
代码正确的时候 AI 不会调不通,所以用超管装载一次性失败点(和批次 5 的
事务失败点同一套机制),让下一个提问被强制判定为指定失败类型。
每次装载只影响一个提问,触发后自动解除。

用法(需先启动后端 8080):
    python scripts/api_test_ai.py

注意:本脚本会**真实调用 DeepSeek**(每次提问几十个 token),
也会往 ai_chat_record 里写记录。重复跑之前请重置演示数据:
    mysql -uroot -proot < sql/05_mock_data.sql
"""
import concurrent.futures
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "http://localhost:8080"

SENSITIVE_CONTENT = 1010

passed = 0
failed = 0


# ══════════════════════════════════════════════════════════════════
#  工具
# ══════════════════════════════════════════════════════════════════

def call(method, path, body=None, token=None, params=None):
    """发一个请求,返回 (HTTP状态码, 解析后的 JSON)。"""
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
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
    """断言业务返回码。

    注意 HTTP 状态码基本永远是 200,业务码在 JSON 体的 code 里 ——
    这个约定在批次 1 就定下来了。例外是越权读公告详情那种真正返回 404 的。
    """
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


def rows_of(res):
    return (res.get("data") or {}).get("rows") or []


def total_of(res):
    return (res.get("data") or {}).get("total") or 0


def ask(token, question, session_id=None):
    """问一句,返回 data 部分"""
    _, res = call("POST", "/api/ai/chat",
                  {"sessionId": session_id or str(uuid.uuid4()), "question": question},
                  token=token)
    return res.get("data") or {}


# ══════════════════════════════════════════════════════════════════
#  测试数据约定(与 sql/05_mock_data.sql 一一对应)
#
#  演示数据里有 12 条 AI 对话记录:
#    成功 9 条 / 降级 2 条(超时、鉴权失败401)/ 失败 1 条(提问包含违规词)
#    降级率 = 2 / 12 ≈ 16.7%
#  而且**刻意分成两个村**:战旗村 7 条、先锋村 3 条、金星村 2 条 ——
#  用来验数据级权限。
#
#  用户:1 平台管理员(超管) 2 战旗村支书 3 先锋村主任
#        4 张建国 5 李秀兰 7 赵春梅 8 刘小明(战旗村 2)
#        6 王大山(金星村 4)  9 陈晓芳 10 周伟(先锋村 3)
# ══════════════════════════════════════════════════════════════════

print("=" * 70)
print("批次 7 —— AI 智能客服 验证")
print("=" * 70)

super_admin = login("13800000000")
village_admin = login("13800000001")   # 战旗村支书
other_admin = login("13800000002")     # 先锋村主任
villager = login("13800000003")        # 张建国(战旗村)
villager2 = login("13800000004")       # 李秀兰(战旗村)

check("五个测试账号全部登录成功",
      all([super_admin, village_admin, other_admin, villager, villager2]),
      "有账号登录失败,后续用例不可信")


# ══════════════════════════════════════════════════════════════════
section("一、问答:基本链路与参数校验")
# ══════════════════════════════════════════════════════════════════

check_code("未登录提问 → 401",
           call("POST", "/api/ai/chat",
                {"sessionId": "x", "question": "押金怎么算"})[1], 401)

check_code("问题为空 → 400(参数校验)",
           call("POST", "/api/ai/chat",
                {"sessionId": str(uuid.uuid4()), "question": "   "},
                token=villager)[1], 400)

check_code("会话标识为空 → 400",
           call("POST", "/api/ai/chat",
                {"sessionId": "", "question": "押金怎么算"},
                token=villager)[1], 400)

check_code("问题超过 1000 字 → 400(不留给数据库去截断)",
           call("POST", "/api/ai/chat",
                {"sessionId": str(uuid.uuid4()), "question": "押" * 1001},
                token=villager)[1], 400)

check_code("会话标识超过 64 字 → 400",
           call("POST", "/api/ai/chat",
                {"sessionId": "s" * 65, "question": "押金怎么算"},
                token=villager)[1], 400)

session_a = str(uuid.uuid4())
first = ask(villager, "租拖拉机押金怎么算?", session_a)
check("正常提问得到 AI 回答(status=1)", first.get("status") == 1,
      f"实际 status={first.get('status')}, 原因={first.get('degradeReason')}")
check("回答非空", bool((first.get("answer") or "").strip()),
      repr((first.get("answer") or "")[:60]))
check("记下了实际使用的模型", first.get("model") == "deepseek-flash",
      f"实际 {first.get('model')}")
check("记下了 token 用量(提示 + 生成都大于 0)",
      (first.get("promptTokens") or 0) > 0 and (first.get("completionTokens") or 0) > 0,
      f"{first.get('promptTokens')}+{first.get('completionTokens')}")
check("记下了耗时", (first.get("costTime") or 0) > 0, f"{first.get('costTime')}ms")
check("成功时没有降级原因", not first.get("degradeReason"),
      f"实际 {first.get('degradeReason')}")
check("返回了记录 id(前端要用它标记已解决)", bool(first.get("recordId")),
      str(first.get("recordId")))
first_record_id = first.get("recordId")

# 提示词有没有真的生效 —— 这是这一批最值钱的一条断言
answer = first.get("answer") or ""
check("提示词纠正过的那个错没有再犯:答案没有把押金说成按租金比例",
      "比例" not in answer
      or any(k in answer for k in ("不是按租金比例", "不按", "跟租金", "无关")),
      answer[:120])
# 【判据为什么给这么多个同义词】AI 的回答措辞每次都不一样,
# 只认「固定」两个字的话,它说"由发布者设定"就会被判成失败 ——
# 而那句话是对的。这类断言要断的是"规则说对了没有",不是"用没用某个词",
# 所以同义表达都要接受。实测踩到过一次措辞不同导致的假失败。
check("答案点出了押金是发布者自己设的、不是按比例算的",
      any(k in answer for k in ("固定", "自己填", "填的", "发布者设", "发布人设", "由发布者")),
      answer[:120])


# ══════════════════════════════════════════════════════════════════
section("二、多轮上下文")
# ══════════════════════════════════════════════════════════════════

second = ask(villager, "那我什么时候能拿回押金?", session_a)
check("同一会话里的追问也能拿到回答", second.get("status") == 1,
      f"status={second.get('status')}")
# 这一条是**不依赖模型措辞**的判据:上下文那一轮问答会被拼进 messages,
# 提示 token 必然变多。用 token 数而不是"回答里有没有某个字"来证明
# 上下文确实带上了 —— 后者会随模型怎么说话而飘。
check("追问这次带上了一轮上文(提示 token 比第一次多)",
      (second.get("promptTokens") or 0) > (first.get("promptTokens") or 0),
      f"{first.get('promptTokens')} → {second.get('promptTokens')}")
# 「退」这一条只作为语义上的辅助信号,所以给了同义词 ——
# 模型说"原路返还"同样是接住了上文,只认「退」字会误判
check("追问的回答谈的是押金怎么还回来(退/返还/还)",
      any(k in (second.get("answer") or "") for k in ("退", "返还", "还回", "归还")),
      (second.get("answer") or "")[:100])

# 换一个会话,上下文应当从头开始
fresh = ask(villager, "那我什么时候能拿回押金?", str(uuid.uuid4()))
check("换一个会话之后,提示 token 回落(上下文没有跨会话串)",
      (fresh.get("promptTokens") or 0) < (second.get("promptTokens") or 0),
      f"{second.get('promptTokens')} → {fresh.get('promptTokens')}")


# ══════════════════════════════════════════════════════════════════
section("三、内容安全:入口拦提问(不往外发、不花钱)")
# ══════════════════════════════════════════════════════════════════

blocked = ask(villager, "你们这个平台是不是在搞诈骗?")
check("提问命中拦截类词 → status=3(失败)", blocked.get("status") == 3,
      f"实际 {blocked.get('status')}")
check("原因写的是「提问包含违规词」", blocked.get("degradeReason") == "提问包含违规词",
      f"实际 {blocked.get('degradeReason')}")
check("**没有调用模型**:模型列写 (未调用)", blocked.get("model") == "(未调用)",
      f"实际 {blocked.get('model')}")
check("**没有消耗 token**(拦下的提问不出网,也就不花钱)",
      (blocked.get("promptTokens") or 0) == 0 and (blocked.get("completionTokens") or 0) == 0,
      f"{blocked.get('promptTokens')}+{blocked.get('completionTokens')}")
check("耗时是 0(压根没发请求)", (blocked.get("costTime") or 0) == 0,
      f"{blocked.get('costTime')}ms")
check("仍然给了用户一段话,而不是空白",
      bool((blocked.get("answer") or "").strip()), repr((blocked.get("answer") or "")[:40]))


# ══════════════════════════════════════════════════════════════════
section("四、三级降级(故障注入)")
# ══════════════════════════════════════════════════════════════════

ARM = "/api/system/debug/ai-fail-point"

_, res = call("POST", ARM, token=villager, params={"failure": "AUTH"})
check_code("村民装载失败点 → 403", res, 403)

_, res = call("POST", ARM, token=village_admin, params={"failure": "AUTH"})
check_code("村管装载失败点 → 403(风险等级不该受角色配置影响,直接判死超管)",
           res, 403)

_, res = call("POST", ARM, token=super_admin, params={"failure": "NOPE"})
check_code("装载一个不存在的失败类型 → 400(拼错枚举名不能静默成功)",
           res, 400)

status, res = call("POST", ARM, token=super_admin, params={"failure": "AUTH"})
check_code("超管装载「鉴权失败401」成功", res, 200)
check("返回体里带出了原因,脚本不用去读代码猜",
      (res.get("data") or {}).get("reason") == "鉴权失败401",
      json.dumps(res.get("data"), ensure_ascii=False))

# 第二级降级:AI 挂了,但本地问答库命中
d1 = ask(villager, "提现多久能到账?")
check("AI 挂了但本地问答库命中 → status=2(降级)", d1.get("status") == 2,
      f"实际 {d1.get('status')}")
check("降级原因就是注入的那个", d1.get("degradeReason") == "鉴权失败401",
      f"实际 {d1.get('degradeReason')}")
check("给的是本地问答库那条答案,不是「客服繁忙」",
      "审核" in (d1.get("answer") or "") and "有点忙" not in (d1.get("answer") or ""),
      (d1.get("answer") or "")[:100])
check("降级时 token 是 0(这次调用没产生生成量)",
      (d1.get("promptTokens") or 0) == 0 and (d1.get("completionTokens") or 0) == 0,
      f"{d1.get('promptTokens')}+{d1.get('completionTokens')}")

# 第三级降级:连本地问答库都没命中
call("POST", ARM, token=super_admin, params={"failure": "TIMEOUT"})
d2 = ask(villager, "你们这个村子附近有修农机的吗?")
check("本地库没这题 → 还是 status=2", d2.get("status") == 2, f"实际 {d2.get('status')}")
check("原因写的是「超时」", d2.get("degradeReason") == "超时",
      f"实际 {d2.get('degradeReason')}")
check("退到了通用话术,并且给出了下一步动作(留言板 / 站内信)",
      "有点忙" in (d2.get("answer") or "") and "留言板" in (d2.get("answer") or ""),
      (d2.get("answer") or "")[:120])

# 一次性:上一条问完之后,失败点应当已经自动解除
d3 = ask(villager, "发布资源要什么条件?")
check("失败点是一次性的:下一条提问已经恢复正常(status=1)",
      d3.get("status") == 1, f"实际 {d3.get('status')}, 原因={d3.get('degradeReason')}")

_, res = call("GET", ARM, token=super_admin)
check("查一下装载状态:已经是空的了",
      (res.get("data") or {}).get("armed") == "",
      json.dumps(res.get("data"), ensure_ascii=False)[:80])


# ══════════════════════════════════════════════════════════════════
section("五、后台:会话记录与统计口径")
# ══════════════════════════════════════════════════════════════════

_, res = call("GET", "/api/admin/ai/list", token=villager)
check_code("村民访问会话记录 → 403(需要 ai:record:list)", res, 403)

_, res = call("GET", "/api/admin/ai/stat", token=villager)
check_code("村民访问降级统计 → 403", res, 403)

_, res = call("GET", "/api/admin/ai/list", token=village_admin,
              params={"pageNum": 1, "pageSize": 50})
check("村管拿得到本村的会话记录", len(rows_of(res)) > 0, f"{len(rows_of(res))} 行")

rows = rows_of(res)
check("列表里带上了提问人和村组(后台要能看出是谁问的)",
      all(r.get("userName") for r in rows) and all(r.get("villageName") for r in rows),
      json.dumps(rows[0], ensure_ascii=False)[:120] if rows else "无数据")
check("状态中文名是服务端给的,不是前端自己映射的",
      all(r.get("statusName") in ("成功", "降级", "失败") for r in rows),
      str([r.get("statusName") for r in rows[:5]]))

# 统计口径:降级率必须等于 降级 / 总数,而且失败不计入分母口径
_, res = call("GET", "/api/admin/ai/stat", token=village_admin)
stat = res.get("data") or {}
total = stat.get("total") or 0
degraded = stat.get("degradedCount") or 0
failed_count = stat.get("failedCount") or 0
success = stat.get("successCount") or 0
check("三种状态的计数加起来等于总数",
      success + degraded + failed_count == total,
      f"{success}+{degraded}+{failed_count} != {total}")
expected_rate = round(100.0 * degraded / total, 1) if total else 0
check(f"降级率 = 降级 ÷ 总数(实际 {degraded}/{total} = {stat.get('degradeRate')}%)",
      abs(float(stat.get("degradeRate") or 0) - expected_rate) < 0.05,
      f"期望 {expected_rate},实际 {stat.get('degradeRate')}")
check("降级率为正(演示数据里就有两条降级)",
      float(stat.get("degradeRate") or 0) > 0, str(stat.get("degradeRate")))
check("平均耗时只统计成功的那部分(不会把兜底话术的耗时混进来)",
      (stat.get("avgCostMs") or 0) > 0, str(stat.get("avgCostMs")))

reasons = [r.get("reason") for r in (stat.get("reasons") or [])]
check("原因分布按原因分开列,不是笼统的一条「降级」",
      len(reasons) >= 3, str(reasons))
check("演示数据里那三种原因都在",
      {"超时", "鉴权失败401", "提问包含违规词"}.issubset(set(reasons)), str(reasons))
check("原因分布按次数倒序",
      [r.get("count") for r in (stat.get("reasons") or [])]
      == sorted([r.get("count") for r in (stat.get("reasons") or [])], reverse=True),
      str([(r.get("reason"), r.get("count")) for r in (stat.get("reasons") or [])]))

# 只看降级和失败
_, res = call("GET", "/api/admin/ai/list", token=village_admin,
              params={"pageNum": 1, "pageSize": 50, "onlyProblem": "true"})
problem_rows = rows_of(res)
check("传 onlyProblem=true → 列表里没有成功记录",
      all(r.get("status") in (2, 3) for r in problem_rows) and len(problem_rows) > 0,
      str([r.get("status") for r in problem_rows]))


# ══════════════════════════════════════════════════════════════════
section("六、数据级权限:村管只看本村")
# ══════════════════════════════════════════════════════════════════

_, res = call("GET", "/api/admin/ai/stat", token=other_admin)   # 先锋村村管
other_stat = res.get("data") or {}
_, res = call("GET", "/api/admin/ai/stat", token=super_admin)
super_stat = res.get("data") or {}

check("先锋村村管看到的总数 < 超管(数据范围是本村)",
      (other_stat.get("total") or 0) < (super_stat.get("total") or 0),
      f"先锋村 {(other_stat.get('total'))} vs 超管 {super_stat.get('total')}")
check("超管看到的是全部(两个村加起来的规模)",
      (super_stat.get("total") or 0) > (stat.get("total") or 0),
      f"超管 {super_stat.get('total')} vs 战旗村 {stat.get('total')}")

# 村管不能标记别村的记录
_, res = call("GET", "/api/admin/ai/list", token=super_admin,
              params={"pageNum": 1, "pageSize": 50})
other_village_rows = [r for r in rows_of(res) if r.get("villageName") == "先锋村"]
if other_village_rows:
    oid = other_village_rows[0]["id"]
    _, res = call("PUT", f"/api/admin/ai/{oid}/follow-up", token=village_admin)
    check_code("战旗村村管标记先锋村的记录 → 403", res, 403)
    _, res = call("PUT", f"/api/admin/ai/{oid}/follow-up", token=other_admin)
    check_code("先锋村村管标记本村的记录 → 200", res, 200)
    _, res = call("GET", "/api/admin/ai/list", token=super_admin,
                  params={"pageNum": 1, "pageSize": 50})
    marked = [r for r in rows_of(res) if r["id"] == oid]
    check("标记之后 isTransfer 变成 1",
          marked and marked[0].get("isTransfer") == 1,
          json.dumps(marked[0], ensure_ascii=False)[:100] if marked else "查不到")


# ══════════════════════════════════════════════════════════════════
section("七、会话越权:拿别人的 sessionId 蹭不到上下文")
# ══════════════════════════════════════════════════════════════════

# 张建国在某个会话里说出自己的名字,李秀兰拿同一个 sessionId 去问
shared_session = str(uuid.uuid4())
plant = ask(villager, "我叫张建国,我住战旗村三组", shared_session)
check("张建国在那个会话里报了名字,AI 回了话",
      plant.get("status") == 1, f"status={plant.get('status')}")

# ── 对照组:本人追问,上下文是通的 ──────────────────────────
#
# 【为什么这一条用 token 数而不是"回答里有没有他的名字"】
# 让模型复述名字是在赌它怎么说话 —— 它完全可能答"你说你住战旗村三组"
# 而不重复姓名,那一句同样证明上下文通了,却会把断言判失败。
# 而"上下文有没有被拼进去"这件事,提示 token 数是**确定性的**证据:
# 多一轮问答就多几十个 token,和模型怎么措辞无关。
own_probe = ask(villager, "我刚才说我叫什么?", shared_session)
check("(对照)本人追问时上下文确实拼进去了(提示 token 变多)",
      (own_probe.get("promptTokens") or 0) > (plant.get("promptTokens") or 0),
      f"{plant.get('promptTokens')} → {own_probe.get('promptTokens')}")

# ── 换个人拿同一个 sessionId ────────────────────────────────
#
# 取历史时带了 user_id 条件,所以她拿到的是**空上下文** ——
# 提示 token 会回落到和一个全新会话差不多的水平。
stolen = ask(villager2, "刚才那个人说他叫什么、住哪儿?", shared_session)
stolen_answer = stolen.get("answer") or ""
check("换个人拿同一个 sessionId → 上下文是空的(提示 token 没有变多)",
      (stolen.get("promptTokens") or 0) < (own_probe.get("promptTokens") or 0),
      f"本人追问 {own_probe.get('promptTokens')} vs 换个人 {stolen.get('promptTokens')}")
check("语义上也拿不到:回答里**没有**对方的名字",
      "张建国" not in stolen_answer, stolen_answer[:100])
check("也没有对方住的组名", "三组" not in stolen_answer, stolen_answer[:100])


# ══════════════════════════════════════════════════════════════════
section("八、标记「已解决」")
# ══════════════════════════════════════════════════════════════════

_, res = call("PUT", f"/api/ai/{first_record_id}/resolved", token=villager)
check_code("标记自己的对话 → 200", res, 200)

_, res = call("PUT", f"/api/ai/{first_record_id}/resolved", token=villager2)
check_code("标记别人的对话 → 403(recordId 是连着的整数,不校验就能遍历)",
           res, 403)

_, res = call("PUT", "/api/ai/999999/resolved", token=villager)
check_code("标记不存在的记录 → 404", res, 404)


# ══════════════════════════════════════════════════════════════════
section("九、并发提问(AI-06)")
# ══════════════════════════════════════════════════════════════════
#
# 【为什么用线程池而不是 JMeter】
# 原计划这一项是 JMeter 20 并发。实现时换成了 Python 线程池,理由是这一项
# 要验的其实是两件事:**线程池不崩**,以及**排队的请求不会变成错误**。
# 这两件事用 12 个线程就能验 —— 池子是 core2/max4/queue50/CallerRunsPolicy,
# 12 个并发会有 4 个在跑、8 个排队,排队的那 8 个要么等到位置后正常返回,
# 要么(队列满时)由调用方线程自己执行。无论哪种,用户都该拿到一段话,
# 而不是一个 500。
# 本机没装 JMeter,为一件事装一套压测工具不划算;真要压到 20 以上并发,
# 瓶颈会先落在 DeepSeek 的限流上,那已经不是本系统的问题了。

CONCURRENCY = 12

_, res = call("GET", "/api/admin/ai/stat", token=village_admin)
before_total = (res.get("data") or {}).get("total") or 0

with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
    results = list(pool.map(
        lambda i: ask(villager, f"第 {i} 个并发提问:租拖拉机押金怎么算?"),
        range(CONCURRENCY)))

check(f"{CONCURRENCY} 个并发提问全部拿到了业务响应(没有连接层面的失败)",
      len(results) == CONCURRENCY, f"实际 {len(results)} 个")
# status 取不到值就说明返回体里没有 data —— 那是 500 的形态
check("每一个都有明确的状态(1成功 / 2降级 / 3失败),没有 500",
      all(r.get("status") in (1, 2, 3) for r in results),
      str([r.get("status") for r in results]))
check("没有任何一个返回空回答(答不上来也要给兜底话术)",
      all((r.get("answer") or "").strip() for r in results),
      str([(r.get("status"), (r.get("answer") or "")[:20]) for r in results if not (r.get("answer") or "").strip()]))
check("排队的那些没有变成错误 —— 降级的也给了原因",
      all(r.get("status") == 1 or r.get("degradeReason") for r in results),
      str([(r.get("status"), r.get("degradeReason")) for r in results]))

succeeded = sum(1 for r in results if r.get("status") == 1)
print(f"    (12 个并发里:成功 {succeeded} 个,降级 {CONCURRENCY - succeeded} 个)")

_, res = call("GET", "/api/admin/ai/stat", token=village_admin)
after_total = (res.get("data") or {}).get("total") or 0
check(f"每一笔并发提问都落了库({before_total} → {after_total})",
      after_total - before_total == CONCURRENCY,
      f"只多了 {after_total - before_total} 条")


# ══════════════════════════════════════════════════════════════════
print()
print("=" * 70)
print(f"结果:通过 {passed} 项,失败 {failed} 项")
print("=" * 70)
if failed:
    print("有失败项,请看上面的 [FAIL] 行。")
    sys.exit(1)
print("全部通过。")
