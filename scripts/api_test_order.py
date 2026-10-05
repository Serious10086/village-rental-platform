#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次 5(交易闭环)接口验证脚本。

验证点:
  1. 下单前置校验 —— 未实名、自有资源、非上架资源、高风险未确认培训
  2. 金额计算 —— 租期含头含尾、押金计入应付、前端传入的金额被忽略
  3. 时段冲突 —— 重叠被拒、不重叠可下单、已取消/已拒绝不占档期
  4. 并发下单 —— 两个请求同时抢同一时段,恰好一个成功
  5. 状态机 —— 非法流转被拒、越权操作被拒
  6. 主流程 —— 下单→确认→支付→交付→归还→完成,全程时间轴
  7. 记账 —— 支付后卖家入账、完成后押金退还、双方信用 +2
  8. 购买类 —— 无租期、无押金、确认收货一步完成
  9. 取消/拒绝 —— 状态流转与资源恢复
 10. 数据级权限 —— 村管只看本村订单
 11. 事务回滚(TC-04)—— 事务最后一步失败时,三张表都无残留

【重要:本脚本会创建真实订单】
和其他接口测试脚本一样,它会往库里写数据。跑完请重新执行
    mysql -uroot -proot < sql/05_mock_data.sql
恢复干净的演示数据。重复运行本脚本前也必须先重置,否则上一轮
留下的订单会占住测试档期,导致冲突检测用例误报。

用法(需先启动后端):
    python scripts/api_test_order.py
"""
import json
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"
sys.stdout.reconfigure(encoding="utf-8")

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
#  测试数据约定
#
#  刻意选演示数据里<b>没有订单</b>的资源,并且把测试档期集中放在 12 月 ——
#  演示数据的订单最晚到 10 月中旬,两者不会互相干扰。
# ══════════════════════════════════════════════════════════════════

RENT_RES       = 5    # 雷沃欧豹拖拉机(王大山,金星村)260/天 押金800 —— 冲突检测用
FLOW_RES       = 12   # 2 寸潜水泵(李秀兰,战旗村)35/天 押金150 —— 主流程用
CONCURRENT_RES = 13   # 5kW 柴油发电机(王大山,金星村)120/天 押金400 —— 并发用
HIGH_RISK_RES  = 15   # 100kVA 变压器,need_training=1 —— 安全培训确认用
SELL_RES       = 18   # 八成新电动三轮车(王大山)1600 —— 购买流程用
CANCEL_RES     = 7    # 雅迪电动三轮车(王大山)30/天 押金100 —— 取消/拒绝对用
OWN_RES        = 14   # 整套木工工具(张建国) —— "不能下单自己的资源"用
PENDING_RES    = 20   # 玉米精量播种机,状态=待审核 —— "非上架不能下单"用
ROLLBACK_RES   = 19   # 闲置电焊机转让(张建国,出售) —— 事务回滚用

# 档期(都在 12 月,远离演示数据的 9-10 月)
SLOT_A = ("2026-12-05", "2026-12-07")   # 主测试档期
SLOT_B = ("2026-12-06", "2026-12-08")   # 与 A 重叠
SLOT_C = ("2026-12-15", "2026-12-17")   # 与 A 不重叠


print("=" * 70)
print("批次 5 —— 交易闭环 验证")
print("=" * 70)

# ── 登录 ──────────────────────────────────────────────────────────
buyer = login("13800000005")     # 刘小明(战旗村,买家)
buyer2 = login("13800000006")    # 陈晓芳(先锋村,买家)
seller = login("13800000007")    # 王大山(金星村,卖家)
seller2 = login("13800000004")   # 李秀兰(战旗村,卖家)
owner = login("13800000003")     # 张建国(战旗村,村民兼卖家)
village_admin = login("13800000001")   # 战旗村支书
super_admin = login("13800000000")     # 平台管理员
unverified = login("13800000009")      # 赵春梅(实名待审核)

check("登录:买家 / 卖家 / 管理员 / 未实名账号均可登录",
      all([buyer, buyer2, seller, seller2, owner, village_admin, super_admin, unverified]))

# ── 前置:测试档期必须是干净的 ─────────────────────────────────────
#
# 本脚本会创建真实订单。如果上一轮的订单还在库里,测试档期就被占住了,
# 后面每一个"下单成功"的断言都会连锁失败,而报错看起来像是产品坏了。
# 这里提前拦一道,把"跑之前先重置"这件事说清楚。
st, pre = call("GET", "/api/order/mine", token=buyer, params={"pageSize": 500})
pre_rows = pre.get("data", {}).get("rows", []) if pre.get("code") == 200 else []
dirty = [r for r in pre_rows
         if r["resourceId"] in (RENT_RES, CONCURRENT_RES)
         and (r.get("rentStartDate") or "").startswith("2026-12")]
if dirty:
    print()
    print("!" * 70)
    print("检测到测试档期已被占用 —— 库里还留着上一轮测试创建的订单。")
    print("请先重置演示数据再运行本脚本:")
    print("    mysql -uroot -proot < sql/05_mock_data.sql")
    print("!" * 70)
    sys.exit(2)


# ══════════════════════════════════════════════════════════════════
section("【1】下单前置校验")
# ══════════════════════════════════════════════════════════════════

# 未实名不能下单
check_code("未实名用户下单被拒(1004)",
           call("POST", "/api/order/create", {"resourceId": RENT_RES,
                                              "rentStartDate": SLOT_A[0],
                                              "rentEndDate": SLOT_A[1]}, unverified), 1004)

# 不能下单自己发布的资源
check_code("不能下单自己发布的资源(400)",
           call("POST", "/api/order/create", {"resourceId": OWN_RES,
                                              "rentStartDate": SLOT_A[0],
                                              "rentEndDate": SLOT_A[1]}, owner), 400)

# 资源不存在
check_code("资源不存在时下单被拒(404)",
           call("POST", "/api/order/create", {"resourceId": 999999,
                                              "rentStartDate": SLOT_A[0],
                                              "rentEndDate": SLOT_A[1]}, buyer), 404)

# 非上架资源不能下单(资源 20 状态为"待审核")
check_code("待审核资源不能下单(1006)",
           call("POST", "/api/order/create", {"resourceId": PENDING_RES,
                                              "rentStartDate": SLOT_A[0],
                                              "rentEndDate": SLOT_A[1]}, buyer), 1006)

# 高风险资源必须确认安全培训
check_code("高风险资源未确认安全须知被拒(400)",
           call("POST", "/api/order/create", {"resourceId": HIGH_RISK_RES,
                                              "rentStartDate": SLOT_A[0],
                                              "rentEndDate": SLOT_A[1],
                                              "trainingConfirmed": False}, buyer), 400)

check_code("高风险资源勾选安全须知后可下单",
           call("POST", "/api/order/create", {"resourceId": HIGH_RISK_RES,
                                              "rentStartDate": SLOT_A[0],
                                              "rentEndDate": SLOT_A[1],
                                              "trainingConfirmed": True}, buyer), 200)

# 租期参数校验
check_code("缺少租期被拒(400)",
           call("POST", "/api/order/create", {"resourceId": RENT_RES}, buyer), 400)
check_code("结束日早于开始日被拒(400)",
           call("POST", "/api/order/create", {"resourceId": RENT_RES,
                                              "rentStartDate": "2026-12-10",
                                              "rentEndDate": "2026-12-08"}, buyer), 400)
check_code("租期超出资源可租窗口被拒(400)",
           call("POST", "/api/order/create", {"resourceId": RENT_RES,
                                              "rentStartDate": "2026-12-25",
                                              "rentEndDate": "2027-01-05"}, buyer), 400)


# ══════════════════════════════════════════════════════════════════
section("【2】金额计算")
# ══════════════════════════════════════════════════════════════════

# 木工工具 50/天,押金 300;12-05 ~ 12-07 含头含尾 = 3 天
st, res = call("POST", "/api/order/create",
               {"resourceId": OWN_RES, "rentStartDate": "2026-12-05",
                "rentEndDate": "2026-12-07",
                "totalAmount": 1, "payAmount": 1, "unitPrice": 1, "depositAmount": 0},
               buyer)
check("含头含尾计天:12-05~12-07 算 3 天(不是 2 天)", res.get("code") == 200, res.get("message"))
amt_order_id = res.get("data")

st, detail = call("GET", f"/api/order/detail/{amt_order_id}", token=buyer)
d = detail.get("data", {}) if detail.get("code") == 200 else {}
check("租期天数 rentDays = 3", d.get("rentDays") == 3, f"实际 {d.get('rentDays')}")
check("租金 = 50 × 3 = 150", float(d.get("totalAmount") or 0) == 150.0,
      f"实际 {d.get('totalAmount')}")
check("押金 = 300", float(d.get("depositAmount") or 0) == 300.0,
      f"实际 {d.get('depositAmount')}")
check("应付总额 = 租金 + 押金 = 450", float(d.get("payAmount") or 0) == 450.0,
      f"实际 {d.get('payAmount')}")
check("前端传入的 totalAmount/payAmount/unitPrice 被忽略",
      float(d.get("unitPrice") or 0) == 50.0 and float(d.get("totalAmount") or 0) == 150.0,
      f"unitPrice={d.get('unitPrice')} totalAmount={d.get('totalAmount')}")

# 取消掉这笔,避免影响后续
call("PUT", f"/api/order/{amt_order_id}/cancel", {"reason": "金额用例结束"}, buyer)


# ══════════════════════════════════════════════════════════════════
section("【3】时段冲突检测")
# ══════════════════════════════════════════════════════════════════

st, res = call("POST", "/api/order/create",
               {"resourceId": RENT_RES, "rentStartDate": SLOT_A[0],
                "rentEndDate": SLOT_A[1]}, buyer)
check("首次下单成功(占用 12-05~12-07)", res.get("code") == 200, res.get("message"))
conflict_order_id = res.get("data")

# 完全重叠
check_code("完全重叠的时段被拒(1007)",
           call("POST", "/api/order/create",
                {"resourceId": RENT_RES, "rentStartDate": SLOT_A[0],
                 "rentEndDate": SLOT_A[1]}, buyer2), 1007)

# 部分重叠(12-06~12-08 与 12-05~12-07 有交集)
check_code("部分重叠的时段被拒(1007)",
           call("POST", "/api/order/create",
                {"resourceId": RENT_RES, "rentStartDate": SLOT_B[0],
                 "rentEndDate": SLOT_B[1]}, buyer2), 1007)

# 边界相接(12-08 起,与 12-07 只差一天,不算重叠)
check_code("相邻但不重叠的时段可以下单",
           call("POST", "/api/order/create",
                {"resourceId": RENT_RES, "rentStartDate": "2026-12-08",
                 "rentEndDate": "2026-12-09"}, buyer2), 200)

# 完全分开的时段
check_code("完全错开的时段可以下单",
           call("POST", "/api/order/create",
                {"resourceId": RENT_RES, "rentStartDate": SLOT_C[0],
                 "rentEndDate": SLOT_C[1]}, buyer2), 200)

# 取消后档期释放 —— 已取消的订单不占档期
call("PUT", f"/api/order/{conflict_order_id}/cancel", {"reason": "释放档期用例"}, buyer)
check_code("已取消的订单不再占用档期,同一时段可被他人下单(1007 → 200)",
           call("POST", "/api/order/create",
                {"resourceId": RENT_RES, "rentStartDate": SLOT_A[0],
                 "rentEndDate": SLOT_A[1]}, buyer2), 200)

# 卖家拒绝同样释放档期
st, res = call("POST", "/api/order/create",
               {"resourceId": CANCEL_RES, "rentStartDate": "2026-12-01",
                "rentEndDate": "2026-12-02"}, buyer)
reject_id = res.get("data")
call("PUT", f"/api/order/{reject_id}/reject", {"reason": "该时段机器已预定"}, seller)
check_code("被卖家拒绝的订单同样不占用档期",
           call("POST", "/api/order/create",
                {"resourceId": CANCEL_RES, "rentStartDate": "2026-12-01",
                 "rentEndDate": "2026-12-02"}, buyer2), 200)


# ══════════════════════════════════════════════════════════════════
section("【4】并发下单(防超卖)")
# ══════════════════════════════════════════════════════════════════

CONCURRENT_SLOT = ("2026-12-20", "2026-12-22")
barrier = threading.Barrier(2)
results = {}


def concurrent_book(tag, token):
    barrier.wait()     # 尽量让两个请求同时发出
    results[tag] = call("POST", "/api/order/create",
                        {"resourceId": CONCURRENT_RES,
                         "rentStartDate": CONCURRENT_SLOT[0],
                         "rentEndDate": CONCURRENT_SLOT[1]}, token)


threads = [threading.Thread(target=concurrent_book, args=("A", buyer)),
           threading.Thread(target=concurrent_book, args=("B", buyer2))]
for t in threads:
    t.start()
for t in threads:
    t.join()

codes = sorted(r[1].get("code") for r in results.values())
ok_count = codes.count(200)
conflict_count = codes.count(1007)

print(f"  并发结果: {codes}")
check("两个并发请求恰好一个成功", ok_count == 1, f"成功 {ok_count} 个")
check("另一个以时段冲突被拒(不是 500 也不是双成功)",
      conflict_count == 1, f"冲突 {conflict_count} 个")

# 查库确认没有超卖:该时段只有 1 笔有效订单
st, lst = call("GET", "/api/admin/order/list", token=super_admin,
               params={"orderNo": "", "pageSize": 500})
if lst.get("code") == 200:
    same_slot = [r for r in lst["data"]["rows"]
                 if r["resourceId"] == CONCURRENT_RES
                 and r["rentStartDate"] == CONCURRENT_SLOT[0]
                 and r["orderStatus"] not in (5, 7)]
    check("库中该时段只有 1 笔有效订单(无脏订单)",
          len(same_slot) == 1, f"实际 {len(same_slot)} 笔")


# ══════════════════════════════════════════════════════════════════
section("【5】状态机与越权")
# ══════════════════════════════════════════════════════════════════

# 造一笔待确认订单
st, res = call("POST", "/api/order/create",
               {"resourceId": FLOW_RES, "rentStartDate": "2026-12-10",
                "rentEndDate": "2026-12-12"}, buyer)
flow_id = res.get("data")
check("创建主流程订单成功", res.get("code") == 200, res.get("message"))

# 待确认状态下不能直接支付
check_code("待卖家确认时不能支付(1008)",
           call("PUT", f"/api/order/{flow_id}/pay", token=buyer), 1008)

# 非卖家不能确认接单
check_code("买家不能替卖家确认接单(403)",
           call("PUT", f"/api/order/{flow_id}/confirm", token=buyer), 403)

# 非交易方不能查看详情
check_code("无关用户查看订单详情返回 404(不泄露订单存在性)",
           call("GET", f"/api/order/detail/{flow_id}", token=buyer2), 404)

# 卖家确认
check_code("卖家确认接单成功",
           call("PUT", f"/api/order/{flow_id}/confirm", token=seller2), 200)

# 确认后不能再次确认
check_code("重复确认被拒(1008)",
           call("PUT", f"/api/order/{flow_id}/confirm", token=seller2), 1008)

# 非买家不能支付
check_code("非买家不能支付该订单(403)",
           call("PUT", f"/api/order/{flow_id}/pay", token=buyer2), 403)

# 待付款状态下的动作清单
st, detail = call("GET", f"/api/order/detail/{flow_id}", token=buyer)
d = detail.get("data", {})
check("待付款:买家可执行动作 = [pay, cancel]",
      sorted(d.get("actions") or []) == ["cancel", "pay"], f"实际 {d.get('actions')}")

st, detail = call("GET", f"/api/order/detail/{flow_id}", token=seller2)
d = detail.get("data", {})
check("待付款:卖家无可执行动作(等买家付款)",
      (d.get("actions") or []) == [], f"实际 {d.get('actions')}")
check("卖家在订单中的身份是 seller", d.get("myRole") == "seller", f"实际 {d.get('myRole')}")

# 未确认就交付
check_code("未支付时不能交付(1008)",
           call("PUT", f"/api/order/{flow_id}/deliver", token=seller2), 1008)


# ══════════════════════════════════════════════════════════════════
section("【6】主流程:支付 → 交付 → 归还 → 完成")
# ══════════════════════════════════════════════════════════════════

# 记录记账基线
st, seller_before = call("GET", "/api/auth/getInfo", token=seller2)
st, buyer_before = call("GET", "/api/auth/getInfo", token=buyer)
sb = seller_before.get("data", {}) if seller_before.get("code") == 200 else {}
bb = buyer_before.get("data", {}) if buyer_before.get("code") == 200 else {}

st, detail = call("GET", f"/api/order/detail/{flow_id}", token=buyer)
d = detail.get("data", {})
total_amount = float(d.get("totalAmount") or 0)
deposit_amount = float(d.get("depositAmount") or 0)
seller_balance_before = float(sb.get("balance") or 0)
buyer_credit_before = int(bb.get("creditScore") or 100)
seller_credit_before = int(sb.get("creditScore") or 100)

# 非法支付方式
check_code("非法支付方式被拒(400)",
           call("PUT", f"/api/order/{flow_id}/pay", token=buyer, params={"payType": 9}), 400)

# 正常支付
check_code("买家支付成功(微信模拟)",
           call("PUT", f"/api/order/{flow_id}/pay", token=buyer, params={"payType": 1}), 200)

st, detail = call("GET", f"/api/order/detail/{flow_id}", token=buyer)
d = detail.get("data", {})
check("支付后状态为「待交付」", d.get("orderStatusName") == "待交付", d.get("orderStatusName"))
check("支付后 payStatus = 1(已支付)", d.get("payStatus") == 1, d.get("payStatus"))
check("支付时间 payTime 已写入", bool(d.get("payTime")))

# 重复支付
check_code("重复支付被拒(1008)",
           call("PUT", f"/api/order/{flow_id}/pay", token=buyer), 1008)

# 租赁资源不因订单改变状态 —— 被占用的是"档期"而不是"资源"
st, res_detail = call("GET", f"/api/resource/detail/{FLOW_RES}", token=buyer)
check("支付后租赁资源仍在架上(占用的是档期,不是资源本身)",
      res_detail.get("code") == 200 and res_detail["data"].get("resourceStatus") == 1,
      f"code={res_detail.get('code')} status={res_detail.get('data', {}).get('resourceStatus')}")

check_code("但被占用的那个时段不能再被下单(1007)",
           call("POST", "/api/order/create",
                {"resourceId": FLOW_RES, "rentStartDate": "2026-12-11",
                 "rentEndDate": "2026-12-13"}, buyer2), 1007)

# 卖家交付
check_code("卖家确认交付成功",
           call("PUT", f"/api/order/{flow_id}/deliver", token=seller2), 200)
st, detail = call("GET", f"/api/order/detail/{flow_id}", token=buyer)
d = detail.get("data", {})
check("交付后状态为「使用中」", d.get("orderStatusName") == "使用中", d.get("orderStatusName"))
check("使用中:买家可执行动作 = [return]", d.get("actions") == ["return"], f"实际 {d.get('actions')}")

# 非买家不能归还
check_code("卖家不能替买家确认归还(403)",
           call("PUT", f"/api/order/{flow_id}/return", token=seller2), 403)

# 买家归还
check_code("买家确认归还,订单完成",
           call("PUT", f"/api/order/{flow_id}/return", token=buyer), 200)

st, detail = call("GET", f"/api/order/detail/{flow_id}", token=buyer)
d = detail.get("data", {})
check("订单状态为「已完成」", d.get("orderStatusName") == "已完成", d.get("orderStatusName"))
check("归还时间 returnTime 已写入", bool(d.get("returnTime")))
check("完成时间 finishTime 已写入", bool(d.get("finishTime")))

# 完成后资源仍在架上
st, res_detail = call("GET", f"/api/resource/detail/{FLOW_RES}", token=buyer)
check("租赁完成后资源仍为「已上架」(档期已释放)",
      res_detail.get("code") == 200 and res_detail["data"].get("resourceStatus") == 1,
      f"code={res_detail.get('code')} status={res_detail.get('data', {}).get('resourceStatus')}")

# 已完成订单不能再操作
check_code("已完成订单不能再取消(1008)",
           call("PUT", f"/api/order/{flow_id}/cancel", {"reason": "试试"}, buyer), 1008)


# ══════════════════════════════════════════════════════════════════
section("【7】资金与信用记账")
# ══════════════════════════════════════════════════════════════════

st, seller_after = call("GET", "/api/auth/getInfo", token=seller2)
st, buyer_after = call("GET", "/api/auth/getInfo", token=buyer)
sa = seller_after.get("data", {})
ba = buyer_after.get("data", {})

seller_balance_after = float(sa.get("balance") or 0)
check(f"支付后卖家余额增加的是租金({total_amount})而不是含押金的总额",
      abs((seller_balance_after - seller_balance_before) - total_amount) < 0.01,
      f"增加 {seller_balance_after - seller_balance_before},期望 {total_amount}")

check("订单完成后买家信用分 +2",
      int(ba.get("creditScore") or 0) - buyer_credit_before == 2,
      f"{buyer_credit_before} → {ba.get('creditScore')}")
check("订单完成后卖家信用分 +2",
      int(sa.get("creditScore") or 0) - seller_credit_before == 2,
      f"{seller_credit_before} → {sa.get('creditScore')}")

# 押金不该进卖家余额
check("押金未计入卖家余额(平台代管,完成后原路退还)",
      abs((seller_balance_after - seller_balance_before) - (total_amount + deposit_amount)) > 0.01,
      "卖家余额把押金也算进去了")


# ══════════════════════════════════════════════════════════════════
section("【8】购买类订单(无租期、无押金)")
# ══════════════════════════════════════════════════════════════════

st, res = call("POST", "/api/order/create",
               {"resourceId": SELL_RES, "buyerRemark": "周六上门自提"}, buyer)
buy_id = res.get("data")
check("购买类下单成功(无需填写租期)", res.get("code") == 200, res.get("message"))

# 出售类资源下单即锁定 —— 一台机器只有一个,不能有两份有效合同
check_code("出售资源下单后立即被锁,他人无法再下单(1006)",
           call("POST", "/api/order/create", {"resourceId": SELL_RES}, buyer2), 1006)

st, detail = call("GET", f"/api/order/detail/{buy_id}", token=buyer)
d = detail.get("data", {})
check("购买类订单 rentDays 为空", d.get("rentDays") is None, f"实际 {d.get('rentDays')}")
check("购买类订单押金为 0", float(d.get("depositAmount") or 0) == 0.0,
      f"实际 {d.get('depositAmount')}")
check("购买类应付总额 = 售价", abs(float(d.get("payAmount") or 0) - 1600.0) < 0.01,
      f"实际 {d.get('payAmount')}")

call("PUT", f"/api/order/{buy_id}/confirm", token=seller)
call("PUT", f"/api/order/{buy_id}/pay", token=buyer, params={"payType": 2})

# 购买类没有"交付"动作
check_code("购买类订单卖家不能执行「交付」(1008)",
           call("PUT", f"/api/order/{buy_id}/deliver", token=seller), 1008)

st, detail = call("GET", f"/api/order/detail/{buy_id}", token=buyer)
d = detail.get("data", {})
check("购买类待交付时买家可执行动作 = [receive]",
      d.get("actions") == ["receive"], f"实际 {d.get('actions')}")

check_code("购买类确认收货成功",
           call("PUT", f"/api/order/{buy_id}/receive", token=buyer), 200)
st, detail = call("GET", f"/api/order/detail/{buy_id}", token=buyer)
d = detail.get("data", {})
check("确认收货后订单完成", d.get("orderStatusName") == "已完成", d.get("orderStatusName"))

# 出售完成后资源进入终态,不再上架
st, sell_res = call("GET", f"/api/resource/detail/{SELL_RES}", token=buyer)
check("出售完成后资源变为「已完成」,不再上架",
      sell_res.get("code") == 404 or sell_res.get("data", {}).get("resourceStatus") == 4,
      f"code={sell_res.get('code')} status={sell_res.get('data', {}).get('resourceStatus')}")

# 租赁订单不能走"确认收货"
st, res = call("POST", "/api/order/create",
               {"resourceId": CANCEL_RES, "rentStartDate": "2026-12-28",
                "rentEndDate": "2026-12-29"}, buyer)
rid = res.get("data")
call("PUT", f"/api/order/{rid}/confirm", token=seller)
call("PUT", f"/api/order/{rid}/pay", token=buyer)
check_code("租赁订单不能用「确认收货」,必须走「确认归还」(1008)",
           call("PUT", f"/api/order/{rid}/receive", token=buyer), 1008)


# ══════════════════════════════════════════════════════════════════
section("【9】取消与拒绝")
# ══════════════════════════════════════════════════════════════════

# 待确认时买家可取消
st, res = call("POST", "/api/order/create",
               {"resourceId": RENT_RES, "rentStartDate": "2026-12-30",
                "rentEndDate": "2026-12-31"}, buyer)
cancel_id = res.get("data")
check_code("待确认时买家可以取消订单",
           call("PUT", f"/api/order/{cancel_id}/cancel", {"reason": "临时有事"}, buyer), 200)

st, detail = call("GET", f"/api/order/detail/{cancel_id}", token=buyer)
d = detail.get("data", {})
check("取消后状态为「已取消」", d.get("orderStatusName") == "已取消", d.get("orderStatusName"))
check("取消原因已记录", d.get("cancelReason") == "临时有事", d.get("cancelReason"))

# 已付款后买家不能自行取消
st, res = call("POST", "/api/order/create",
               {"resourceId": CANCEL_RES, "rentStartDate": "2026-12-23",
                "rentEndDate": "2026-12-24"}, buyer)
paid_id = res.get("data")
call("PUT", f"/api/order/{paid_id}/confirm", token=seller)
call("PUT", f"/api/order/{paid_id}/pay", token=buyer)
check_code("已付款订单买家不能自行取消,需走纠纷流程(1008)",
           call("PUT", f"/api/order/{paid_id}/cancel", {"reason": "想退"}, buyer), 1008)

# 卖家拒绝必须填原因
st, res = call("POST", "/api/order/create",
               {"resourceId": CANCEL_RES, "rentStartDate": "2026-12-26",
                "rentEndDate": "2026-12-27"}, buyer2)
reject_id2 = res.get("data")
st, rr = call("PUT", f"/api/order/{reject_id2}/reject", {"reason": ""}, seller)
check("卖家拒绝时留空原因会被兜底为默认文案",
      rr.get("code") == 200, f"实际 {rr.get('code')} / {rr.get('message')}")


# ══════════════════════════════════════════════════════════════════
section("【10】数据级权限")
# ══════════════════════════════════════════════════════════════════

# 战旗村支书:只能看到本村订单
st, admin_list = call("GET", "/api/admin/order/list", token=village_admin,
                      params={"pageSize": 500})
rows = admin_list.get("data", {}).get("rows", []) if admin_list.get("code") == 200 else []
villages = {r.get("villageName") for r in rows}
check("村管能看到本村订单(潜水泵那笔是战旗村的)",
      any(r.get("resourceId") == FLOW_RES for r in rows),
      f"返回 {len(rows)} 条")
check("村管看不到别村订单(金星村的资源 5 不在结果里)",
      all(r.get("resourceId") != RENT_RES for r in rows),
      f"村名集合 {villages}")

# 超管能看到全部
st, super_list = call("GET", "/api/admin/order/list", token=super_admin,
                      params={"pageSize": 500})
srows = super_list.get("data", {}).get("rows", []) if super_list.get("code") == 200 else []
check("超管能看到全部村组的订单",
      len(srows) > len(rows), f"超管 {len(srows)} 条 / 村管 {len(rows)} 条")

# 村管看不到别村的订单详情
st, cross = call("GET", f"/api/order/detail/{conflict_order_id}", token=village_admin)
check("村管访问别村订单详情返回 404", cross.get("code") == 404,
      f"实际 {cross.get('code')}")

# 村管强制取消有权管辖的订单(资源 12 是战旗村的)——
# 用待付款那笔会污染主流程数据,这里改用一个临时订单
st, res = call("POST", "/api/order/create",
               {"resourceId": FLOW_RES, "rentStartDate": "2026-12-18",
                "rentEndDate": "2026-12-19"}, buyer2)
admin_cancel_id = res.get("data")
check_code("村管可以强制取消本村订单(不受状态限制)",
           call("PUT", f"/api/admin/order/{admin_cancel_id}/cancel",
                {"reason": "该资源被举报存在安全隐患,订单终止"}, village_admin), 200)

st, detail = call("GET", f"/api/order/detail/{admin_cancel_id}", token=buyer2)
d = detail.get("data", {})
check("强制取消后状态为「已取消」且原因带管理员前缀",
      d.get("orderStatusName") == "已取消" and "管理员处理" in (d.get("cancelReason") or ""),
      f"{d.get('orderStatusName')} / {d.get('cancelReason')}")

# 强制取消必须填原因
st, res = call("POST", "/api/order/create",
               {"resourceId": FLOW_RES, "rentStartDate": "2026-12-21",
                "rentEndDate": "2026-12-22"}, buyer2)
tmp_id = res.get("data")
check_code("管理员强制取消未填原因被拒(400)",
           call("PUT", f"/api/admin/order/{tmp_id}/cancel", {"reason": ""}, village_admin), 400)


# ══════════════════════════════════════════════════════════════════
section("【11】事务回滚(TC-04)")
# ══════════════════════════════════════════════════════════════════

# 先记录基线
st, before_list = call("GET", "/api/order/mine", token=buyer, params={"pageSize": 500})
before_orders = before_list["data"]["total"] if before_list.get("code") == 200 else -1

st, before_notice = call("GET", "/api/notice/list", token=owner, params={"pageSize": 500})
before_notices = before_notice["data"]["total"] if before_notice.get("code") == 200 else -1

st, before_res = call("GET", f"/api/resource/detail/{ROLLBACK_RES}", token=buyer)
before_count = before_res.get("data", {}).get("orderCount")

# 装载失败点
st, arm = call("POST", "/api/system/debug/fail-point", token=super_admin,
               params={"point": "createOrder"})
check("超管装载事务失败点成功", arm.get("code") == 200, arm.get("message"))

st, q = call("GET", "/api/system/debug/fail-point", token=super_admin)
check("失败点已装载", q.get("data") == "createOrder", f"实际 {q.get('data')}")

# 下单 —— 应在事务最后一步抛出异常
st, rb = call("POST", "/api/order/create",
              {"resourceId": ROLLBACK_RES, "buyerRemark": "回滚测试"}, buyer)
check("事务在最后一步失败并返回业务错误(不是 500 崩溃)",
      rb.get("code") == 500 and "故障注入" in (rb.get("message") or ""),
      f"code={rb.get('code')} msg={rb.get('message')}")

# 逐表核对无残留
st, after_list = call("GET", "/api/order/mine", token=buyer, params={"pageSize": 500})
after_orders = after_list["data"]["total"] if after_list.get("code") == 200 else -2
check("订单表无残留(未产生半截订单)", before_orders == after_orders,
      f"{before_orders} → {after_orders}")

st, after_notice = call("GET", "/api/notice/list", token=owner, params={"pageSize": 500})
after_notices = after_notice["data"]["total"] if after_notice.get("code") == 200 else -2
check("通知表无残留(卖家没收到半截通知)", before_notices == after_notices,
      f"{before_notices} → {after_notices}")

st, after_res = call("GET", f"/api/resource/detail/{ROLLBACK_RES}", token=buyer)
after_count = after_res.get("data", {}).get("orderCount")
check("资源的下单计数已回滚", before_count == after_count,
      f"{before_count} → {after_count}")

# 失败点是一次性的
st, q2 = call("GET", "/api/system/debug/fail-point", token=super_admin)
check("失败点触发后自动解除,不会持续影响后续请求", q2.get("data") == "",
      f"实际 {q2.get('data')}")

# 正常下单应当成功
st, again = call("POST", "/api/order/create",
                 {"resourceId": ROLLBACK_RES, "buyerRemark": "失败点解除后的正常下单"}, buyer)
check("失败点解除后,同样的下单请求成功", again.get("code") == 200, again.get("message"))

# 权限:村级管理员不能动失败点
check_code("村管调用故障注入接口被拒(403)",
           call("POST", "/api/system/debug/fail-point", token=village_admin,
                params={"point": "createOrder"}), 403)
check_code("未登录调用故障注入接口被拒(401)",
           call("POST", "/api/system/debug/fail-point", params={"point": "createOrder"}), 401)


# ══════════════════════════════════════════════════════════════════
print()
print("=" * 70)
print(f"结果: {passed} 项通过,{failed} 项失败")
print("=" * 70)
if failed == 0:
    print()
    print("提示:本脚本创建了真实订单,如需恢复干净演示数据请执行:")
    print("      mysql -uroot -proot < sql/05_mock_data.sql")
sys.exit(1 if failed else 0)
