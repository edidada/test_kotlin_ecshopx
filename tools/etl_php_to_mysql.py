#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ETL 脚本（M2-4 批F，docs/04 §29.6 成字、docs/06 手册的执行件）。

两件职责，一次脚本：

  1. `--translate`   方言翻译：把 `sql/schema.sql`（H2 MySQL 兼容模式方言）翻译成
                     MySQL 8 直跑的 `mysql_schema.sql`；`sql/seed.sql` 里唯一一处
                     H2 独家写法（自增序列 `ALTER TABLE x ALTER COLUMN c RESTART WITH 100`）
                     翻成 MySQL 的 `ALTER TABLE x AUTO_INCREMENT = 100`，其余原样带出
                     （写了 INSERT IGNORE/TRUE/FALSE/GREATEST 这些两方言共同子集）。
                     这一步是**首装的一次性动作**，产物给 application-mysql.yml 的
                     sql.init 引用——jar 里不藏第二份 DDL，schema.sql 仍是唯一事实源。

  2. `--migrate`     PHP 旧库搬家：把 PHP ECShopX 旧库（原系统）的核心表数据按列映射
                     写进新库。映射表是显式的（MAP 常量），对着现场旧库改这里即可，
                     不做黑魔法自动推断——搬家搬错一列比少搬一列严重。

为什么 H2/MySQL 有"共同子集"这件事是设计出来的：
  M0 起的纪律就是 DDL 分页写法取交集——主键用内联 `BIGINT AUTO_INCREMENT PRIMARY KEY`
  （不用 H2 的 IDENTITY / MySQL 的 KEY AUTO_INCREMENT=0 两种旁门）、布尔用 BOOLEAN
  （MySQL 原生别名 TINYINT(1)）、默认值常量、`IF NOT EXISTS` / `INSERT IGNORE` 两边都收。
  所以方言差异收敛到一处：CLOB（H2 方言）→ LONGTEXT（MySQL 方言）。

用法：
  python3 tools/etl_php_to_mysql.py --translate --out-dir /opt/ecshopx/sql
  python3 tools/etl_php_to_mysql.py --migrate \
      --old-host ... --old-db ecshopx_php --new-db ecshopx \
      --tables members,goods,orders       # 不给 --tables 则跑 MAP 里全部

依赖：pymysql（只 --migrate 需要；--translate 纯标准库）。
"""

import argparse
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------- 方言翻译

# schema.sql 里 H2 独家、MySQL 吃不了的写法 → MySQL 等价物。显式表，不搞正则黑盒。
DDL_TRANSLATE = [
    # H2 方言的 CLOB（应用层序列化的 JSON/数组/富文本列）→ MySQL 的 LONGTEXT
    (r"\bCLOB\b", "LONGTEXT"),
]
# MySQL 8 直跑仍会翻车的残留词（翻译后扫描报告，人工裁决——脚本不替人做决定）
DDL_RESIDUE = re.compile(
    r"\bIDENTITY\b|\bRESTART WITH\b|\bMERGE INTO\b|\bNEXTVAL\b|\bSET MODE\b|"
    r"\bCOMMENT ON\b|FETCH FIRST|@identity",
    re.IGNORECASE,
)
# H2 独家写法只收字面量：`RESTART [ WITH long ]`（官方语法），"按 MAX(id)+1 复位"写不进 SQL。
# seed 里这批语句翻译为 MySQL 的 AUTO_INCREMENT 等值式；批G 起 H2 侧还有启动件
# IdentityRealignRunner 按 MAX+1 校正（MySQL 的低值重置 InnoDB 自动忽略，语义不受翻译影响）。
SEED_RESTART = re.compile(
    r"ALTER TABLE (\w+) ALTER COLUMN \w+ RESTART WITH (\d+);",
    re.IGNORECASE,
)


def cmd_translate(repo_root: Path, out_dir: Path) -> None:
    schema_src = repo_root / "sql" / "schema.sql"
    seed_src = repo_root / "sql" / "seed.sql"
    text = schema_src.read_text(encoding="utf-8")

    for pat, rep in DDL_TRANSLATE:
        text, n = re.subn(pat, rep, text, flags=re.IGNORECASE)
        print(f"[translate] {pat} -> {rep}: {n} 处")

    # seed 翻译：RESTART WITH（H2 字面量语法）→ MySQL AUTO_INCREMENT；其余已是共同子集
    seed_text = seed_src.read_text(encoding="utf-8")
    seed_text, seed_n = SEED_RESTART.subn(r"ALTER TABLE \1 AUTO_INCREMENT = \2;", seed_text)
    print(f"[translate] seed RESTART -> AUTO_INCREMENT: {seed_n} 处")

    residue = [f"L{i}: {ln.strip()[:80]}" for i, ln in enumerate(text.splitlines(), 1)
               if DDL_RESIDUE.search(ln)]
    seed_residue = [f"seed L{i}: {ln.strip()[:80]}" for i, ln in enumerate(seed_text.splitlines(), 1)
                    if DDL_RESIDUE.search(ln)]
    if residue or seed_residue:
        print("[translate] 警告：以下行仍含两方言之外的写法，请人工裁决：", file=sys.stderr)
        for r in residue + seed_residue:
            print("  " + r, file=sys.stderr)

    out_dir.mkdir(parents=True, exist_ok=True)
    schema_out = out_dir / "mysql_schema.sql"
    schema_out.write_text(
        "-- 本文件由 tools/etl_php_to_mysql.py --translate 从 sql/schema.sql 生成，未提交进库。\n"
        "-- 唯一事实源是 sql/schema.sql；改 DDL 改那边，再跑一次翻译。\n"
        "-- 方言差异收敛为一处：CLOB→LONGTEXT（应用层 JSON/富文本列，语义不变）。\n\n"
        + text,
        encoding="utf-8",
    )
    seed_out = out_dir / "seed.sql"
    # seed 除 RESTART 一族外全部写在两方言共同子集上（INSERT IGNORE / TRUE/FALSE / GREATEST 均原生支持）；
    # RESTART 行已译成 AUTO_INCREMENT 形式（InnoDB 对低值重置自动忽略，覆盖不改语义）
    seed_out.write_text(
        "-- 本文件由 tools/etl_php_to_mysql.py --translate 从 sql/seed.sql 生成，未提交进库。\n"
        "-- 唯一事实源是 sql/seed.sql；改 seed 改那边，再跑一次翻译。\n"
        f"-- 方言差异收敛为一处：RESTART WITH→AUTO_INCREMENT（{seed_n} 处，自增序列下限）。\n\n"
        + seed_text,
        encoding="utf-8",
    )
    print(f"[translate] 产物：{schema_out} / {seed_out}")
    print("[translate] 启动配置：ECX_SQL_DIR 指向该目录后 --spring.profiles.active=mysql")


# ------------------------------------------------------- PHP 旧库 → 新库 列映射

# (旧表, 新表, {旧列: 新列}, 固定列 {新列: 常量值}, 主键-旧列名)
# 固定列是 Python 值（pymysql 会按类型参数化：1 / "onsale" / True 都安全），不拼 SQL 字面量。
# 常量的口径在 docs/06 §数据迁移：搬进来的历史行一律"已成交已收货"态，不触发任何
# 在途流程（不进待发货、不进超时任务、不重算库存）——搬家不动新系统的活账。
# trade 的 order_id 特殊：新库 trade.order_id 是 VARCHAR（旧库 BIGINT），str() 转换写死在
# 下面的搬运行里，不放在映射表——它说的是"值形状"不是"列对应"。
MAP = [
    ("ecs_users", "members_info",
     {"user_id": "member_id", "user_name": "username", "email": "email",
      "mobile_phone": "mobile", "reg_time": "created"},
     {"company_id": 1, "status": 1}, "user_id"),
    ("ecs_goods", "goods",
     {"goods_id": "goods_id", "goods_name": "goods_name", "goods_sn": "goods_sn",
      "goods_brief": "brief", "shop_price": "price", "market_price": "market_price",
      "goods_number": "store", "goods_img": "pics"},
     {"company_id": 1, "approve_status": "onsale"}, "goods_id"),
    ("ecs_order_info", "orders",
     {"order_id": "order_id", "order_sn": "order_sn", "user_id": "member_id",
      "goods_amount": "goods_fee", "shipping_fee": "shipping_fee",
      "order_amount": "order_fee", "add_time": "created"},
     {"company_id": 1, "pay_status": 2, "order_status": 0}, "order_id"),
    ("ecs_order_goods", "order_items",
     {"rec_id": "id", "order_id": "order_id", "goods_id": "goods_id",
      "goods_name": "item_name", "goods_number": "num", "goods_price": "sale_price"},
     {"company_id": 1}, "rec_id"),
    ("ecs_pay_log", "trade",
     {"log_id": "trade_id", "order_id": "order_id", "order_amount": "total_fee",
      "trans_id": "transaction_id"},
     {"company_id": 1, "pay_type": "wxpay", "trade_state": "SUCCESS", "is_settled": True},
     "log_id"),
]


def cmd_migrate(args) -> None:
    try:
        import pymysql
    except ImportError:
        print("缺依赖：pip install pymysql", file=sys.stderr)
        return

    old = pymysql.connect(
        host=args.old_host, port=args.old_port, user=args.old_user,
        password=args.old_password, database=args.old_db, charset="utf8mb4",
    )
    new = pymysql.connect(
        host=args.new_host, port=args.new_port, user=args.new_user,
        password=args.new_password, database=args.new_db, charset="utf8mb4",
        autocommit=False,
    )

    wanted = set(args.tables.split(",")) if args.tables else None
    try:
        for old_t, new_t, colmap, consts, old_pk in MAP:
            if wanted and old_t not in wanted:
                continue
            rows = _read_old(old, old_t, list(colmap))
            n = 0
            for row in rows:
                if row.get(old_pk) is None:
                    continue  # 主键都没有的残行不搬家（也不硬造一个）
                cols, vals = [], []
                for oc, nc in colmap.items():
                    v = row[oc]
                    # trade.order_id：新库 VARCHAR，旧库 BIGINT（§29.6 C241 那条形状差异）
                    if nc == "order_id" and new_t == "trade" and v is not None:
                        v = str(v)
                    cols.append(nc)
                    vals.append(v)
                for c, v in consts.items():
                    cols.append(c)
                    vals.append(v)
                _upsert_new(new, new_t, cols, vals, colmap[old_pk])
                n += 1
            new.commit()
            print(f"[migrate] {old_t} -> {new_t}: {n} 行")
    finally:
        old.close()
        new.close()


def _read_old(conn, table: str, cols: list) -> list:
    with conn.cursor(pymysql.cursors.DictCursor) as cur:
        cur.execute(f"SELECT {', '.join(cols)} FROM {table}")
        return cur.fetchall()


def _upsert_new(conn, table: str, cols: list, vals: list, pk: str) -> None:
    """幂等搬运：主键撞了按旧库值覆盖（搬家可以反复跑，跑出同一份结果）。"""
    ph = ", ".join(["%s"] * len(cols))
    assign = ", ".join(f"{c} = VALUES({c})" for c in cols if c != pk)
    sql = (f"INSERT INTO {table} ({', '.join(cols)}) VALUES ({ph}) "
           f"ON DUPLICATE KEY UPDATE {assign}")
    with conn.cursor() as cur:
        cur.execute(sql, vals)


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    p = argparse.ArgumentParser(description="ECShopX PHP→Kotlin 生产化 ETL")
    p.add_argument("--translate", action="store_true", help="schema/seed 方言翻译出 mysql_schema.sql 与 seed.sql")
    p.add_argument("--out-dir", default=str(root / "build" / "sql"))
    p.add_argument("--migrate", action="store_true", help="PHP 旧库数据搬家")
    p.add_argument("--old-host", default="127.0.0.1"); p.add_argument("--old-port", type=int, default=3306)
    p.add_argument("--old-user", default="root"); p.add_argument("--old-password", default="")
    p.add_argument("--old-db", default="ecshopx_php")
    p.add_argument("--new-host", default="127.0.0.1"); p.add_argument("--new-port", type=int, default=3306)
    p.add_argument("--new-user", default="root"); p.add_argument("--new-password", default="")
    p.add_argument("--new-db", default="ecshopx")
    p.add_argument("--tables", default="")
    args = p.parse_args()

    if args.translate:
        cmd_translate(root, Path(args.out_dir))
    elif args.migrate:
        cmd_migrate(args)
    else:
        p.print_help()


if __name__ == "__main__":
    main()
