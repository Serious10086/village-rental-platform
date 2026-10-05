#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次 6b(站内信 + 提现 + 纠纷)接口验证脚本。

验证点:
   1. 钱包概览与收益明细 —— 余额口径、direction 方向、只看自己的流水
   2. 提现前置校验 —— 起提门槛、余额不足、收款方式、必填项
   3. **并发提现(TC-09)** —— 余额 4000,两个请求同时提 4000,恰好一个成功
   4. 提现审核 —— 按钮级权限、数据级权限、驳回必填理由、乐观锁防重复退款
   5. 会话 —— 双方 ID 归一化(反向发起必须命中同一个会话)
   6. 聊天 —— 未读数增减、敏感词两级处理、非成员访问被拒
   7. 纠纷 —— 申请窗口、重复申请、订单状态存档与复原、撤销
   8. **数据级权限** —— 村管只能处理本村纠纷;村民读不到别人的纠纷
   9. 站内信权限回归 —— 按订单调取聊天记录只有管辖该村的管理员能用

【重要:本脚本会创建真实数据(提现单、会话、纠纷)】
跑完请重新执行以下命令恢复干净的演示数据:
    mysql -uroot -proot < sql/05_mock_data.sql
重复运行本脚本前也必须先重置 —— 上一轮留下的未决纠纷会让
"申请纠纷"用例误报失败(被"已有未决纠纷"挡住)。

用法(需先启动后端):
    python scripts/api_test_interact.py
"""
import json
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"
sys.stdout.reconfigure(encoding="utf-8")

# 业务返回码(对应后端 common/result/ResultCode.java)。
# 只给这组 1xxx 起名字:它们是容易记错的,第一版我凭印象写成
# 40001/40006,失败的是测试而不是代码。400/401/403/404 是 HTTP 语义,
# 直接写字面量更一目了然,也和 scripts/api_test_order.py 保持一致。
ORDER_STATUS_INVALID = 1008
BALANCE_NOT_ENOUGH = 1009
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
    """断言业务返回码"""
    actual = res[1].get("code")
    check(name, actual == expect_code,
          f"期望 code={expect_code},实际 {actual} / {res[1].get('message')}")


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


# ══════════════════════════════════════════════════════════════════
#  测试数据约定(与 sql/05_mock_data.sql 一一对应,不依赖接口摸索)
#
#  用户:1 平台管理员(超管) 2 战旗村支书(村管,village 2)
#        3 先锋村主任(村管,village 3)
#        4 张建国 5 李秀兰 8 刘小明(战旗村) 6 王大山(金星村,无村管)
#
#  订单:⑨ 待交付(village 2,买家 6 王大山 / 卖家 4 张建国,无纠纷)
#        → 用来跑完整的纠纷申请→撤销→再审→调解
#       ④ 使用中(village 4,买家 4 / 卖家 6,金星村没有配村管)
#        → 用来验证跨村数据级权限,以及订单状态复原到"使用中"而不是"待交付"
#       ③ 待付款(买家 6 / 卖家 5)→ 用来验证"付款前不让申请纠纷"
#       ⑩ 纠纷中(village 2,带未决纠纷)→ 用来验证重复申请被拒
#
#  提现:1 李秀兰 2000 已打款 / 2 张建国 500 待审核 / 3 李秀兰 300 已驳回
#  余额:张建国 4240 / 李秀兰 2160 / 王大山 4000
# ══════════════════════════════════════════════════════════════════

ORDER_PAID = 9          # 待交付、village 2、无纠纷 —— 纠纷主流程用
ORDER_IN_USE = 4        # 使用中、village 4 —— 跨村权限 + 状态复原用
ORDER_UNPAID = 3        # 待付款 —— "付款前不能申请纠纷"用
ORDER_DISPUTED = 10     # 已有未决纠纷 —— 重复申请用
RES_FOR_CHAT = 1        # 张建国发布的拖拉机,用来验证会话去重

WD_OF_SELLER = 2        # 张建国 500 待审核(village 2)—— 村管审核路径用

print("=" * 70)
print("批次 6b —— 站内信 + 提现 + 纠纷 验证")
print("=" * 70)

super_admin = login("13800000000")
village_admin = login("13800000001")     # 战旗村支书,village 2
other_admin = login("13800000002")       # 先锋村主任,village 3
seller = login("13800000003")            # 张建国,village 2
seller2 = login("13800000004")           # 李秀兰,village 2
buyer = login("13800000007")             # 王大山,village 4
buyer2 = login("13800000005")            # 刘小明,village 2

check("七个测试账号全部登录成功",
      all([super_admin, village_admin, other_admin, seller, seller2, buyer, buyer2]),
      "有账号登录失败,后续用例不可信")


# ══════════════════════════════════════════════════════════════════
section("一、钱包与收益明细")
# ══════════════════════════════════════════════════════════════════

check_code("未登录访问钱包被拒(401)", call("GET", "/api/wallet/info"), 401)

_, res = call("GET", "/api/wallet/info", token=buyer)
wallet = res.get("data") or {}
check("王大山余额 = 4000(演示数据)", float(wallet.get("balance", -1)) == 4000.0,
      f"实际 {wallet.get('balance')}")
check("起提门槛由后端下发 = 10", float(wallet.get("minWithdrawAmount", -1)) == 10.0,
      f"实际 {wallet.get('minWithdrawAmount')}")
check("王大山没有提现记录,累计提现 = 0", float(wallet.get("totalWithdraw", -1)) == 0.0,
      f"实际 {wallet.get('totalWithdraw')}")

# 收益明细的核心不变量:所有正方向流水之和 == 累计收入。
# 这条同时验证了两件事:流水没串号(串了之后和会对不上),direction 没算反。
_, res = call("GET", "/api/wallet/earnings", token=seller,
              params={"pageNum": 1, "pageSize": 50})
rows = res.get("data", {}).get("rows", [])
check("张建国能看到自己的资金流水", len(rows) > 0, f"实际 {len(rows)} 行")
income_sum = sum(float(r.get("amount", 0)) for r in rows if r.get("direction") == 1)
_, res = call("GET", "/api/wallet/info", token=seller)
check("收益明细里正方向之和 == 钱包的累计收入",
      abs(income_sum - float(res["data"]["totalIncome"])) < 0.001,
      f"流水合计 {income_sum} vs 累计收入 {res['data']['totalIncome']}")
check("张建国的累计收入 = 840+1650+2250 = 4740",
      abs(float(res["data"]["totalIncome"]) - 4740.0) < 0.001,
      f"实际 {res['data']['totalIncome']}")

# 流水必须只看自己的 —— 别人的流水里有金额和订单号,
# 能看到就等于看到了别人在平台上做了多少生意
check("资金流水里不出现自己不是交易方的订单",
      all(r.get("orderNo") for r in rows), "存在 orderNo 为空的流水")

_, res = call("GET", "/api/wallet/withdraws", token=seller2, params={"pageNum": 1, "pageSize": 20})
wd_rows = res.get("data", {}).get("rows", [])
check("李秀兰只看到自己的 2 条提现记录", len(wd_rows) == 2, f"实际 {len(wd_rows)} 条")
statuses = sorted(w.get("status") for w in wd_rows)
check("两条分别是「已打款」和「已驳回」", statuses == [1, 2], f"实际 {statuses}")


# ══════════════════════════════════════════════════════════════════
section("二、提现前置校验")
# ══════════════════════════════════════════════════════════════════

WD_BASE = {"accountType": 1, "accountNo": "13800000007", "accountName": "王大山"}

res = call("POST", "/api/wallet/withdraw", dict(WD_BASE, amount=5), token=buyer)
check_code("提 5 元低于起提门槛,被拒(400)", res, 400)
check("提示里说明了门槛", "10" in str(res[1].get("message")), res[1].get("message"))

res = call("POST", "/api/wallet/withdraw", dict(WD_BASE, amount=0), token=buyer)
check_code("提 0 元被拒(400)", res, 400)

res = call("POST", "/api/wallet/withdraw", dict(WD_BASE, amount=4000.01), token=buyer)
check_code("提现额超过余额被拒(余额不足)", res, BALANCE_NOT_ENOUGH)
check("提示里带有当前可提现金额", "4000" in str(res[1].get("message")), res[1].get("message"))

res = call("POST", "/api/wallet/withdraw", dict(WD_BASE, amount=100, accountType=9), token=buyer)
check_code("收款方式不合法被拒(400)", res, 400)

res = call("POST", "/api/wallet/withdraw", dict(WD_BASE, amount=100, accountNo="   "), token=buyer)
check_code("收款账号为空被拒(参数校验)", res, 400)

check_code("未登录申请提现被拒(401)",
           call("POST", "/api/wallet/withdraw", dict(WD_BASE, amount=100)), 401)


# ══════════════════════════════════════════════════════════════════
section("三、并发提现(TC-09)—— 本批次最核心的一处")
# ══════════════════════════════════════════════════════════════════

# 余额 4000,两个请求同时各提 4000。
# 不加行锁时:两个请求都读到 4000、都判定"够",各自扣一次 → 余额 -4000。
# 加了 SELECT ... FOR UPDATE 之后:后一个会阻塞到前一个提交,
# 醒来时读到的是已经被扣过的 0,于是被正常拒绝。
_, res = call("GET", "/api/wallet/info", token=buyer)
balance_before = float(res["data"]["balance"])
check(f"并发前王大山余额 = {balance_before}", balance_before == 4000.0, f"实际 {balance_before}")

barrier = threading.Barrier(2)
results = {}


def concurrent_withdraw(tag):
    barrier.wait()   # 尽量让两个请求同时发出
    results[tag] = call("POST", "/api/wallet/withdraw",
                        dict(WD_BASE, amount=4000), token=buyer)


threads = [threading.Thread(target=concurrent_withdraw, args=(t,)) for t in ("A", "B")]
for t in threads:
    t.start()
for t in threads:
    t.join()

codes = sorted(r[1].get("code") for r in results.values())
check("两个并发提现请求恰好一个成功、一个失败", codes == [200, BALANCE_NOT_ENOUGH],
      f"实际返回码 {codes} —— 若两个都是 200,说明行锁没起作用")
check("失败的那个是「余额不足」而非别的错误",
      any(r[1].get("code") == BALANCE_NOT_ENOUGH for r in results.values()),
      str([r[1].get("message") for r in results.values()]))

_, res = call("GET", "/api/wallet/info", token=buyer)
balance_after = float(res["data"]["balance"])
check("并发后余额 = 0,没有变成负数", balance_after == 0.0, f"实际 {balance_after}")

_, res = call("GET", "/api/wallet/withdraws", token=buyer,
              params={"pageNum": 1, "pageSize": 20, "status": 0})
pending = res.get("data", {}).get("rows", [])
check("只落下一张 4000 元的待审核提现单", len(pending) == 1 and float(pending[0]["amount"]) == 4000.0,
      f"实际 {len(pending)} 条")
concurrent_wd_id = pending[0]["id"] if pending else None

_, res = call("GET", "/api/wallet/info", token=buyer)
check("在途提现被计入 pendingWithdraw",
      abs(float(res["data"]["pendingWithdraw"]) - 4000.0) < 0.001,
      f"实际 {res['data']['pendingWithdraw']}")


# ══════════════════════════════════════════════════════════════════
section("四、提现审核 —— 权限、理由必填、乐观锁")
# ══════════════════════════════════════════════════════════════════

check_code("村民调用后台提现审核被拒(403,按钮级权限)",
           call("PUT", f"/api/admin/withdraw/{concurrent_wd_id}/audit",
                {"pass": True}, token=seller), 403)

# 这张提现单归属金星村(village 4),战旗村支书管不到
check_code("战旗村支书审核金星村的提现单被拒(403,数据级权限)",
           call("PUT", f"/api/admin/withdraw/{concurrent_wd_id}/audit",
                {"pass": True}, token=village_admin), 403)

# 张建国那张 500 元的待审核提现单在战旗村,村管能处理
check_code("驳回时不填审核意见被拒(400)",
           call("PUT", f"/api/admin/withdraw/{WD_OF_SELLER}/audit",
                {"pass": False}, token=village_admin), 400)

_, res = call("GET", "/api/wallet/info", token=seller)
seller_balance_before = float(res["data"]["balance"])
check_code("村管通过打款", call("PUT", f"/api/admin/withdraw/{WD_OF_SELLER}/audit",
                               {"pass": True, "remark": "收款信息与实名一致"},
                               token=village_admin), 200)

# 打款不再动余额 —— 钱在申请时就扣了
_, res = call("GET", "/api/wallet/info", token=seller)
check("打款后余额不变(钱在申请时已扣)",
      abs(float(res["data"]["balance"]) - seller_balance_before) < 0.001,
      f"{seller_balance_before} → {res['data']['balance']}")

check_code("对同一条提现再次审核被拒(乐观锁)",
           call("PUT", f"/api/admin/withdraw/{WD_OF_SELLER}/audit",
                {"pass": True}, token=village_admin), 400)

# 超管驳回王大山那笔 4000 → 钱必须退回余额。
# 这条同时验证了"驳回退款"和"超管能跨村管辖"
check_code("超管驳回王大山的提现单",
           call("PUT", f"/api/admin/withdraw/{concurrent_wd_id}/audit",
                {"pass": False, "remark": "商户号异常,请核对后重新提交"},
                token=super_admin), 200)
_, res = call("GET", "/api/wallet/info", token=buyer)
check("被驳回后 4000 元退回余额", float(res["data"]["balance"]) == 4000.0,
      f"实际 {res['data']['balance']}")

check_code("已驳回的提现单再次驳回被拒(乐观锁,防重复退款)",
           call("PUT", f"/api/admin/withdraw/{concurrent_wd_id}/audit",
                {"pass": False, "remark": "再驳回一次"}, token=super_admin), 400)

_, res = call("GET", "/api/wallet/withdraws", token=buyer, params={"pageNum": 1, "pageSize": 20})
check("王大山收到了提现结果通知", res.get("code") == 200)


# ══════════════════════════════════════════════════════════════════
section("五、会话 —— 双方 ID 归一化")
# ══════════════════════════════════════════════════════════════════

check_code("未登录访问会话列表被拒(401)", call("GET", "/api/chat/conversations"), 401)

res = call("POST", "/api/chat/start", {"targetUserId": 8, "resourceId": RES_FOR_CHAT}, token=seller)
conv_id = res[1].get("data")
check("张建国发起与刘小明的会话成功", res[1].get("code") == 200 and conv_id, str(res[1]))

# 这一条是会话表能不能用的分水岭:反向发起必须命中同一个会话。
# 不归一化的话 (4,8) 和 (8,4) 会被当成两条,双方各说各话。
res = call("POST", "/api/chat/start", {"targetUserId": 4, "resourceId": RES_FOR_CHAT}, token=buyer2)
check("刘小明反向发起,拿到的是同一个会话 ID(归一化生效)",
      res[1].get("data") == conv_id,
      f"张建国拿到 {conv_id},刘小明拿到 {res[1].get('data')} —— 不相等说明会话被拆成了两条")

res = call("POST", "/api/chat/start", {"targetUserId": 8, "resourceId": 3}, token=seller)
check("换一个资源发起,得到新会话(资源是会话的一部分)",
      res[1].get("data") and res[1].get("data") != conv_id, str(res[1]))
second_conv_id = res[1].get("data")

check_code("和自己发起会话被拒(400)",
           call("POST", "/api/chat/start", {"targetUserId": 4}, token=seller), 400)
check_code("对方用户不存在(404)",
           call("POST", "/api/chat/start", {"targetUserId": 999999}, token=seller), 404)
check_code("关联资源不存在(404)",
           call("POST", "/api/chat/start", {"targetUserId": 8, "resourceId": 999999},
                token=seller), 404)


# ══════════════════════════════════════════════════════════════════
section("六、聊天 —— 未读数与敏感词两级处理")
# ══════════════════════════════════════════════════════════════════

_, res = call("GET", "/api/chat/unread", token=buyer2)
unread_before = res.get("data") or 0

check_code("张建国发一条普通消息",
           call("POST", "/api/chat/send",
                {"conversationId": conv_id, "content": "三轮车周六能提"},
                token=seller), 200)

_, res = call("GET", "/api/chat/unread", token=buyer2)
check("对方未读数 +1", (res.get("data") or 0) == unread_before + 1,
      f"{unread_before} → {res.get('data')}")

check_code("不在会话里的人发消息被拒(403)",
           call("POST", "/api/chat/send",
                {"conversationId": conv_id, "content": "插一句"}, token=buyer), 403)
check_code("不在会话里的人读消息被拒(403)",
           call("GET", "/api/chat/messages", token=buyer, params={"conversationId": conv_id}), 403)

# ── 替换类敏感词:发出去、存原文、读到的是打码版 ──────────────
check_code("含「加微信」的消息发送成功(替换类打码放行)",
           call("POST", "/api/chat/send",
                {"conversationId": conv_id, "content": "你直接加微信跟我说吧"},
                token=seller), 200)

_, res = call("GET", "/api/chat/messages", token=buyer2, params={"conversationId": conv_id})
msgs = res.get("data") or []
last = msgs[-1] if msgs else {}
check("双方读到的是打码后的内容", "加微信" not in str(last.get("content")),
      f"实际内容 {last.get('content')}")
check("打码后的内容里出现掩码字符", "*" in str(last.get("content")),
      f"实际内容 {last.get('content')}")
check("该消息带 isFiltered 标记", last.get("isFiltered") == 1,
      f"实际 {last.get('isFiltered')}")

# ── 拦截类敏感词:整条拒绝 ────────────────────────────────────
res = call("POST", "/api/chat/send",
           {"conversationId": conv_id, "content": "要不我们赌博吧"}, token=seller)
check_code("含「赌博」的消息被整条拒绝", res, SENSITIVE_CONTENT)
check("拒绝提示里点名了命中的词", "赌博" in str(res[1].get("message")), res[1].get("message"))

res = call("POST", "/api/chat/send",
           {"conversationId": conv_id, "content": "啊" * 1001}, token=seller)
check_code("超过 1000 字的消息被拒(400)", res, 400)

check_code("读取不存在的会话(404)",
           call("GET", "/api/chat/messages", token=seller, params={"conversationId": 999999}), 404)

check_code("标记已读", call("PUT", f"/api/chat/{conv_id}/read", token=buyer2), 200)
_, res = call("GET", "/api/chat/unread", token=buyer2)
# 【别写成 (res.get("data") or -1) == 0】
# 未读数是 0 的时候,`0 or -1` 在 Python 里求值成 -1,断言必然失败 ——
# 而这恰恰是唯一需要断言的那一次。or 兜底只适合"None 视为 0/空"的场景,
# 碰到 0 本身是合法值时就是个陷阱。
check("标记已读后未读数归零", res.get("data") == 0, f"实际 {res.get('data')}")

_, res = call("GET", "/api/chat/conversations", token=seller)
convs = res.get("data") or []
check("会话列表里能看到刚聊的这条", any(c.get("id") == conv_id for c in convs),
      f"共 {len(convs)} 条会话")
check("会话列表带出了对方的姓名与最后一条消息",
      all(c.get("peerName") is not None for c in convs), "存在 peerName 为空的会话")


# ══════════════════════════════════════════════════════════════════
section("七、纠纷 —— 申请窗口、存档复原、撤销")
# ══════════════════════════════════════════════════════════════════

check_code("未登录申请纠纷被拒(401)",
           call("POST", "/api/dispute/apply",
                {"orderId": ORDER_PAID, "disputeType": 3, "description": "x"}), 401)

# 付款前不让申请 —— 那时买家直接取消订单就能退回,不存在"争议"
res = call("POST", "/api/dispute/apply",
           {"orderId": ORDER_UNPAID, "disputeType": 3, "description": "货不对板"},
           token=buyer)
check_code("未付款的订单不能申请纠纷", res, ORDER_STATUS_INVALID)

# 不是交易双方的人不能申请
res = call("POST", "/api/dispute/apply",
           {"orderId": ORDER_PAID, "disputeType": 3, "description": "货不对板"},
           token=seller2)
check_code("非交易双方申请纠纷被拒(403)", res, 403)

res = call("POST", "/api/dispute/apply",
           {"orderId": ORDER_PAID, "disputeType": 3,
            "description": "说好的是 500 公斤粮仓,到场的是 300 公斤,少的部分要求补偿"},
           token=buyer)
check_code("待交付订单的买家申请纠纷成功", res, 200)
dispute_a = res[1].get("data")

res = call("POST", "/api/dispute/apply",
           {"orderId": ORDER_PAID, "disputeType": 3, "description": "再申请一次"},
           token=buyer)
check_code("同一订单已有未决纠纷,重复申请被拒", res, 400)
# 这里特意断言提示语:未决期间订单状态被挂成"纠纷中",如果状态判断
# 排在"已存在未决纠纷"判断之前,用户看到的会是"订单当前状态为
# 「纠纷中」,已付款且未完成的订单才能申请纠纷" —— 那句话对一个
# 正在重复点击的人来说毫无意义。断言提示语才能守住这个顺序。
check("拒绝理由是「已有正在处理的纠纷」而不是「订单状态不对」",
      "正在处理的纠纷" in str(res[1].get("message")), res[1].get("message"))

res = call("POST", "/api/dispute/apply",
           {"orderId": ORDER_DISPUTED, "disputeType": 1, "description": "再次损坏"},
           token=buyer)
check_code("演示数据里那张纠纷中的订单也不能再申请", res, 400)
check("同样给出的是「已有正在处理的纠纷」", "正在处理的纠纷" in str(res[1].get("message")),
      res[1].get("message"))

check_code("纠纷描述超过 1000 字被拒(400)",
           call("POST", "/api/dispute/apply",
                {"orderId": ORDER_IN_USE, "disputeType": 1, "description": "啊" * 1001},
                token=seller), 400)

# ── 详情可见性:正是本次修掉的那个越权读取 ────────────────────
check_code("与纠纷无关的村民读详情被拒(403)",
           call("GET", f"/api/dispute/{dispute_a}", token=seller2), 403)
check_code("被申请人可以读详情", call("GET", f"/api/dispute/{dispute_a}", token=seller), 200)

_, res = call("GET", f"/api/dispute/{dispute_a}", token=seller)
vo = res.get("data") or {}
check("详情里记录了纠纷前的订单状态(待交付)", vo.get("orderStatusBefore") == 2,
      f"实际 {vo.get('orderStatusBefore')}")
check("被申请人看到 canCancel=false(他不是申请人)", vo.get("canCancel") is False,
      f"实际 {vo.get('canCancel')}")

_, res = call("GET", f"/api/dispute/{dispute_a}", token=village_admin)
check("有权管辖的村管看到 canHandle=true", res["data"].get("canHandle") is True,
      f"实际 {res['data'].get('canHandle')}")

# ── 撤销 ──────────────────────────────────────────────────────
check_code("非申请人撤销被拒(403)",
           call("PUT", f"/api/dispute/{dispute_a}/cancel", token=seller), 403)
check_code("申请人本人撤销成功",
           call("PUT", f"/api/dispute/{dispute_a}/cancel", token=buyer), 200)

_, res = call("GET", f"/api/order/detail/{ORDER_PAID}", token=buyer)
check("撤销后订单恢复到「待交付」(2),没有卡在纠纷中",
      res["data"].get("orderStatus") == 2, f"实际 {res['data'].get('orderStatus')}")

check_code("已撤销的纠纷不能再次撤销(400)",
           call("PUT", f"/api/dispute/{dispute_a}/cancel", token=buyer), 400)


# ══════════════════════════════════════════════════════════════════
section("八、纠纷处理 —— 状态复原与数据级权限")
# ══════════════════════════════════════════════════════════════════

# 从「待交付」再申请一次,这次走完调解流程
res = call("POST", "/api/dispute/apply",
           {"orderId": ORDER_PAID, "disputeType": 3,
            "description": "粮仓容量不符,要求补送或按差额补偿"}, token=buyer)
check_code("再次申请纠纷成功(上一条已结束,不构成「同时未决」)", res, 200)
dispute_b = res[1].get("data")

_, res = call("GET", f"/api/order/detail/{ORDER_PAID}", token=buyer)
check("申请后订单被挂起为「纠纷中」(6)", res["data"].get("orderStatus") == 6,
      f"实际 {res['data'].get('orderStatus')}")

check_code("村民调用后台处理纠纷被拒(403,按钮级权限)",
           call("PUT", f"/api/admin/dispute/{dispute_b}/handle",
                {"status": 2, "handleResult": "调解完成"}, token=buyer), 403)
check_code("别村的管理员处理被拒(403,数据级权限)",
           call("PUT", f"/api/admin/dispute/{dispute_b}/handle",
                {"status": 2, "handleResult": "调解完成"}, token=other_admin), 403)
check_code("处理结果只能填「已调解/已关闭」(参数校验)",
           call("PUT", f"/api/admin/dispute/{dispute_b}/handle",
                {"status": 4, "handleResult": "乱填"}, token=village_admin), 400)

check_code("本村管理员调解结案",
           call("PUT", f"/api/admin/dispute/{dispute_b}/handle",
                {"status": 2,
                 "handleResult": "经核实为容量标注理解不一致,已协调卖方补送粮仓,双方认可"},
                token=village_admin), 200)

_, res = call("GET", f"/api/order/detail/{ORDER_PAID}", token=buyer)
check("调解后订单恢复履约(回到「待交付」2)",
      res["data"].get("orderStatus") == 2, f"实际 {res['data'].get('orderStatus')}")

check_code("同一条纠纷再次处理被拒(乐观锁)",
           call("PUT", f"/api/admin/dispute/{dispute_b}/handle",
                {"status": 3, "handleResult": "再处理一次"}, token=village_admin), 400)

# ── 跨村:金星村的订单,战旗村支书管不了,超管能管 ─────────────
res = call("POST", "/api/dispute/apply",
           {"orderId": ORDER_IN_USE, "disputeType": 1,
            "description": "老宅西厢房门窗有破损,退租时要求扣部分押金"}, token=seller)
check_code("使用中的订单也可以申请纠纷", res, 200)
dispute_c = res[1].get("data")

check_code("别村管理员处理被拒(403,数据级权限)",
           call("PUT", f"/api/admin/dispute/{dispute_c}/handle",
                {"status": 2, "handleResult": "跨村处理"}, token=village_admin), 403)
check_code("超管可以跨村处理(不受村组限制)",
           call("PUT", f"/api/admin/dispute/{dispute_c}/handle",
                {"status": 3,
                 "handleResult": "双方各执一词且无照片证据,建议线下协商,本案关闭"},
                token=super_admin), 200)

# 关键:订单原状态是「使用中」(3),复原必须回到 3 而不是一律退回 2
_, res = call("GET", f"/api/order/detail/{ORDER_IN_USE}", token=seller)
check("订单复原到「使用中」(3)而不是「待交付」—— order_status_before 生效",
      res["data"].get("orderStatus") == 3, f"实际 {res['data'].get('orderStatus')}")

# ── 后台列表按数据范围过滤 ────────────────────────────────────
_, res = call("GET", "/api/admin/dispute/list", token=village_admin,
              params={"pageNum": 1, "pageSize": 50})
admin_rows = res.get("data", {}).get("rows", [])
check("村管的纠纷列表只含本村(village 2)",
      all(r.get("villageId") == 2 for r in admin_rows),
      f"出现了非本村纠纷 {[r.get('villageId') for r in admin_rows]}")

_, res = call("GET", "/api/admin/dispute/list", token=village_admin,
              params={"pageNum": 1, "pageSize": 50})
check("列表里带出了 applicantName / respondentName(调解要知道找谁)",
      all(r.get("applicantName") for r in (res.get("data", {}).get("rows") or [])))


# ══════════════════════════════════════════════════════════════════
section("九、按订单调取聊天记录 —— 敏感信息的权限边界")
# ══════════════════════════════════════════════════════════════════

# 这是全系统唯一能读到别人私聊的接口,权限必须收紧到"管辖该村的管理员"。
# 交易双方本人也不行 —— 他有自己的会话列表,不需要这个后门。
check_code("交易双方本人也不能用这个接口(403)",
           call("GET", "/api/chat/order-messages", token=buyer,
                params={"orderId": ORDER_DISPUTED}), 403)
check_code("未登录访问被拒(401)",
           call("GET", "/api/chat/order-messages", params={"orderId": ORDER_DISPUTED}), 401)
check_code("别村管理员访问被拒(403,数据级权限)",
           call("GET", "/api/chat/order-messages", token=other_admin,
                params={"orderId": ORDER_DISPUTED}), 403)
check_code("不传订单号被拒(400)",
           call("GET", "/api/chat/order-messages", token=village_admin), 400)

_, res = call("GET", "/api/chat/order-messages", token=village_admin,
              params={"orderId": ORDER_DISPUTED, "pageNum": 1, "pageSize": 50})
msgs = res.get("data", {}).get("rows", [])
check("本村管理员可以调取聊天记录", len(msgs) > 0, f"实际 {len(msgs)} 条")

# 管理员看到的是<b>原文</b> —— 这是「保留事实、控制展示」的落点:
# "他到底有没有把交易往平台外引"本身就是要判的事,抹掉就判不了
filtered = [m for m in msgs if m.get("isFiltered") == 1]
check("聊天记录里能查到被过滤过的消息", len(filtered) > 0, "演示数据里应有 1 条")
if filtered:
    check("管理员看到的是原文,不是打码版", "加微信" in str(filtered[0].get("content")),
          f"实际 {filtered[0].get('content')}")

check_code("超管也能调取", call("GET", "/api/chat/order-messages", token=super_admin,
                              params={"orderId": ORDER_DISPUTED}), 200)


# ══════════════════════════════════════════════════════════════════
section("十、站内信 —— 纠纷与提现结果的通知")
# ══════════════════════════════════════════════════════════════════

_, res = call("GET", "/api/notice/list", token=buyer, params={"pageNum": 1, "pageSize": 20})
notices = res.get("data", {}).get("rows", [])
titles = [n.get("title", "") for n in notices]
check("王大山收到了纠纷处理结果的通知",
      any("纠纷" in t for t in titles), f"实际通知标题 {titles}")
check("通知里带出了跳转业务类型(bizType)",
      all(n.get("bizType") is not None for n in notices), "存在 bizType 为空的站内信")

_, res = call("GET", "/api/notice/list", token=seller, params={"pageNum": 1, "pageSize": 20})
titles = [n.get("title", "") for n in res.get("data", {}).get("rows", [])]
check("张建国收到了「订单被申请了纠纷」的通知",
      any("纠纷" in t for t in titles), f"实际通知标题 {titles}")

_, res = call("GET", "/api/notice/list", token=buyer, params={"pageNum": 1, "pageSize": 20})
titles = [n.get("title", "") for n in res.get("data", {}).get("rows", [])]
check("王大山收到了提现被驳回的通知",
      any("提现" in t for t in titles), f"实际通知标题 {titles}")


# ══════════════════════════════════════════════════════════════════
print()
print("=" * 70)
print(f"结果: {passed} 项通过,{failed} 项失败")
print("=" * 70)
if failed == 0:
    print()
    print("提示:本脚本创建了真实的提现单、会话与纠纷记录,如需恢复演示数据请执行:")
    print("      mysql -uroot -proot < sql/05_mock_data.sql")
sys.exit(1 if failed else 0)
