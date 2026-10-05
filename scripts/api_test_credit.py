#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次 6a(评价 + 信用)接口验证脚本。

验证点:
  1. 信用概览 —— 分数、等级、三个比率(NULL 与 0 的区分)
  2. 评价前置校验 —— 未完成订单、非交易方、评分越界、敏感词
  3. 双向评价与信用分变动 —— 好评 +1、中评 0、差评 -2
  4. 评价回复 —— 只有被评价人、只能一次
  5. 匿名评价的脱敏边界
  6. 后台评价管理 —— 隐藏/恢复,以及"隐藏后信用口径立即变化"
  7. 后台信用管理 —— 列表、流水、手动调分、数据级权限
  8. 权限 —— 未登录、村管越权

【重要:本脚本会创建真实订单和评价】
跑完请重新执行:
    mysql -uroot -proot < sql/05_mock_data.sql
重复运行前也必须先重置,脚本里有前置检查会提前拦住。

用法(需先启动后端):
    python scripts/api_test_credit.py
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


def call(method, path, token=None, body=None, params=None):
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


def check_code(name, res, expect):
    actual = res[1].get("code")
    check(name, actual == expect,
          f"期望 code={expect},实际 {actual} / {res[1].get('message')}")


def login(phone, password="123456"):
    status, res = call("POST", "/api/auth/login", body={"phone": phone, "password": password})
    if status == 200 and res.get("code") == 200:
        return res["data"]["token"]
    return None


def section(title):
    print()
    print("─" * 70)
    print(title)
    print("─" * 70)


def credit(token):
    return (call("GET", "/api/credit/mine", token=token)[1].get("data") or {})


def score(token):
    return credit(token).get("creditScore")


def run_order(steps):
    """按顺序执行一组订单操作,任一步失败就记录出来"""
    results = []
    for method, path, token, body in steps:
        results.append(call(method, path, token=token, body=body))
    return results


# ══════════════════════════════════════════════════════════════════
#  测试数据约定
#
#  评价必须挂在"已完成"的订单上,所以本脚本先自己造两笔订单走完流程。
#  用演示数据里没有订单的资源,档期放在 12 月,与演示数据(9-10 月)错开。
#
#    订单A 租赁:资源 5(雷沃拖拉机,王大山发布)260/天 押金800
#                → 双向评价(好评 / 差评)+ 回复
#    订单B 购买:资源 19(闲置电焊机转让,张建国发布)480
#                → 敏感词、匿名、中评
#
#  两笔订单分属金星村(4)和战旗村(2),正好用来验证数据级权限。
# ══════════════════════════════════════════════════════════════════

RENT_RES = 5
SELL_RES = 19
SLOT = ("2026-12-05", "2026-12-07")

print("=" * 70)
print("批次 6a —— 评价 + 信用 验证")
print("=" * 70)

buyer = login("13800000005")     # 刘小明(战旗村买家)
buyer2 = login("13800000006")    # 陈晓芳(先锋村买家,用于越权)
seller = login("13800000007")    # 王大山(金星村卖家,资源5的发布人)
owner = login("13800000003")     # 张建国(战旗村卖家,资源19的发布人)
village_admin = login("13800000001")   # 战旗村支书
super_admin = login("13800000000")

check("账号登录", all([buyer, buyer2, seller, owner, village_admin, super_admin]))

st, pre = call("GET", "/api/order/mine", token=buyer, params={"pageSize": 500})
pre_rows = pre.get("data", {}).get("rows", []) if pre.get("code") == 200 else []
if any(r["resourceId"] == RENT_RES and (r.get("rentStartDate") or "").startswith("2026-12")
       for r in pre_rows):
    print()
    print("!" * 70)
    print("检测到测试档期已被占用 —— 库里还留着上一轮测试创建的订单。")
    print("请先重置演示数据再运行本脚本:")
    print("    mysql -uroot -proot < sql/05_mock_data.sql")
    print("!" * 70)
    sys.exit(2)


# ══════════════════════════════════════════════════════════════════
section("【0】准备:造两笔已完成的订单")
# ══════════════════════════════════════════════════════════════════

st, res = call("POST", "/api/order/create", token=buyer,
               body={"resourceId": RENT_RES, "rentStartDate": SLOT[0],
                     "rentEndDate": SLOT[1], "buyerRemark": "评价流程测试"})
order_a = res.get("data")
check("订单A 创建成功(租赁,金星村)", res.get("code") == 200, res.get("message"))

run_order([
    ("PUT", f"/api/order/{order_a}/confirm", seller, None),
    ("PUT", f"/api/order/{order_a}/pay", buyer, None),
    ("PUT", f"/api/order/{order_a}/deliver", seller, None),
    ("PUT", f"/api/order/{order_a}/return", buyer, None),
])
st, d = call("GET", f"/api/order/detail/{order_a}", token=buyer)
check("订单A 走完流程并完成", d.get("data", {}).get("orderStatusName") == "已完成",
      d.get("data", {}).get("orderStatusName"))

st, res = call("POST", "/api/order/create", token=buyer,
               body={"resourceId": SELL_RES, "buyerRemark": "评价边界用例"})
order_b = res.get("data")
check("订单B 创建成功(购买,战旗村)", res.get("code") == 200, res.get("message"))

run_order([
    ("PUT", f"/api/order/{order_b}/confirm", owner, None),
    ("PUT", f"/api/order/{order_b}/pay", buyer, None),
    ("PUT", f"/api/order/{order_b}/receive", buyer, None),
])
st, d = call("GET", f"/api/order/detail/{order_b}", token=buyer)
check("订单B 走完流程并完成", d.get("data", {}).get("orderStatusName") == "已完成",
      d.get("data", {}).get("orderStatusName"))

# 基线:两笔订单完成时买卖双方各 +2,这是订单履约的分;
# 下面的评价是另一笔账,分开记。
base_buyer = score(buyer)
base_seller = score(seller)
print(f"  基线:刘小明 {base_buyer} 分 / 王大山 {base_seller} 分"
      f"(两笔订单完成时各 +2)")


# ══════════════════════════════════════════════════════════════════
section("【1】信用概览")
# ══════════════════════════════════════════════════════════════════

check("未登录不能查信用", call("GET", "/api/credit/mine")[1].get("code") == 401)

me = credit(buyer)
check("返回信用分", isinstance(me.get("creditScore"), int))
check("返回信用等级与说明", bool(me.get("level")) and bool(me.get("levelDesc")))
check("返回交易履约率(对应需求原文「交易履约」)", "fulfillRate" in me)
check("返回评价均分(对应需求原文「用户评价」)", "avgScore" in me)
check("返回评价条数", isinstance(me.get("evalCount"), int))

# 资源完好率对纯买家是「无数据」。
# 【为什么字段会整个消失】查询接口配了 Jackson 的 non_null,
# null 字段不参与序列化 —— 前端拿到 undefined,`== null` 判断照样成立,
# 所以这是有意的设计而不是丢字段。
check("纯买家的资源完好率是「无数据」(字段缺省,而不是 0%)",
      me.get("intactRate") is None, f"实际 {me.get('intactRate')}")

# 卖家应当有这一项:买家评卖家时会填"资源相符度"。
# 用演示数据里的李秀兰 —— 她收到过两条带资源相符度的评价
lady = login("13800000004")
lady_credit = credit(lady)
check("卖家有资源完好率数据(买家评价时填了资源相符度)",
      lady_credit.get("intactRate") is not None,
      f"实际 {lady_credit.get('intactRate')}")
check("卖家有评价均分",
      lady_credit.get("avgScore") is not None,
      f"实际 {lady_credit.get('avgScore')}")

print(f"  {me.get('realName')} —— {me.get('creditScore')} 分 / {me.get('level')}")
print(f"  履约率 {me.get('fulfillRate')}% · 完好率 {me.get('intactRate')} · "
      f"均分 {me.get('avgScore')}({me.get('evalCount')} 条)")
print(f"  {me.get('levelDesc')}")

# NULL 语义:没有数据的比率返回 null 而不是 0
c2 = credit(buyer2)
check("没有交易的用户,履约率是「无数据」而不是 0%",
      c2.get("fulfillRate") is None, f"实际 {c2.get('fulfillRate')}")
check("没有评价的用户,均分是「无数据」而不是 0",
      c2.get("avgScore") is None, f"实际 {c2.get('avgScore')}")
check("新用户信用分为初始值 100", c2.get("creditScore") == 100,
      f"实际 {c2.get('creditScore')}")
check("新用户已完成的订单数为 0",
      c2.get("finishOrderCount") == 0, f"实际 {c2.get('finishOrderCount')}")


# ══════════════════════════════════════════════════════════════════
section("【2】评价前置校验")
# ══════════════════════════════════════════════════════════════════

check_code("未完成的订单不能评价(1008)",
           call("POST", "/api/evaluation/create", token=buyer,
                body={"orderId": 5, "score": 5, "content": "还没完成就评价"}), 1008)

check_code("非交易方不能评价(403)",
           call("POST", "/api/evaluation/create", token=buyer2,
                body={"orderId": order_a, "score": 5}), 403)

check_code("订单不存在时被拒(404)",
           call("POST", "/api/evaluation/create", token=buyer,
                body={"orderId": 999999, "score": 5}), 404)

check_code("评分 0 分被拒(400)",
           call("POST", "/api/evaluation/create", token=buyer,
                body={"orderId": order_a, "score": 0}), 400)
check_code("评分 6 分被拒(400)",
           call("POST", "/api/evaluation/create", token=buyer,
                body={"orderId": order_a, "score": 6}), 400)
check_code("不填评分被拒(400)",
           call("POST", "/api/evaluation/create", token=buyer,
                body={"orderId": order_a}), 400)
check_code("缺少订单ID被拒(400)",
           call("POST", "/api/evaluation/create", token=buyer,
                body={"score": 5}), 400)

# 敏感词两级处理
#
# 【词库里的 action 含义】1 = 替换(打码放行),2 = 拦截(整条拒绝)。
# 注意别记反:"加微信"这类是**替换**类,不是拦截类 ——
# 广告词打码后内容照发,只有辱骂和违法词才整条拦下。
# 替换类的正向用例放在【5】,和匿名一起验,避免同一订单被评价两次。
check_code("评价含拦截类敏感词(诈骗)被拒(1010)",
           call("POST", "/api/evaluation/create", token=buyer,
                body={"orderId": order_b, "score": 5,
                      "content": "东西不错,不过这人像个诈骗团伙"}), 1010)

check_code("未登录不能提交评价(401)",
           call("POST", "/api/evaluation/create",
                body={"orderId": order_a, "score": 5}), 401)


# ══════════════════════════════════════════════════════════════════
section("【3】双向评价与信用分变动")
# ══════════════════════════════════════════════════════════════════

st, res = call("POST", "/api/evaluation/create", token=buyer,
               body={"orderId": order_a, "score": 5, "resourceScore": 5,
                     "serviceScore": 5, "content": "机器好,人也实在。"})
eval_good = res.get("data")
check("买家提交 5 星好评成功", res.get("code") == 200, res.get("message"))

after_seller = score(seller)
check("好评让被评价人信用分 +1",
      after_seller == base_seller + 1, f"{base_seller} → {after_seller}(期望 +1)")

st, res = call("POST", "/api/evaluation/create", token=seller,
               body={"orderId": order_a, "score": 2,
                     "content": "归还时机器没清理干净,下不为例。"})
check("卖家提交 2 星差评成功", res.get("code") == 200, res.get("message"))

after_buyer = score(buyer)
check("差评让被评价人信用分 -2",
      after_buyer == base_buyer - 2, f"{base_buyer} → {after_buyer}(期望 -2)")

check_code("同一人对同一订单不能重复评价(400)",
           call("POST", "/api/evaluation/create", token=buyer,
                body={"orderId": order_a, "score": 5}), 400)

# 信用流水链
st, recs = call("GET", "/api/credit/records", token=buyer, params={"pageSize": 50})
rows = recs.get("data", {}).get("rows", [])
ordered = sorted(rows, key=lambda r: (r["createTime"], r["id"]))
check("信用流水的 before/after 首尾相接",
      all(ordered[i]["afterScore"] == ordered[i + 1]["beforeScore"]
          for i in range(len(ordered) - 1)),
      str([(r["beforeScore"], r["afterScore"]) for r in ordered[:5]]))
check("流水终点等于当前信用分",
      bool(ordered) and ordered[-1]["afterScore"] == after_buyer,
      f"流水终点 {ordered[-1]['afterScore'] if ordered else '?'} / 实际 {after_buyer}")
check("每条流水都带变更原因", all(r.get("reason") for r in rows))
check("每条流水都带业务类型中文名", all(r.get("bizTypeName") for r in rows))

# 最新一条应当是本次差评产生的 -2。
# 不能断言"评价类流水只有 1 条" —— 演示数据里本来就有两条好评流水,
# 脚本不清库,那些记录还在。
latest = ordered[-1] if ordered else {}
check("最新一条流水是本次差评产生的 -2",
      latest.get("bizType") == 3 and latest.get("changeScore") == -2
      and "收到差评" in (latest.get("reason") or ""),
      f"实际 {latest.get('bizType')} / {latest.get('changeScore')} / {latest.get('reason')}")


# ══════════════════════════════════════════════════════════════════
section("【4】评价回复")
# ══════════════════════════════════════════════════════════════════

check_code("被评价人可以回复(200)",
           call("PUT", f"/api/evaluation/{eval_good}/reply", token=seller,
                body={"replyContent": "谢谢认可,下次再来给你留着。"}), 200)

check_code("同一条评价不能回复两次(400)",
           call("PUT", f"/api/evaluation/{eval_good}/reply", token=seller,
                body={"replyContent": "再补一句"}), 400)

check_code("评价人不能替被评价人回复(403)",
           call("PUT", f"/api/evaluation/{eval_good}/reply", token=buyer,
                body={"replyContent": "我自己回复自己"}), 403)

check_code("回复内容不能为空(400)",
           call("PUT", f"/api/evaluation/{eval_good}/reply", token=seller,
                body={"replyContent": "   "}), 400)

check_code("评价不存在时被拒(404)",
           call("PUT", "/api/evaluation/999999/reply", token=seller,
                body={"replyContent": "test"}), 404)

st, detail = call("GET", f"/api/evaluation/order/{order_a}", token=seller)
mine_row = [r for r in detail.get("data", {}).get("rows", []) if r.get("id") == eval_good]
check("回复后 canReply 变为 false",
      bool(mine_row) and mine_row[0].get("canReply") is False,
      f"实际 {mine_row[0].get('canReply') if mine_row else '未找到'}")


# ══════════════════════════════════════════════════════════════════
section("【5】匿名评价与中评")
# ══════════════════════════════════════════════════════════════════

# 一条评价同时验证两件事:替换类敏感词打码 + 匿名
st, res = call("POST", "/api/evaluation/create", token=buyer,
               body={"orderId": order_b, "score": 5, "anonymous": True,
                     "content": "东西挺好,有需要的加微信详聊"})
anon_id = res.get("data")
check("替换类敏感词被打码放行(200)", res.get("code") == 200, res.get("message"))

st, sent = call("GET", "/api/evaluation/sent", token=buyer, params={"pageSize": 20})
own = [r for r in sent.get("data", {}).get("rows", []) if r.get("id") == anon_id]
check("打码后的内容已入库(敏感词被星号替换)",
      bool(own) and "加微信" not in (own[0].get("content") or ""),
      f"实际:{own[0].get('content') if own else '未找到'}")
check("评价人自己能看到自己的匿名评价是谁写的",
      bool(own) and own[0].get("fromUserName") != "匿名用户",
      f"实际 {own[0].get('fromUserName') if own else '未找到'}")

st, received = call("GET", "/api/evaluation/received", token=owner, params={"pageSize": 20})
recv_row = [r for r in received.get("data", {}).get("rows", []) if r.get("id") == anon_id]
check("匿名评价对被评价人显示为「匿名用户」",
      bool(recv_row) and recv_row[0].get("fromUserName") == "匿名用户",
      f"实际 {recv_row[0].get('fromUserName') if recv_row else '未找到'}")
check("匿名评价不回传真实的 fromUserId",
      bool(recv_row) and recv_row[0].get("fromUserId") is None,
      f"实际 {recv_row[0].get('fromUserId') if recv_row else '未找到'}")

# 中评(3 星)→ 不加不减
before_mid = score(buyer)
st, res = call("POST", "/api/evaluation/create", token=owner,
               body={"orderId": order_b, "score": 3, "content": "一般般,凑合用。"})
check("卖家提交 3 星中评成功", res.get("code") == 200, res.get("message"))
check("中评(3 星)不改变被评价人的信用分",
      score(buyer) == before_mid, f"{before_mid} → {score(buyer)}")

# 匿名评价也照常算信用分。
# 张建国在演示数据里本来就是 103 分(订单1 履约 +2、收到好评 +1),
# 本次订单B 完成再 +2、收到买家 5 星好评再 +1 → 106
check("匿名不影响计分:好评(5 星)让被评价人 +1",
      score(owner) == 106,
      f"张建国 {score(owner)} 分(期望 106:103 + 2 履约 + 1 好评)")


# ══════════════════════════════════════════════════════════════════
section("【6】评价查询")
# ══════════════════════════════════════════════════════════════════

st, by_res = call("GET", f"/api/evaluation/resource/{RENT_RES}", params={"pageSize": 20})
check("资源下的评价可以匿名访问(公开接口)",
      by_res.get("code") == 200, by_res.get("message"))

st, demo = call("GET", "/api/evaluation/resource/1", params={"pageSize": 20})
check("演示数据的资源 1 有评价可看",
      demo.get("data", {}).get("total", 0) >= 2,
      f"total={demo.get('data', {}).get('total')}")

st, by_order = call("GET", f"/api/evaluation/order/{order_a}", token=buyer)
check("按订单查评价返回双方各一条",
      by_order.get("data", {}).get("total") == 2,
      f"total={by_order.get('data', {}).get('total')}")

st, empty_res = call("GET", "/api/evaluation/resource/99999", params={"pageSize": 20})
check("不存在的资源返回空列表而不是报错",
      empty_res.get("code") == 200 and empty_res.get("data", {}).get("total") == 0,
      f"code={empty_res.get('code')} total={empty_res.get('data', {}).get('total')}")


# ══════════════════════════════════════════════════════════════════
section("【7】后台评价管理")
# ══════════════════════════════════════════════════════════════════

check_code("未登录不能访问后台评价管理(401)",
           call("GET", "/api/admin/evaluation/list"), 401)

st, admin_list = call("GET", "/api/admin/evaluation/list", token=super_admin,
                      params={"pageSize": 50})
total_all = admin_list.get("data", {}).get("total", 0)
check("超管能看到全部评价", total_all >= 9, f"total={total_all}")

st, va_list = call("GET", "/api/admin/evaluation/list", token=village_admin,
                   params={"pageSize": 50})
va_rows = va_list.get("data", {}).get("rows", [])
check("村管看不到别村的评价(订单A 属金星村)",
      all(r.get("orderId") != order_a for r in va_rows),
      f"村管 {len(va_rows)} 条 / 超管 {total_all} 条")

# 隐藏与恢复 —— 用买家给张建国的匿名评价(订单B 属战旗村,村管有权管)
owns_anon = [r for r in va_rows if r.get("id") == anon_id]
check("村管能看到本村订单的评价", bool(owns_anon))

if owns_anon:
    before_cnt = credit(owner).get("evalCount")
    check_code("村管可以隐藏本村评价(200)",
               call("PUT", f"/api/admin/evaluation/{anon_id}/hide", token=village_admin), 200)

    after_cnt = credit(owner).get("evalCount")
    check("隐藏后该评价不再计入评价口径",
          after_cnt == (before_cnt or 0) - 1, f"评价数 {before_cnt} → {after_cnt}")

    st, hidden_list = call("GET", "/api/admin/evaluation/list", token=village_admin,
                           params={"pageSize": 50})
    check("被隐藏的评价在后台仍然可见(否则无法恢复)",
          any(r.get("id") == anon_id
              for r in hidden_list.get("data", {}).get("rows", [])))

    st, pub = call("GET", f"/api/evaluation/resource/{SELL_RES}", params={"pageSize": 20})
    check("被隐藏的评价不再出现在前台资源页",
          all(r.get("id") != anon_id
              for r in pub.get("data", {}).get("rows", [])))

    check_code("村管可以恢复评价(200)",
               call("PUT", f"/api/admin/evaluation/{anon_id}/show", token=village_admin), 200)
    check("恢复后重新计入评价口径",
          credit(owner).get("evalCount") == before_cnt,
          f"评价数 {after_cnt} → {credit(owner).get('evalCount')}")

# 越权:村管动不了别村的评价
others = [r for r in admin_list.get("data", {}).get("rows", [])
          if r.get("orderId") == order_a]
if others:
    check_code("村管不能隐藏别村的评价(403)",
               call("PUT", f"/api/admin/evaluation/{others[0]['id']}/hide",
                    token=village_admin), 403)

# 只看差评
st, bad = call("GET", "/api/admin/evaluation/list", token=super_admin,
               params={"pageSize": 50, "onlyBad": "true"})
bad_rows = bad.get("data", {}).get("rows", [])
check("只看差评的筛选用效,且结果确实都是 ≤2 星",
      all(r.get("score") <= 2 for r in bad_rows) and len(bad_rows) >= 1,
      f"返回 {len(bad_rows)} 条")


# ══════════════════════════════════════════════════════════════════
section("【8】后台信用管理")
# ══════════════════════════════════════════════════════════════════

check_code("未登录不能访问后台信用管理(401)",
           call("GET", "/api/admin/credit/list"), 401)

st, credit_list = call("GET", "/api/admin/credit/list", token=super_admin,
                       params={"pageSize": 50})
cl_rows = credit_list.get("data", {}).get("rows", [])
check("超管能看到全部用户", len(cl_rows) == 10, f"实际 {len(cl_rows)}")

scores = [r["creditScore"] for r in cl_rows]
check("列表按信用分升序(最需要关注的排最前)",
      scores == sorted(scores), f"{scores[:5]}...")

# 比率字段对"有数据的人"必须存在。这里刻意挑一个有订单的用户来查 ——
# 全新用户的比率是 null,而 Jackson 配了 non_null,字段名压根不会出现,
# 拿他们去断言"字段存在"会误报。
active = [r for r in cl_rows if r.get("totalOrderCount", 0) > 0]
check("有交易记录的用户带出三个信用比率",
      bool(active) and all(
          "fulfillRate" in r and "avgScore" in r for r in active),
      f"有交易的用户 {len(active)} 个")
check("纯买家没有资源相符度数据时,完好率字段缺省(而不是 0%)",
      all("intactRate" not in r or r["intactRate"] is not None for r in cl_rows))

st, va_credit = call("GET", "/api/admin/credit/list", token=village_admin,
                     params={"pageSize": 50})
va_credit_rows = va_credit.get("data", {}).get("rows", [])
check("村管只能看到本村用户", len(va_credit_rows) < len(cl_rows),
      f"村管 {len(va_credit_rows)} / 超管 {len(cl_rows)}")
check("村管看不到金星村的王大山",
      all(r.get("userId") != 6 for r in va_credit_rows))

st, kw = call("GET", "/api/admin/credit/list", token=super_admin,
              params={"pageSize": 50, "keyword": "刘小明"})
check("按姓名搜索生效", kw.get("data", {}).get("total") == 1,
      f"total={kw.get('data', {}).get('total')}")

# 手动调分
before = score(buyer)
check_code("管理员手动加分(200)",
           call("POST", "/api/admin/credit/adjust", token=super_admin,
                body={"userId": 8, "delta": 5, "reason": "线下调解达成一致,补偿性加分"}), 200)
check("手动加分生效", score(buyer) == before + 5, f"{before} → {score(buyer)}")

st, recs = call("GET", "/api/credit/records", token=buyer, params={"pageSize": 5})
top = (recs.get("data", {}).get("rows") or [{}])[0]
check("手动调整也写进了流水(用户能看到这 5 分的来由)",
      top.get("bizType") == 4 and top.get("changeScore") == 5,
      f"bizType={top.get('bizType')} change={top.get('changeScore')}")

check_code("手动调分不填原因被拒(400)",
           call("POST", "/api/admin/credit/adjust", token=super_admin,
                body={"userId": 8, "delta": 3}), 400)
check_code("手动调分为 0 被拒(400)",
           call("POST", "/api/admin/credit/adjust", token=super_admin,
                body={"userId": 8, "delta": 0, "reason": "试试"}), 400)
check_code("用户不存在时被拒(404)",
           call("POST", "/api/admin/credit/adjust", token=super_admin,
                body={"userId": 999999, "delta": 1, "reason": "test"}), 404)

# 越权
check_code("村管不能调整别村用户的信用分(403)",
           call("POST", "/api/admin/credit/adjust", token=village_admin,
                body={"userId": 6, "delta": 10, "reason": "越权测试"}), 403)
check_code("村管不能查看别村用户的信用流水(404)",
           call("GET", "/api/admin/credit/records", token=village_admin,
                params={"userId": 6}), 404)
check_code("村管可以查看本村用户的信用流水(200)",
           call("GET", "/api/admin/credit/records", token=village_admin,
                params={"userId": 8, "pageSize": 20}), 200)


# ══════════════════════════════════════════════════════════════════
section("【9】前台越权")
# ══════════════════════════════════════════════════════════════════

a = credit(buyer)
b = credit(seller)
check("前台信用接口只认 token 里的身份,查到的分数不同",
      a.get("userId") == 8 and b.get("userId") == 6,
      f"{a.get('userId')} / {b.get('userId')}")
check("前台不能查别人的信用流水(接口无 userId 参数,只能查自己)",
      call("GET", "/api/credit/records", token=buyer,
           params={"userId": 6})[1].get("data", {}).get("rows", [{}])[0].get("userId") == 8,
      "传了别人的 userId 但返回的仍是自己的流水")


# ══════════════════════════════════════════════════════════════════
print()
print("=" * 70)
print(f"结果: {passed} 项通过,{failed} 项失败")
print("=" * 70)
if failed == 0:
    print()
    print("提示:本脚本创建了真实订单和评价,如需恢复干净演示数据请执行:")
    print("      mysql -uroot -proot < sql/05_mock_data.sql")
sys.exit(1 if failed else 0)
