#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
外来用户备案 —— 端到端实测(需先启动后端)。

覆盖:
  1. 越权:村管把人登记到别人的村 → 必须 403
  2. 叶子村校验:登记到乡镇(唐昌镇) → 必须 400
  3. 不存在的村组 → 必须 400
  4. 正常登记 → 用户 village_id 仍为 NULL,备案单待审核
  5. 数据范围:超管看得到,非目标村的村管看不到
  6. 越权审核:非目标村的村管审这条单 → 必须 403
  7. 审核通过 → village_id 落库,is_outside 仍为 1
  8. 站内信跳转 → linkPath 必须是 /user/profile   ← 本次改动的核心
  9. 审核意见超 500 字 → 必须被 @Size 拦下
     (审核请求一律【不带 bizType】—— 该字段由服务端按端点强制写入)
 10. 改密码 → 验原密码、旧密码失效、新密码可用

用法:python scripts/outside_e2e_test.py
"""
import json
import subprocess
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "village_rental"

# 本次测试专用的外来人员手机号(每次跑之前先清掉,保证可重复)
OUTSIDER_PHONE = "13900007777"
OUTSIDER_NAME = "测试外来户"
TARGET_VILLAGE = 3        # 先锋村
TOWN_NODE = 1             # 唐昌镇(顶级节点,不是村)

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
        with urllib.request.urlopen(req, timeout=15) as resp:
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


def denied(status, res, code=403):
    """判断是否被拒。两种约定都要认:框架级(真 HTTP 码)
    与业务级(HTTP 200 + body 里的 code)。"""
    return status == code or res.get("code") == code


def sql(query):
    """跑一条查询,返回([表头], [行])。"""
    out = subprocess.run(
        [MYSQL, "-uroot", "-proot", "--default-character-set=utf8mb4",
         "-N", "-B", DB, "-e", query],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    rows = [line.split("\t") for line in out.stdout.strip().splitlines() if line.strip()]
    return rows


def login(phone, password="123456"):
    status, res = call("POST", "/api/auth/login", {"phone": phone, "password": password})
    if status == 200 and res.get("code") == 200:
        return res["data"]["token"]
    return None


def cleanup():
    """把这轮测试造出来的数据清掉,让脚本可重复运行。"""
    sql(f"DELETE FROM sys_audit_record WHERE biz_id IN "
        f"(SELECT id FROM sys_user WHERE phone='{OUTSIDER_PHONE}') AND biz_type=3")
    sql(f"DELETE FROM msg_notice WHERE user_id IN "
        f"(SELECT id FROM sys_user WHERE phone='{OUTSIDER_PHONE}')")
    sql(f"DELETE FROM sys_user_role WHERE user_id IN "
        f"(SELECT id FROM sys_user WHERE phone='{OUTSIDER_PHONE}')")
    sql(f"DELETE FROM sys_user WHERE phone='{OUTSIDER_PHONE}'")


print("=" * 70)
print("外来用户备案 —— 端到端实测")
print("=" * 70)

cleanup()

# ── 准备 ────────────────────────────────────────────────────────────────
admin = login("13800000000")       # 超级管理员
village_admin = login("13800000001")  # 战旗村支书(village_id=2)
other_village_admin = login("13800000002")  # 先锋村主任(village_id=3)

check("超管登录成功", admin is not None)
check("战旗村支书登录成功", village_admin is not None)
check("先锋村主任登录成功", other_village_admin is not None)
if not all([admin, village_admin, other_village_admin]):
    print("\n登录失败,后续无法进行")
    sys.exit(1)

# ── [1] 越权:村管把外来人员塞进别人的村 ───────────────────────────────
print("\n[1] 越权 —— 村管不能把外来人员登记到别的村")
status, res = call("POST", "/api/admin/user/outside",
                   {"phone": "13900001111", "realName": "越权测试", "villageId": TARGET_VILLAGE},
                   token=village_admin)
check(f"战旗村支书登记到先锋村(villageId={TARGET_VILLAGE}) 被拒", denied(status, res, 403),
      f"实际 status={status} res={res}")

# ── [2] 叶子村校验:不能登记到乡镇 ──────────────────────────────────────
print("\n[2] 叶子村校验 —— 只能登记到村,不能登记到乡镇")
status, res = call("POST", "/api/admin/user/outside",
                   {"phone": "13900002222", "realName": "乡镇测试", "villageId": TOWN_NODE},
                   token=admin)
check(f"登记到乡镇(唐昌镇 villageId={TOWN_NODE}) 被拒",
      denied(status, res, 400) and "村" in str(res.get("message", "")),
      f"实际 status={status} res={res}")

# ── [3] 不存在的村组 ────────────────────────────────────────────────────
print("\n[3] 不存在的村组")
status, res = call("POST", "/api/admin/user/outside",
                   {"phone": "13900003333", "realName": "幽灵村", "villageId": 99999},
                   token=admin)
check("登记到不存在的村组被拒", denied(status, res, 400), f"实际 status={status} res={res}")

# ── [4] 正常登记 ────────────────────────────────────────────────────────
print("\n[4] 正常登记 —— 此时用户还没有归属村组")
status, res = call("POST", "/api/admin/user/outside",
                   {"phone": OUTSIDER_PHONE, "realName": OUTSIDER_NAME,
                    "villageId": TARGET_VILLAGE, "address": "成都市青羊区"},
                   token=admin)
check("登记成功", status == 200 and res.get("code") == 200,
      f"实际 status={status} res={res}")

rows = sql(f"SELECT id, village_id, is_outside, realname_status FROM sys_user "
           f"WHERE phone='{OUTSIDER_PHONE}'")
check("账号已建立", len(rows) == 1, f"实际 {rows}")
new_user_id = int(rows[0][0]) if rows else None
check("village_id 仍为 NULL(备案通过前不落户)", rows and rows[0][1] == "NULL",
      f"实际 village_id={rows[0][1] if rows else 'N/A'}")
check("is_outside = 1", rows and rows[0][2] == "1", f"实际 {rows[0][2] if rows else 'N/A'}")

rows = sql(f"SELECT id, biz_type, biz_id, applicant_id, village_id, audit_status "
           f"FROM sys_audit_record WHERE biz_type=3 AND biz_id={new_user_id}")
check("备案单已生成且为待审核", len(rows) == 1 and rows[0][5] == "0", f"实际 {rows}")
audit_id = int(rows[0][0]) if rows else None
check("备案单的 village_id 指向目标村", rows and rows[0][4] == str(TARGET_VILLAGE),
      f"实际 {rows[0][4] if rows else 'N/A'}")

# ── [5] 数据范围 ────────────────────────────────────────────────────────
print("\n[5] 数据范围 —— 只有目标村的村管能看到这条待办")
status, res = call("GET", "/api/admin/user/outside/list?pageNum=1&pageSize=50", token=admin)
ids = [r["id"] for r in res["data"]["rows"]] if status == 200 else []
check("超管能看到这条备案", audit_id in ids, f"实际 status={status} ids={ids}")

status, res = call("GET", "/api/admin/user/outside/list?pageNum=1&pageSize=50",
                   token=other_village_admin)
ids = [r["id"] for r in res["data"]["rows"]] if status == 200 else []
check("先锋村主任能看到这条备案(目标村)", audit_id in ids, f"实际 ids={ids}")

status, res = call("GET", "/api/admin/user/outside/list?pageNum=1&pageSize=50",
                   token=village_admin)
ids = [r["id"] for r in res["data"]["rows"]] if status == 200 else []
check("战旗村支书看不到这条备案(非本村)", audit_id not in ids, f"实际 ids={ids}")

# ── [6] 越权审核 ────────────────────────────────────────────────────────
print("\n[6] 越权审核 —— 非目标村的村管不能审这条单")
status, res = call("POST", "/api/admin/user/outside/audit",
                   {"auditId": audit_id, "approved": True, "remark": "越权审核"},
                   token=village_admin)
check("战旗村支书审核先锋村的备案被拒", denied(status, res, 403) or denied(status, res, 400),
      f"实际 status={status} res={res}")

# ── [7] 审核意见超长(本次新增的 @Size)────────────────────────────────
print("\n[7] 审核意见长度上限 @Size(max=500)")
status, res = call("POST", "/api/admin/user/outside/audit",
                   {"auditId": audit_id, "approved": False, "remark": "长" * 501},
                   token=admin)
check("501 字的审核意见被拦下,且原因是长度而非其他",
      denied(status, res, 400) and "500" in str(res.get("message", "")),
      f"实际 status={status} res={res}")

# ── [8] 审核通过 ────────────────────────────────────────────────────────
print("\n[8] 审核通过 —— 目标村组落库")
status, res = call("POST", "/api/admin/user/outside/audit",
                   {"auditId": audit_id, "approved": True, "remark": "资料无误,准予备案"},
                   token=admin)
check("审核通过成功", status == 200 and res.get("code") == 200,
      f"实际 status={status} res={res}")

rows = sql(f"SELECT village_id, is_outside FROM sys_user WHERE id={new_user_id}")
check(f"village_id 已写入目标村({TARGET_VILLAGE})",
      rows and rows[0][0] == str(TARGET_VILLAGE), f"实际 {rows}")
check("is_outside 仍为 1(永久身份标记)", rows and rows[0][1] == "1", f"实际 {rows[0][1] if rows else 'N/A'}")

rows = sql(f"SELECT audit_status FROM sys_audit_record WHERE id={audit_id}")
check("备案单状态变为已通过(1)", rows and rows[0][0] == "1", f"实际 {rows}")

# ── [9] 站内信跳转 ← 本次改动的核心 ────────────────────────────────────
print("\n[9] 站内信 —— 备案通知的 linkPath(本次改动核心)")
outsider_token = login(OUTSIDER_PHONE)
check("外来用户可用初始密码 123456 登录", outsider_token is not None)

status, res = call("GET", "/api/notice/list?pageNum=1&pageSize=20", token=outsider_token)
records = (res.get("data") or {}).get("rows") or []
outside_notices = [n for n in records if n.get("noticeType") == 11]
check("收到了备案通知(noticeType=11)", len(outside_notices) >= 1,
      f"实际 noticeType 列表={[n.get('noticeType') for n in records]}")

if outside_notices:
    n = outside_notices[0]
    print(f"       标题: {n.get('title')}")
    print(f"       linkPath: {n.get('linkPath')!r}")
    check("linkPath 指向 /user/profile", n.get("linkPath") == "/user/profile",
          f"实际 linkPath={n.get('linkPath')!r}")
else:
    check("linkPath 指向 /user/profile", False, "没有找到备案通知")

# ── [10] 改密码 ─────────────────────────────────────────────────────────
print("\n[10] 修改密码")
status, res = call("POST", "/api/auth/changePassword",
                   {"oldPassword": "wrong-password", "newPassword": "newpass123"},
                   token=outsider_token)
check("原密码错误被拒", denied(status, res, 400), f"实际 status={status} res={res}")

status, res = call("POST", "/api/auth/changePassword",
                   {"oldPassword": "123456", "newPassword": "newpass123"},
                   token=outsider_token)
check("原密码正确时修改成功", status == 200 and res.get("code") == 200,
      f"实际 status={status} res={res}")

check("旧密码 123456 已失效", login(OUTSIDER_PHONE, "123456") is None)
check("新密码 newpass123 可登录", login(OUTSIDER_PHONE, "newpass123") is not None)

# ── [11] 无权限角色 ─────────────────────────────────────────────────────
print("\n[11] 权限 —— 村民不能访问备案接口")
villager = login("13800000003")   # 张建国,VILLAGER
status, res = call("GET", "/api/admin/user/outside/list", token=villager)
check("村民访问备案列表被拒(403)", denied(status, res, 403), f"实际 status={status} res={res}")

status, res = call("GET", "/api/admin/user/outside/list")
check("无 token 访问被拒(401)", denied(status, res, 401), f"实际 status={status} res={res}")

# ── 收尾 ────────────────────────────────────────────────────────────────
print("\n[清理] 删除本轮测试数据")
cleanup()
rows = sql(f"SELECT COUNT(*) FROM sys_user WHERE phone='{OUTSIDER_PHONE}'")
check("测试账号已删除", rows[0][0] == "0", f"实际 {rows}")

print("\n" + "=" * 70)
print(f"结果:通过 {passed} 项,失败 {failed} 项")
print("=" * 70)
sys.exit(1 if failed else 0)
