#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
检查「数据库里的菜单组件路径」与「前端实际存在的 .vue 文件」是否一一对应。

【为什么需要这个检查】
后台页面走的是动态路由:路由表由后端下发,组件路径是数据库里的一列字符串。
如果某个菜单的 component 写成了 'admin/audit/realname/index',
而实际文件在 'admin/audit/realname.vue',前端不会报错 ——
它会静默回退到占位页,表现为"这个菜单点进去是空的"。
不报错、不白屏、只是没内容,这种问题靠肉眼排查很费时间。

所以每次新增后台页面后跑一遍这个脚本,能提前发现路径不匹配。

用法(需 MySQL 可访问):
    python scripts/check_view_paths.py
"""
import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

VIEWS_ROOT = "D:/bishe/claude/frontend/src/views"

# 从 application-dev.yml 读数据库配置,避免把密码写在脚本里
CONFIG = "D:/bishe/claude/backend/src/main/resources/application-dev.yml"


def read_db_config():
    """从配置文件里取出数据库名与密码"""
    content = open(CONFIG, encoding="utf-8").read()
    db = re.search(r"jdbc:mysql://[^/]+/(\w+)", content)
    pwd = re.search(r"^\s*password:\s*(\S+)", content, re.M)
    if not db or not pwd:
        print("无法从配置文件中读取数据库信息:", CONFIG)
        sys.exit(2)
    return db.group(1), pwd.group(1)


def query_components(db_name, password):
    """查出所有 C 类型菜单的组件路径"""
    sql = ("SELECT component FROM sys_menu "
           "WHERE menu_type = 'C' AND component IS NOT NULL AND component <> '' "
           "ORDER BY id;")
    result = subprocess.run(
        ["mysql", "-h", "127.0.0.1", "-u", "root", "-N", "-e", sql, db_name],
        capture_output=True,
        env={**os.environ, "MYSQL_PWD": password},
    )
    if result.returncode != 0:
        print("查询失败:", result.stderr.decode("utf-8", errors="replace"))
        sys.exit(2)
    return [line.strip() for line in
            result.stdout.decode("utf-8", errors="replace").split("\n") if line.strip()]


def main():
    db_name, password = read_db_config()
    components = query_components(db_name, password)

    print("=" * 64)
    print("后台菜单组件路径检查")
    print("=" * 64)

    missing = []
    for comp in components:
        # 组件路径里用的是 / ,在 Windows 上拼出来会混用分隔符影响阅读,
        # 统一转成当前系统的写法
        path = os.path.normpath(os.path.join(VIEWS_ROOT, comp + ".vue"))
        if os.path.exists(path):
            print(f"  [OK]   {comp}")
        else:
            print(f"  [缺失] {comp}   →  期望文件 {os.path.basename(os.path.dirname(path))}"
                  f"/{os.path.basename(path)}")
            missing.append(comp)

    print()
    print(f"共 {len(components)} 个菜单,缺失 {len(missing)} 个组件")

    if missing:
        print()
        print("修复方式:把对应的 .vue 文件放到 src/views/<component>.vue,")
        print("或者修正数据库里的 component 值。两者必须一致。")
        sys.exit(1)

    print("全部匹配。")
    sys.exit(0)


if __name__ == "__main__":
    main()
