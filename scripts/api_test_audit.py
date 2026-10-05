#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次 4(审核流 + 通知 + 敏感词)接口验证脚本。

验证点:
  1. 实名认证提交 —— 重复提交、身份证查重、格式校验
  2. 审核列表 —— 数据级权限、身份证脱敏
  3. 审核操作 —— 状态流转、事务、驳回必填原因、重复审核拦截
  4. 资源审核 —— 通过上架 / 驳回带原因
  5. 通知 —— 审核结果必达、越权标记拦截
  6. 敏感词 —— DFA 过滤效果、拦截与替换两种动作、词库热更新

用法(需先启动后端):
    python scripts/api_test_audit.py
"""
import json
import random
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


def q(**kw):
    return "?" + urllib.parse.urlencode({k: v for k, v in kw.items() if v is not None})


print("=" * 70)
print("批次 4 —— 审核流 + 通知 + 敏感词 验证")
print("=" * 70)

# ══════════════════════════════════════════════════════════════
print("\n[1] 实名认证提交")

# 注册一个全新用户,走完整的"注册 → 实名提交 → 审核通过"闭环
new_phone = "137" + str(random.randint(10000000, 99999999))
status, res = call("POST", "/api/auth/register", {
    "phone": new_phone, "password": "123456",
    "realName": "实名测试", "villageId": 2, "userType": 1
})
check("新用户注册成功", res.get("code") == 200, str(res)[:150])

new_token = login(new_phone)
check("新用户可登录", new_token is not None)

# 未实名时不能发布资源(批次3的规则仍然生效)
status, res = call("POST", "/api/resource/publish", {
    "resourceName": "未实名测试", "categoryId": 11, "tradeType": 1,
    "price": 100, "location": "测试", "rentStartDate": "2026-10-01",
    "rentEndDate": "2026-12-31", "rentableWeekdays": [1, 2], "useRule": "测试"
}, token=new_token)
check("未实名用户不能发布资源", res.get("code") == 1004, str(res)[:150])

# 身份证号格式校验
status, res = call("POST", "/api/user/realname", {
    "realName": "实名测试", "idCard": "12345",
    "idCardFront": "/demo/a.svg", "idCardBack": "/demo/b.svg"
}, token=new_token)
check("非法身份证号被拦下", res.get("code") == 400, str(res)[:120])

# 正常提交。
#
# 身份证号用随机生成而不是写死:写死的话第二次运行就会撞上
# "该身份证号已被其他账号使用" —— 上一轮测试的账号还在库里。
# 那样测的是"上次跑没跑过",不是功能本身。
# 格式要满足后端的正则:[6位地区][4位年][2位月][2位日][3位顺序][1位校验]
test_id_card = (f"5101241990{random.randint(1, 12):02d}{random.randint(1, 28):02d}"
                f"{random.randint(100, 999)}{random.randint(0, 9)}")

status, res = call("POST", "/api/user/realname", {
    "realName": "实名测试", "idCard": test_id_card,
    "idCardFront": "/demo/a.svg", "idCardBack": "/demo/b.svg"
}, token=new_token)
check("提交实名认证成功", res.get("code") == 200,
      f"{res} (身份证号 {test_id_card})")

status, res = call("GET", "/api/user/profile", token=new_token)
check("提交后状态变为待审核(1)",
      (res.get("data") or {}).get("realnameStatus") == 1,
      f"实际={(res.get('data') or {}).get('realnameStatus')}")

# 重复提交
status, res = call("POST", "/api/user/realname", {
    "realName": "实名测试", "idCard": test_id_card,
    "idCardFront": "/demo/a.svg", "idCardBack": "/demo/b.svg"
}, token=new_token)
check("审核中不能重复提交", res.get("code") == 400, str(res)[:150])

# 身份证号被其他账号占用
other_phone = "137" + str(random.randint(10000000, 99999999))
call("POST", "/api/auth/register", {
    "phone": other_phone, "password": "123456", "realName": "占用测试",
    "villageId": 2, "userType": 1
})
other_token = login(other_phone)
status, res = call("POST", "/api/user/realname", {
    "realName": "占用测试", "idCard": test_id_card,
    "idCardFront": "/demo/a.svg", "idCardBack": "/demo/b.svg"
}, token=other_token)
check("同一身份证号不能被两个账号使用", res.get("code") == 400, str(res)[:150])

# ══════════════════════════════════════════════════════════════
print("\n[2] 审核列表与权限")
village_admin = login("13800000001")   # 战旗村支书
super_admin = login("13800000000")
villager = login("13800000003")

status, res = call("GET", "/api/admin/user/audit/list" + q(pageSize=100), token=village_admin)
rows = (res.get("data") or {}).get("rows") or []
check("村管能查看实名审核列表", res.get("code") == 200, str(res)[:120])
check("列表只含实名认证类型", rows and all(r["bizType"] == 1 for r in rows),
      f"共 {len(rows)} 条")
check("列表只含本村申请", rows and all(r["applicantVillageName"] == "战旗村" for r in rows),
      f"涉及村组:{ {r['applicantVillageName'] for r in rows} }")

# 身份证号必须脱敏
if rows:
    id_card_in_list = rows[0].get("idCard") or ""
    check("列表里的身份证号已脱敏",
          "********" in id_card_in_list,
          f"实际:{id_card_in_list}")
    test_audit_id = rows[0]["id"]

status, res = call("GET", "/api/admin/user/audit/list", token=villager)
check("村民无权查看审核列表", res.get("code") == 403, str(res)[:120])

# ══════════════════════════════════════════════════════════════
print("\n[3] 审核操作")

# 找到刚提交的那条待审记录
status, res = call("GET", "/api/admin/user/audit/list" + q(pageSize=100), token=village_admin)
target = next((r for r in ((res.get("data") or {}).get("rows") or [])
               if r.get("applicantPhone") == new_phone), None)
check("能找到刚提交的待审记录", target is not None)

if target:
    audit_id = target["id"]

    # 详情接口返回完整身份证号
    status, res = call("GET", f"/api/admin/audit/detail/{audit_id}", token=village_admin)
    detail_card = (res.get("data") or {}).get("idCard") or ""
    check("详情接口返回完整身份证号",
          detail_card == test_id_card, f"实际:{detail_card}")

    # 驳回不填原因
    status, res = call("POST", "/api/admin/user/audit", {
        "bizType": 1, "auditId": audit_id, "approved": False, "remark": ""
    }, token=village_admin)
    check("驳回不填原因被拒绝", res.get("code") == 400, str(res)[:150])

    # 驳回(填原因)
    status, res = call("POST", "/api/admin/user/audit", {
        "bizType": 1, "auditId": audit_id, "approved": False,
        "remark": "身份证照片模糊,请重新上传"
    }, token=village_admin)
    check("驳回成功", res.get("code") == 200, str(res)[:150])

    status, res = call("GET", "/api/user/profile", token=new_token)
    check("驳回后用户状态为已驳回(3)",
          (res.get("data") or {}).get("realnameStatus") == 3,
          f"实际={(res.get('data') or {}).get('realnameStatus')}")

    # 重复审核
    status, res = call("POST", "/api/admin/user/audit", {
        "bizType": 1, "auditId": audit_id, "approved": True
    }, token=village_admin)
    check("已处理的单子不能重复审核", res.get("code") == 400, str(res)[:150])

    # 重新提交 → 复用同一条待办
    status, res = call("POST", "/api/user/realname", {
        "realName": "实名测试", "idCard": test_id_card,
        "idCardFront": "/demo/c.svg", "idCardBack": "/demo/d.svg"
    }, token=new_token)
    check("被驳回后可以重新提交", res.get("code") == 200, str(res)[:150])

    status, res = call("GET", "/api/admin/user/audit/list" + q(pageSize=100), token=village_admin)
    same = [r for r in ((res.get("data") or {}).get("rows") or [])
            if r.get("applicantPhone") == new_phone]
    check("重新提交复用同一条待办(不堆积)",
          len(same) == 1 and same[0]["id"] == audit_id,
          f"找到 {len(same)} 条")

    # 通过
    status, res = call("POST", "/api/admin/user/audit", {
        "bizType": 1, "auditId": audit_id, "approved": True
    }, token=village_admin)
    check("审核通过", res.get("code") == 200, str(res)[:150])

    status, res = call("GET", "/api/user/profile", token=new_token)
    check("通过后用户状态为已认证(2)",
          (res.get("data") or {}).get("realnameStatus") == 2,
          f"实际={(res.get('data') or {}).get('realnameStatus')}")

    # 越权:用另一个村的管理员审战旗村的单子
    other_admin = login("13800000002")   # 先锋村主任
    status, res = call("POST", "/api/admin/user/audit", {
        "bizType": 1, "auditId": audit_id, "approved": True
    }, token=other_admin)
    check("别的村的管理员无权审本村单子",
          res.get("code") in (400, 403), str(res)[:150])

    # 越权:查看别村的审核详情
    status, res = call("GET", f"/api/admin/audit/detail/{audit_id}", token=other_admin)
    check("别的村的管理员无权查看本村申请详情",
          res.get("code") == 403, str(res)[:150])

# ══════════════════════════════════════════════════════════════
print("\n[4] 资源审核")

# 用刚通过实名的用户发一条资源
status, res = call("POST", "/api/resource/publish", {
    "resourceName": "审核流程测试用打谷机", "categoryId": 13, "tradeType": 1,
    "price": 88, "location": "战旗村测试位置",
    "rentStartDate": "2026-10-01", "rentEndDate": "2026-12-31",
    "rentableWeekdays": [1, 2, 3], "useRule": "测试用",
    "description": "用于验证资源审核流程"
}, token=new_token)
check("已实名用户能发布资源", res.get("code") == 200, str(res)[:150])
new_resource_id = (res.get("data") or {}).get("id")

status, res = call("GET", "/api/admin/resource/audit/list" + q(pageSize=100), token=village_admin)
res_audits = (res.get("data") or {}).get("rows") or []
check("资源审核列表只含资源类型", res_audits and all(r["bizType"] == 2 for r in res_audits),
      f"共 {len(res_audits)} 条")

target_res = next((r for r in res_audits if r.get("bizId") == new_resource_id), None)
check("能找到刚发布的资源待审记录", target_res is not None)

if target_res:
    raid = target_res["id"]

    # 驳回
    status, res = call("POST", "/api/admin/resource/audit", {
        "bizType": 2, "auditId": raid, "approved": False,
        "remark": "描述太简单,请补充规格参数"
    }, token=village_admin)
    check("资源驳回成功", res.get("code") == 200, str(res)[:150])

    status, res = call("GET", f"/api/resource/detail/{new_resource_id}", token=new_token)
    check("驳回后资源状态为审核驳回(5)",
          (res.get("data") or {}).get("resourceStatus") == 5,
          f"实际={(res.get('data') or {}).get('resourceStatus')}")
    check("驳回原因写回资源记录",
          "补充规格参数" in str((res.get("data") or {}).get("offlineReason")),
          str((res.get("data") or {}).get("offlineReason")))

    # 修改后重新提交 → 生成新的待办
    status, res = call("PUT", "/api/resource/update", {
        "id": new_resource_id, "resourceName": "审核流程测试用打谷机",
        "categoryId": 13, "tradeType": 1, "price": 88,
        "location": "战旗村测试位置",
        "rentStartDate": "2026-10-01", "rentEndDate": "2026-12-31",
        "rentableWeekdays": [1, 2, 3], "useRule": "测试用",
        "description": "补充了规格参数:功率 5.5kW,脱粒效率 800kg/h"
    }, token=new_token)
    check("被驳回的资源可以修改", res.get("code") == 200, str(res)[:150])

    status, res = call("GET", "/api/admin/resource/audit/list" + q(pageSize=100), token=village_admin)
    re_audits = [r for r in ((res.get("data") or {}).get("rows") or [])
                 if r.get("bizId") == new_resource_id]
    check("修改后重新生成待审记录", len(re_audits) >= 1, f"找到 {len(re_audits)} 条")

    if re_audits:
        # 通过
        status, res = call("POST", "/api/admin/resource/audit", {
            "bizType": 2, "auditId": re_audits[0]["id"], "approved": True
        }, token=village_admin)
        check("资源审核通过", res.get("code") == 200, str(res)[:150])

        status, res = call("GET", f"/api/resource/detail/{new_resource_id}", token=new_token)
        check("通过后资源状态为已上架(1)",
              (res.get("data") or {}).get("resourceStatus") == 1,
              f"实际={(res.get('data') or {}).get('resourceStatus')}")

# 类型不匹配防护。
#
# 注意测法:两个审核接口都会在控制器层强制覆盖 bizType
# (实名接口写死 1、资源接口写死 2),所以正常调用不可能不匹配。
# 真正要防的是"拿资源审核单的ID去调实名审核接口"——
# 此时控制器把 bizType 覆盖成 1,而记录本身是 2,服务层比对后拒绝。
if res_audits:
    resource_audit_id = res_audits[0]["id"]
    status, res = call("POST", "/api/admin/user/audit", {
        "bizType": 99, "auditId": resource_audit_id, "approved": True
    }, token=village_admin)
    check("拿资源审核单调实名审核接口会被拒绝",
          res.get("code") == 400, str(res)[:150])
    check("拒绝原因是类型不匹配",
          "类型" in str(res.get("message", "")), str(res.get("message")))

# ══════════════════════════════════════════════════════════════
print("\n[5] 通知")

status, res = call("GET", "/api/notice/list" + q(pageSize=50), token=new_token)
notices = (res.get("data") or {}).get("rows") or []
check("用户能收到通知", len(notices) > 0, f"共 {len(notices)} 条")

types = {n["noticeTypeName"] for n in notices}
check("包含实名审核通知", "实名认证" in types, str(types))
if new_resource_id:
    check("包含资源审核通知", "资源审核" in types, str(types))

if notices:
    check("通知带跳转路径", any(n.get("linkPath") for n in notices),
          str([n.get("linkPath") for n in notices[:3]]))

status, res = call("GET", "/api/notice/unread", token=new_token)
unread = res.get("data") or 0
check("未读数量正确", unread > 0, f"未读 {unread} 条")

if notices:
    nid = notices[0]["id"]
    status, res = call("PUT", f"/api/notice/{nid}/read", token=new_token)
    check("标记已读成功", res.get("code") == 200, str(res)[:120])

    status, res = call("GET", "/api/notice/unread", token=new_token)
    check("未读数减少", (res.get("data") or 0) == unread - 1,
          f"{unread} → {res.get('data')}")

    # 越权:标记别人的通知
    other_user_token = login("13800000004")
    status, res = call("PUT", f"/api/notice/{nid}/read", token=other_user_token)
    check("不能标记别人的通知为已读", res.get("code") == 403, str(res)[:150])

    status, res = call("PUT", "/api/notice/read-all", token=new_token)
    check("全部标记已读", res.get("code") == 200, str(res)[:120])
    status, res = call("GET", "/api/notice/unread", token=new_token)
    check("全部已读后未读数为 0", (res.get("data") or 0) == 0, f"实际={res.get('data')}")

# ══════════════════════════════════════════════════════════════
print("\n[6] 敏感词")

status, res = call("GET", "/api/system/sensitive/list" + q(pageSize=100), token=super_admin)
words = (res.get("data") or {}).get("rows") or []
check("超管能查看敏感词库", res.get("code") == 200, str(res)[:120])
check("词库有初始数据", len(words) > 0, f"共 {len(words)} 个")

status, res = call("GET", "/api/system/sensitive/list", token=village_admin)
check("村管无权管理敏感词", res.get("code") == 403, str(res)[:120])

# DFA 过滤效果
status, res = call("GET", "/api/system/sensitive/test" + q(text="正常内容"), token=super_admin)
data = res.get("data") or {}
check("正常内容不被拦截", data.get("blocked") is False, str(data))
check("正常内容无命中", len(data.get("hits") or []) == 0, str(data.get("hits")))

status, res = call("GET", "/api/system/sensitive/test" + q(text="有需要的加微信联系"), token=super_admin)
data = res.get("data") or {}
# 注意用的是「加微信」而不是「加我微信」—— 词库里的词是前者,
# 中间多一个字符就匹配不上了(DFA 是连续字符匹配,不是模糊匹配)
check("替换类敏感词被打码",
      data.get("filtered") == "有需要的***联系",
      f"原文『有需要的加微信联系』→ 过滤后『{data.get('filtered')}』")
check("替换类不拦截整体提交", data.get("blocked") is False, str(data))

status, res = call("GET", "/api/system/sensitive/test" + q(text="这是赌博网站"), token=super_admin)
data = res.get("data") or {}
check("拦截类敏感词触发拦截", data.get("blocked") is True, str(data))
check("命中词被记录下来", "赌博" in (data.get("hits") or []), str(data.get("hits")))

# 敏感词在真实业务路径上生效:发布资源时描述含拦截词
status, res = call("POST", "/api/resource/publish", {
    "resourceName": "含违禁词的资源", "categoryId": 11, "tradeType": 1,
    "price": 100, "location": "测试",
    "rentStartDate": "2026-10-01", "rentEndDate": "2026-12-31",
    "rentableWeekdays": [1], "useRule": "测试",
    "description": "这个资源涉及赌博"
}, token=new_token)
check("发布资源时拦截类敏感词被拦下",
      res.get("code") == 1010, str(res)[:150])
check("错误提示指出命中了什么词",
      "赌博" in str(res.get("message", "")), str(res.get("message")))

status, res = call("POST", "/api/resource/publish", {
    "resourceName": "含广告词的资源", "categoryId": 11, "tradeType": 1,
    "price": 100, "location": "测试",
    "rentStartDate": "2026-10-01", "rentEndDate": "2026-12-31",
    "rentableWeekdays": [1], "useRule": "测试",
    "description": "有意者扫码联系,加微信谈"
}, token=new_token)
check("替换类敏感词不拦截发布", res.get("code") == 200, str(res)[:150])
if res.get("code") == 200:
    ad_id = (res.get("data") or {}).get("id")
    status, res = call("GET", f"/api/resource/detail/{ad_id}", token=new_token)
    desc = (res.get("data") or {}).get("description") or ""
    check("落库的描述里敏感词已被打码",
          "加微信" not in desc and "扫码" not in desc,
          f"实际描述:{desc}")

# 新增敏感词后立刻生效(热更新)
status, res = call("POST", "/api/system/sensitive", {
    "word": "测试专用违禁词", "category": "违法", "action": 2, "status": 1
}, token=super_admin)
check("新增敏感词成功", res.get("code") == 200, str(res)[:150])

added_id = None
status, res = call("GET", "/api/system/sensitive/list" + q(keyword="测试专用违禁词"), token=super_admin)
rows = (res.get("data") or {}).get("rows") or []
if rows:
    added_id = rows[0]["id"]

status, res = call("GET", "/api/system/sensitive/test" + q(text="这里有测试专用违禁词"), token=super_admin)
check("新增的词立刻生效(无需重启)",
      (res.get("data") or {}).get("blocked") is True, str(res.get("data")))

# 重复新增
status, res = call("POST", "/api/system/sensitive", {
    "word": "测试专用违禁词", "category": "违法", "action": 2
}, token=super_admin)
check("重复添加敏感词被拒绝", res.get("code") == 400, str(res)[:150])

# 批量导入。
#
# 用带随机后缀的词而不是固定词:固定词第二次跑就会全部命中"已存在",
# 导入数变成 0 —— 那测的就不是导入功能,而是"上次跑过没跑过"。
# 跑完顺手清理,保证不留垃圾数据。
batch_tag = str(random.randint(100000, 999999))
batch_words = [f"批量词甲{batch_tag}", f"批量词乙{batch_tag}", f"批量词丙{batch_tag}"]

status, res = call("POST", "/api/system/sensitive/batch", {
    "text": "\n".join(batch_words[:1]) + "," + ",".join(batch_words[1:]),
    "category": "广告", "action": 1
}, token=super_admin)
check("批量导入成功", res.get("code") == 200, str(res)[:150])
if res.get("code") == 200:
    check("导入了 3 个词", (res.get("data") or {}).get("inserted") == 3,
          str(res.get("data")))

    # 清理
    for w in batch_words:
        _, listed = call("GET", "/api/system/sensitive/list" + q(keyword=w),
                         token=super_admin)
        for row in ((listed.get("data") or {}).get("rows") or []):
            if row["word"] == w:
                call("DELETE", f"/api/system/sensitive/{row['id']}", token=super_admin)

# 清理测试数据
if added_id:
    status, res = call("DELETE", f"/api/system/sensitive/{added_id}", token=super_admin)
    check("删除敏感词成功", res.get("code") == 200, str(res)[:120])

status, res = call("GET", "/api/system/sensitive/test" + q(text="这里有测试专用违禁词"), token=super_admin)
check("删除后立刻失效",
      (res.get("data") or {}).get("blocked") is False, str(res.get("data")))

# ── 汇总 ───────────────────────────────────────────────────────
print("\n" + "=" * 70)
print(f"结果:通过 {passed} 项,失败 {failed} 项")
print("=" * 70)
sys.exit(1 if failed else 0)
