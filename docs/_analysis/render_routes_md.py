#!/usr/bin/env python3
"""Render docs/_analysis/routes_raw.json into the full URL inventory appendix."""
import json, os
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
rows = json.load(open(os.path.join(HERE, "routes_raw.json"), encoding="utf-8"))

SURFACE = OrderedDict([
    ("api", ("A. 商家/运营后台 API", "基址 `/api/…`，鉴权 provider `jwt`（运营人员/商家 JWT）")),
    ("admin", ("B. 店铺员工小程序端 API", "基址 `/api/admin/wxapp/…`，provider `adminwxapp`（导购 x-wxapp-session）")),
    ("frontapi", ("C. 消费者端 API（H5/小程序）", "基址 `/api/h5app/…` 或 `/api/wxapp/…`，provider `wxapp`/`jwt`/公开")),
    ("super", ("D. 平台超管 API", "基址 `/api/superadmin/…`、`/api/super/…`，provider `superadmin`")),
    ("systemlink", ("E. ERP 系统对接 API", "基址 `/api/systemlink/…`")),
    ("thirdparty", ("F. 第三方回调 / 开放平台 API", "基址 `/api/third/…`、`/api/openapi/…`、`/api/saaserp/…`")),
    ("web.php", ("G. Web 回调路由（Lumen 原生）", "微信授权事件、支付通知等，无 `/api` 前缀")),
])

def esc(x):
    return (x or "").replace("|", "\\|").strip()

out = []
out.append("# ECShopX PHP 全量路由清单（迁移参考附录）\n")
out.append(f"共 **{len(rows)}** 条端点，由 `docs/_analysis/extract_routes.py` 从 `thirdparty/ECShopX/routes/**` 机械提取。\n")
out.append("Kotlin 重写的完整 URL 迁移对照表；接口逐条的入参/出参规格见 [URL 列表文档](./04-URL列表文档.md)（核心接口）与各模块详细设计。\n")
stats = {}
groups = OrderedDict((k, []) for k in SURFACE)
for r in rows:
    key = r["file"].split("/")[0]
    key = "web.php" if key == "web.php" else key.removesuffix(".php") if "/" not in r["file"] else key
    groups.setdefault(key, []).append(r)

for key, (title, note) in SURFACE.items():
    items = groups.get(key, [])
    if not items:
        continue
    stats[title] = len(items)
    out.append(f"\n## {title}（{len(items)} 条）\n")
    out.append(f"> {note}\n")
    cur = None
    for r in sorted(items, key=lambda x: (x["file"], x["line"])):
        if r["file"] != cur:
            cur = r["file"]
            out.append(f"\n### `routes/{cur}`\n")
            out.append("| 方法 | URL | 接口名 | 处理器 (uses) | 鉴权 | 中间件 |")
            out.append("|---|---|---|---|---|---|")
        url = r["url"] if key == "web.php" else "/api" + r["url"]
        handler = r["uses"]
        if handler and r["namespace"]:
            ns = r["namespace"].split("\\")[-1]
            handler = f"{ns}\\{handler}"
        out.append(f"| {r['method']} | `{url}` | {esc(r['name'])} | {esc(handler)} | {esc(r['providers'])} | {esc(r['middleware'])} |")

with open(os.path.join(HERE, "..", "附录-ECShopX全量路由清单.md"), "w", encoding="utf-8") as f:
    f.write("\n".join(out) + "\n")
print("written, sections:", stats)
