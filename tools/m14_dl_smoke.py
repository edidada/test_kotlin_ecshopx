import json
import time
import urllib.error
import urllib.parse
import urllib.request

"""M1-4 第二批实跑冒烟（佣金 DL + 提现 CW）：真 fat jar 上把 §18.5 那条钱链走一遍。

CommissionEndToEndTest（16 例）管"口径对不对"，本脚本管"起得来的服务对不对"：
权限码缓存（47..50 四条新码在真 JWT 上生不生效）、`sys_config.commission.close_delay`
在常驻演示库里的实际读数、上一轮脚本留下的在途佣金与提现行 —— 这些内存测试都碰不到。

覆盖：
 1. **入账跟着钱走**：建单后 DL-A1 查不到行，`pay→notify` 结清那一刻才有，
    金额取订单行的 `total_rebate`（= `items.rebate × num`，本案把商品 3 的佣金设成 500 分/件）；
 2. **幂等**：回调重放一次，这一单的流水仍是 1 行；
 3. **到期才结、结过不再结**：`to_time=now` 一行不动（delay 还没到），
    给一个未来的 `to_time` 才置位，第二次同窗 `closed_count=0`；
 4. **CW-F1 四道闸门**：不是本店 403、金额非法 422、在途唯一 422、余额不足 422，
    通过后才落 `applied`；
 5. **CW-A2 状态机**：非法目标 400、`applied→paid` 跨态 422、终态再动 422，
    走完 `applied→confirmed→paid`；`paid` 仍占余额（C149）；
 6. **CW-A3 打款信息**：`paid` 之前 `paid_time=0` 且账户栏写"待财务打款"；
 7. **权限与数据权限**（§18.7/§18.8）：店铺运营岗只有 `distribution.logs`，
    于是它在 DL-A1 上 200 且只看绑定店，在 DL-A3/CW-A1/CW-A2 上全 403；会员令牌 401；
 8. **冲销分两段**（D52）：已结算的行退款 → 不回改正向行，补一条等额负向流水。

脚本按**差值**断言：常驻演示库会被上一轮自己与 m05/m06/m13 等脚本动过，
账上的绝对数不是常数。收尾把 `items.rebate` 还原，不给下一轮留佣金。
"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
SPW = "shop" + "123"
VCODE = "8888"
MEMBER = "13800000001"
SHOP = 2                       # seed 的 demo 旗舰店（CW-F1 的在册负责人就是演示会员）
OTHER_SHOP = 1                 # 商城总部：本案的会员不是它的在册员工
ITEM = 3
UNIT = 8900
NUM = 2
REBATE = 500                   # 每件佣金（分）：本案现场设进 items.rebate
DAY = 86400
RUN = "DL%d" % int(time.time())
ok = []


def req(method, path, body=None, token=None):
    payload = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=payload, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def raw(path, body, ctype):
    r = urllib.request.Request(BASE + path, data=body.encode(), method="POST")
    r.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")


def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else " -> " + str(extra)[:400]))


def data(method, path, body=None, token=None):
    st, res = req(method, path, body, token)
    assert st == 200, "%s %s -> %s %s" % (method, path, st, res)
    return res["data"]


def login(path, body):
    st, res = req("POST", path, body)
    assert st == 200, res
    return res["data"]["token"]


def fail(method, path, body, token=None):
    return req(method, path, body, token)


atok = login("/api/operator/login", {"username": "admin", "password": PW})
mtok = login("/api/h5app/wxapp/new_login",
             {"auth_type": "local", "username": MEMBER, "check_type": "mobile", "vcode": VCODE})
stok = login("/api/operator/login", {"username": "distributor01", "password": SPW})
assert atok and mtok and stok, "三个令牌都要拿到（admin / 演示会员 / 店铺运营）"


def stats():
    return data("GET", "/api/distribution/count?distributor_id=%d" % SHOP, token=atok)


def logs_of(oid):
    return data("GET", "/api/distribution/logs?order_id=%s&pageSize=50" % oid, token=atok)


def close(to_time, distributor_id=None):
    body = {"to_time": to_time}
    if distributor_id:
        body["distributor_id"] = distributor_id
    return data("POST", "/api/distribution/logs/close", body, atok)


def notify(trade_id, fee, state="SUCCESS"):
    fields = {"out_trade_no": trade_id, "total_fee": str(fee), "trade_state": state,
              "transaction_id": "WX-" + trade_id[-6:]}
    return raw("/api/notify/wxpay", urllib.parse.urlencode(fields), "application/x-www-form-urlencoded")


# ===== 0. 佣金基数：本案把商品 3 设成 500 分/件，收尾还原 =====
# GOODS-4 是读-改-写，但校验要求 item_name/price/store 必给（缺一个就 400），
# 关联表字段留给 null 表示"不动"，所以这里只回填那三栏 + 要改的 rebate/store。
before_item = data("GET", "/api/goods/items/%d" % ITEM, token=atok)
old_rebate = before_item["rebate"]
old_store = before_item["store"]
UNIT = before_item["price"]
assert UNIT > 0, "商品 3 单价拿来算回调金额，不能是 0"


def patch_item(rebate, store=None):
    body = {"item_name": before_item["item_name"], "price": UNIT, "rebate": rebate}
    body["store"] = store if store is not None else data("GET", "/api/goods/items/%d" % ITEM, token=atok)["store"]
    data("PUT", "/api/goods/items/%d" % ITEM, body, atok)
    return data("GET", "/api/goods/items/%d" % ITEM, token=atok)


after_item = patch_item(REBATE, max(old_store, 50))
check("GOODS-4 写入 items.rebate 后读得到", after_item["rebate"] == REBATE, after_item["rebate"])
check("库存抬到够下单（常驻库会被往轮买走）", after_item["store"] >= NUM + 10, after_item["store"])

s0 = stats()
check("DL-A2 契约：八个键齐（含 withdraw_occupying）",
      all(k in s0 for k in ["commission_total", "commission_open", "commission_closed",
                            "withdraw_applied", "withdraw_paid", "withdraw_refused",
                            "withdraw_occupying", "balance"]), s0)
check("DL-A2 余额非负（常驻库有残账，只断言公式下限）", s0["balance"] >= 0, s0)

# ===== 1. 未付款不入账 =====
data("POST", "/api/h5app/wxapp/cart", {"item_id": ITEM, "num": NUM, "distributor_id": SHOP}, mtok)
oid = data("POST", "/api/h5app/wxapp/order_new", {
    "cart_type": "cart", "order_type": "normal", "distributor_id": SHOP,
    "receiver_name": "张三", "receiver_mobile": MEMBER, "receiver_state": "浙江省",
    "receiver_city": "杭州市", "receiver_district": "西湖区", "receiver_address": "文三路 1 号",
    "order_from": "h5",
}, mtok)["order_id"]
check("ORDER-2 建在加盟店名下", str(oid) == str(int(oid)), oid)
check("未付款不入账：DL-A1 这一单 0 行", logs_of(oid)["total_count"] == 0, logs_of(oid))

# ===== 2. 结清即入账 + 幂等 =====
t = data("POST", "/api/h5app/wxapp/payment", {"order_id": str(oid), "pay_type": "wxpay"}, mtok)
st, body = notify(t["trade_id"], UNIT * NUM)
check("回调应答成功", "SUCCESS" in body, (st, body))
st, body = notify(t["trade_id"], UNIT * NUM)
check("回调重放仍应答成功且不双计", "SUCCESS" in body and logs_of(oid)["total_count"] == 1,
      (st, body, logs_of(oid)["total_count"]))

p = logs_of(oid)["list"][0]
check("DL-A1 行契约（含 order_no 与掩码昵称位）",
      all(k in p for k in ["id", "order_id", "order_no", "distributor_id", "item_id", "item_name",
                           "user_id", "shop_id", "num", "nickname", "rebate", "total_rebate",
                           "is_close", "plan_close_time", "created", "updated"]), sorted(p))
check("单号出参是字符串且与 order_no 同值",
      p["order_id"] == str(oid) == p["order_no"], (p["order_id"], p["order_no"]))
check("佣金 = items.rebate × num（取行上的 total_rebate，不重算费率 D46）",
      p["rebate"] == REBATE * NUM and p["total_rebate"] == REBATE * NUM, p["rebate"])
check("归属落在被选中的那家店", p["distributor_id"] == SHOP and p["num"] == NUM, p)
check("未结算：入账即在途（delay 由 sys_config 决定）", p["is_close"] is False, p["is_close"])
check("plan_close_time 在未来（本案读到 close_delay=7 天）", p["plan_close_time"] > int(time.time()), p)
check("买家昵称出得来", bool(p["nickname"]), p["nickname"])

s1 = stats()
check("DL-A2 open 随入账增长 1000", s1["commission_open"] - s0["commission_open"] == REBATE * NUM,
      (s0["commission_open"], s1["commission_open"]))
check("在途的钱进不了余额", s1["balance"] == s0["balance"], (s0["balance"], s1["balance"]))

# ===== 3. 结算：未到期不动 / 到期一把结 / 同窗幂等 =====
now = int(time.time())
c0 = close(now)
check("DL-A3 未到期 closed_count=0", c0["closed_count"] == 0 and c0["closed_fee"] == 0, c0)
future = now + 8 * DAY
c1 = close(future)
check("DL-A3 到期一次结清（本案这单必在其中）", c1["closed_count"] >= 1, c1)
check("结算出参带 to_time 与 distributor_id", c1["to_time"] == future and c1["distributor_id"] == 0, c1)
check("结完这一行就是已结算", logs_of(oid)["list"][0]["is_close"] is True, logs_of(oid)["list"][0])
c2 = close(future)
check("同一时间窗第二次 0 行（幂等）", c2["closed_count"] == 0 and c2["closed_fee"] == 0, c2)
s2 = stats()
check("余额随结算上涨（已结算 − 占用）", s2["commission_closed"] - s1["commission_closed"] >= REBATE * NUM,
      (s1["commission_closed"], s2["commission_closed"]))
check("DL-A3 按店收窄：另一家店无到期行", close(future, OTHER_SHOP)["closed_count"] == 0,
      close(future, OTHER_SHOP))

# ===== 4. CW-F1 四道闸门 =====
st, res = fail("POST", "/api/h5app/wxapp/cash_withdrawal", {"distributor_id": OTHER_SHOP, "money": 100}, mtok)
check("不是你的店 → 403（归属只认在册员工行 C152）", st == 403, (st, res))
st, res = fail("POST", "/api/h5app/wxapp/cash_withdrawal", {"distributor_id": SHOP, "money": 0}, mtok)
check("金额 0 → 422 + errors.money", st == 422 and res["errors"]["money"] == ["提现金额必须大于 0"], (st, res))
st, res = fail("POST", "/api/h5app/wxapp/cash_withdrawal", {"distributor_id": SHOP, "money": 99999999}, mtok)
check("超出可提现 → 422 并写明余额", st == 422 and str(s2["balance"]) in res["errors"]["money"][0], (st, res))
st, res = fail("POST", "/api/h5app/wxapp/cash_withdrawal", {"distributor_id": SHOP, "money": 100}, None)
check("匿名提现 → 401 Unauthenticated.", st == 401 and res.get("message") == "Unauthenticated.", (st, res))

bal = s2["balance"]
assert bal > 0, "结完必有余额，否则后面的链跑不动"
w = data("POST", "/api/h5app/wxapp/cash_withdrawal", {"distributor_id": SHOP, "money": bal}, mtok)
wid = w["id"]
check("CW-F1 出参契约", all(k in w for k in ["id", "distributor_id", "shop_name", "user_id",
                                             "money", "status", "status_text", "paid_time",
                                             "created", "updated", "remarks"]), sorted(w))
check("落 applied 且 paid_time=0（本表无该列，C151）",
      w["status"] == "applied" and w["status_text"] == "待处理" and w["paid_time"] == 0, w)
check("带店名与申请额", w["shop_name"] == "demo 旗舰店" and w["money"] == bal, w)
st, res = fail("POST", "/api/h5app/wxapp/cash_withdrawal", {"distributor_id": SHOP, "money": 100}, mtok)
check("在途唯一 → 422（资格判据排在余额之前）",
      st == 422 and "已有提现申请待处理" in res["message"], (st, res))
s3 = stats()
check("提完即见底（balance 纯派生，不写缓存列 C149）",
      s3["withdraw_occupying"] - s2["withdraw_occupying"] == bal and s3["balance"] == 0,
      (s2["withdraw_occupying"], s3["withdraw_occupying"], s3["balance"]))

# ===== 5. CW-A3 打款信息（paid 之前） =====
i0 = data("GET", "/api/distributor/cash_withdrawal/payinfo/%d" % wid, token=atok)
check("CW-A3 契约 + mock 回执 pay_type=offline（D47）",
      i0["pay_type"] == "offline" and all(k in i0 for k in ["id", "money", "status", "paid_time", "account"]), i0)
check("未打款：paid_time=0 且账户栏写待财务打款",
      i0["paid_time"] == 0 and i0["account"]["tip"] == "待财务打款", i0)
check("账户名是店名、电话已掩码",
      i0["account"]["name"] == "demo 旗舰店" and "****" in i0["account"]["mobile"], i0["account"])
st, _ = req("GET", "/api/distributor/cash_withdrawal/payinfo/999999", None, atok)
check("不存在的提现单 → 404", st == 404, st)

# ===== 6. CW-A2 状态机 =====
st, res = fail("PUT", "/api/distribution/cash_withdrawal/%d" % wid, {"status": "applied"}, atok)
check("非法目标 applied → 400", st == 400 and "只支持" in res["errors"]["status"][0], (st, res))
st, res = fail("PUT", "/api/distribution/cash_withdrawal/%d" % wid, {"status": "paid"}, atok)
check("applied→paid 跨态 → 422（申请与打款分人分步 §18.7）", st == 422 and "不能迁到" in res["message"], (st, res))
c = data("PUT", "/api/distribution/cash_withdrawal/%d" % wid, {"status": "confirmed", "remarks": "核对无误"}, atok)
check("applied→confirmed 200", c["status"] == "confirmed" and c["status_text"] == "已确认待打款", c)
check("备注回显且仍无打款时刻", c["remarks"] == "核对无误" and c["paid_time"] == 0, c)
pd = data("PUT", "/api/distribution/cash_withdrawal/%d" % wid, {"status": "paid", "remarks": "已转账"}, atok)
check("confirmed→paid 200，终态时刻落在 updated", pd["status"] == "paid" and pd["paid_time"] > 0, pd)
st, res = fail("PUT", "/api/distribution/cash_withdrawal/%d" % wid, {"status": "confirmed"}, atok)
check("终态再动 → 422", st == 422, (st, res))
i1 = data("GET", "/api/distributor/cash_withdrawal/payinfo/%d" % wid, token=atok)
check("打款后 paid_time>0 且回执写已打款", i1["paid_time"] > 0 and i1["account"]["tip"] == "已打款", i1)
s4 = stats()
check("paid 仍占余额（refused 才还不回去）",
      s4["withdraw_paid"] - s3["withdraw_paid"] == bal and s4["balance"] == 0,
      (s3["withdraw_paid"], s4["withdraw_paid"], s4["balance"]))

# ===== 7. CW-F2 / CW-A1 与权限面 =====
mine = data("GET", "/api/h5app/wxapp/cash_withdrawals?pageSize=50", token=mtok)
check("CW-F2 只出本人的行", any(r["id"] == wid for r in mine["list"]) and
      all(r["user_id"] == mine["list"][0]["user_id"] for r in mine["list"]), mine["total_count"])
paid_only = data("GET", "/api/h5app/wxapp/cash_withdrawals?status=paid&pageSize=50", token=mtok)
check("CW-F2 状态过滤：status=paid 只出 paid", all(r["status"] == "paid" for r in paid_only["list"]),
      [r["status"] for r in paid_only["list"]])
a1 = data("GET", "/api/distribution/cash_withdrawals?pageSize=50", token=atok)
check("CW-A1 后台列表含本案这笔", any(r["id"] == wid for r in a1["list"]), a1["total_count"])
check("CW-A1 行同构（前后共用一份装配）", set(a1["list"][0]) == set(mine["list"][0]),
      (sorted(a1["list"][0]), sorted(mine["list"][0])))
st, _ = req("GET", "/api/distribution/cash_withdrawals?pageSize=50", None, stok)
check("店铺运营岗没有 withdraw.lists → 403", st == 403, st)
st, _ = fail("POST", "/api/distribution/logs/close", {"to_time": now + DAY}, stok)
check("店铺运营岗没有 distribution.close → 403", st == 403, st)
st, _ = fail("PUT", "/api/distribution/cash_withdrawal/%d" % wid, {"status": "paid"}, stok)
check("店铺运营岗没有 withdraw.check → 403", st == 403, st)
sl = data("GET", "/api/distribution/logs?pageSize=100", token=stok)
check("数据权限：DL-A1 只看绑定的店", all(r["distributor_id"] == SHOP for r in sl["list"]), sl["total_count"])
check("越界显式 id → 过滤到 0 行",
      data("GET", "/api/distribution/logs?distributor_id=%d&pageSize=50" % OTHER_SHOP, token=stok)["total_count"] == 0)
st, _ = req("GET", "/api/distribution/count?distributor_id=%d" % OTHER_SHOP, None, stok)
check("资源引用面越界 → 404（不给'这家店存在'的信息）", st == 404, st)
st, _ = req("GET", "/api/distribution/logs?pageSize=50", None, mtok)
check("会员令牌进不了后台面 → 401", st == 401, st)

# ===== 8. 冲销：已结算的行不回改，补负向流水（D52） =====
bn = data("POST", "/api/h5app/wxapp/aftersales", {
    "order_id": str(oid), "item_id": ITEM, "num": 1, "aftersales_type": "ONLY_REFUND",
    "reason": "质量问题", "description": "开箱即损",
}, mtok)["aftersales_bn"]
data("POST", "/api/aftersales/review", {"aftersales_bn": bn, "is_approved": 1}, atok)
data("POST", "/api/aftersales/refundCheck", {"aftersales_bn": bn, "check_refund": 1}, atok)
rows = logs_of(oid)["list"]
check("退款后这一单多出一条流水", len(rows) == 2, [r["rebate"] for r in rows])
pos = [r for r in rows if r["rebate"] > 0][0]
neg = [r for r in rows if r["rebate"] < 0]
check("正向行原样留着（历史行不回改）", pos["rebate"] == REBATE * NUM and pos["is_close"] is True, pos)
check("补一条等额负向行（按行成交额占比 1000×8900/17800=500）",
      len(neg) == 1 and neg[0]["rebate"] == -REBATE, neg)
check("负向行天生已结算、件数记 0",
      neg and neg[0]["is_close"] is True and neg[0]["num"] == 0, neg)
check("负向行沿用原行的店铺与买家归属",
      neg and neg[0]["distributor_id"] == SHOP and neg[0]["user_id"] == pos["user_id"], neg)
s5 = stats()
check("已结算侧立刻回落 500（负向行把余额抵回去）",
      s5["commission_closed"] == s4["commission_closed"] - REBATE, (s4["commission_closed"], s5["commission_closed"]))
check("账自洽：balance = max(0, 已结算 − 占用)",
      s5["balance"] == max(0, s5["commission_closed"] - s5["withdraw_occupying"]), s5)

# ===== 9. 收尾：还原佣金基数与库存 =====
back = patch_item(old_rebate)
check("items.rebate 还原为 %s" % old_rebate, back["rebate"] == old_rebate, back["rebate"])
check("改费率不回溯已落流水（D46）：本案两条行金额仍是 ±%s" % REBATE,
      abs(logs_of(oid)["list"][1]["rebate"]) == REBATE or abs(logs_of(oid)["list"][0]["rebate"]) == REBATE,
      logs_of(oid)["list"])

print("\n[%s] 通过 %d/%d" % (RUN, sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
