#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次 1(认证与权限)接口验证脚本。

用来验证三层权限是否真的生效:
  1. 认证 —— 登录拿 token,无 token 访问受保护接口应被拒
  2. 菜单级 —— 不同角色拿到的路由树不同
  3. 按钮级 —— 越权调用接口应返回 403
  4. 数据级 —— 村级管理员只能看到本村数据,且前端传的 villageId 会被服务端覆盖

用法(需先启动后端):
    python scripts/api_test.py
"""
import json
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
sys.stdout.reconfigure(encoding="utf-8")

passed = 0
failed = 0


def call(method, path, body=None, token=None):
    """发一个请求,返回 (http_status, 解析后的 json 或 None)。"""
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
        return res["data"]["token"], res["data"]["userInfo"]
    return None, res


print("=" * 70)
print("批次 1 —— 认证与权限 验证")
print("=" * 70)

# ── 1. 登录 ────────────────────────────────────────────────────
print("\n[1] 认证")
super_token, super_info = login("13800000000")
check("超级管理员登录成功", super_token is not None, str(super_info)[:120])

village_token, village_info = login("13800000001")
check("村级管理员登录成功", village_token is not None, str(village_info)[:120])

bad_token, bad_res = login("13800000000", "wrongpassword")
check("错误密码被拒绝", bad_token is None, str(bad_res)[:100])

no_user_token, no_user_res = login("13900000000")
check("未注册手机号被拒绝", no_user_token is None, str(no_user_res)[:100])

# ── 2. 无 token 访问受保护接口 ──────────────────────────────────
print("\n[2] 未登录访问受保护接口")
status, res = call("GET", "/api/auth/getInfo")
check("无 token 获取用户信息返回 401", res.get("code") == 401, str(res)[:100])

# ── 3. 菜单级权限 ──────────────────────────────────────────────
print("\n[3] 菜单级权限")
_, super_routers = call("GET", "/api/auth/getRouters", token=super_token)
_, village_routers = call("GET", "/api/auth/getRouters", token=village_token)

super_top = [r["path"] for r in (super_routers.get("data") or [])]
village_top = [r["path"] for r in (village_routers.get("data") or [])]

print(f"      超级管理员可见目录({len(super_top)}):{super_top}")
print(f"      村级管理员可见目录({len(village_top)}):{village_top}")

check("超级管理员能看到'系统管理'", "/admin/system" in super_top)
check("村级管理员看不到'系统管理'", "/admin/system" not in village_top)
check("村级管理员能看到'审核管理'", "/admin/audit" in village_top)
check("两者菜单数量不同", len(super_top) != len(village_top),
      f"super={len(super_top)} village={len(village_top)}")

# 村民/买家不应有后台菜单
villager_token, _ = login("13800000003")
_, villager_routers = call("GET", "/api/auth/getRouters", token=villager_token)
check("村民没有后台菜单(走前台门户)",
      len(villager_routers.get("data") or []) == 0,
      str(villager_routers.get("data"))[:80])

# ── 4. 按钮级权限 ──────────────────────────────────────────────
print("\n[4] 按钮级权限")
status, super_role = call("GET", "/api/system/role/list", token=super_token)
check("超级管理员可访问角色管理", super_role.get("code") == 200, str(super_role)[:100])

status, village_role = call("GET", "/api/system/role/list", token=village_token)
check("村级管理员访问角色管理返回 403",
      village_role.get("code") == 403, str(village_role)[:120])

# ── 5. 数据级权限 ──────────────────────────────────────────────
print("\n[5] 数据级权限")
_, super_users = call("GET", "/api/admin/user/list?pageSize=100", token=super_token)
_, village_users = call("GET", "/api/admin/user/list?pageSize=100", token=village_token)

super_rows = (super_users.get("data") or {}).get("rows") or []
village_rows = (village_users.get("data") or {}).get("rows") or []

super_villages = sorted({r.get("villageName") for r in super_rows if r.get("villageName")})
village_villages = sorted({r.get("villageName") for r in village_rows if r.get("villageName")})

print(f"      超级管理员可见用户 {len(super_rows)} 人,涉及村组:{super_villages}")
print(f"      村级管理员可见用户 {len(village_rows)} 人,涉及村组:{village_villages}")

check("超级管理员能看到多个村的数据", len(super_villages) > 1)
check("村级管理员只能看到本村数据", village_villages == ["战旗村"],
      f"实际:{village_villages}")

# 最关键的一条:前端强行指定别的村,服务端必须覆盖掉
status, forced = call("GET", "/api/admin/user/list?villageId=3&pageSize=100", token=village_token)
forced_rows = (forced.get("data") or {}).get("rows") or []
forced_villages = sorted({r.get("villageName") for r in forced_rows if r.get("villageName")})
print(f"      村级管理员强行传 villageId=3(先锋村),实际看到:{forced_villages}")
check("前端传 villageId 被服务端覆盖(越权无效)", forced_villages == ["战旗村"],
      f"实际:{forced_villages} —— 存在越权!")

# ── 6. 注册 ────────────────────────────────────────────────────
print("\n[6] 注册")
import random
new_phone = "139" + str(random.randint(10000000, 99999999))
status, reg = call("POST", "/api/auth/register", {
    "phone": new_phone,
    "password": "test123456",
    "realName": "测试用户",
    "villageId": 2,
    "userType": 2
})
check("新用户注册成功", reg.get("code") == 200, str(reg)[:150])

status, dup = call("POST", "/api/auth/register", {
    "phone": new_phone, "password": "test123456", "realName": "测试用户", "villageId": 2
})
check("重复手机号被拒绝", dup.get("code") == 1003, str(dup)[:120])

status, badphone = call("POST", "/api/auth/register", {
    "phone": "12345", "password": "test123456", "realName": "测试"
})
check("非法手机号被参数校验拦下", badphone.get("code") == 400, str(badphone)[:120])

# 新注册用户应能登录,但实名未认证
new_token, new_info = login(new_phone, "test123456")
check("新注册用户可登录", new_token is not None)
if new_info:
    check("新用户实名状态为未提交(0)", new_info.get("realnameStatus") == 0,
          f"实际:{new_info.get('realnameStatus')}")
    check("新用户初始信用分为 100", new_info.get("creditScore") == 100,
          f"实际:{new_info.get('creditScore')}")
    check("新用户拥有 BUYER 角色", "BUYER" in (new_info.get("roles") or []),
          str(new_info.get("roles")))

# ── 汇总 ───────────────────────────────────────────────────────
print("\n" + "=" * 70)
print(f"结果:通过 {passed} 项,失败 {failed} 项")
print("=" * 70)
sys.exit(1 if failed else 0)
