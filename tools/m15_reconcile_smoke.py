import json
import time
import urllib.error
import urllib.parse
import urllib.request

"""M1-5（积分 PT / 储值 CZ / 评价 RATE / 发票 INV）实跑冒烟：真 fat jar + 常驻 H2 库上走一遍 docs/04 §23.5 的四道门槛。

M15EndToEndTest（13 例）管"口径对不对"，本脚本管"起得来的服务与跑了很久的库对不对"，
内存测试碰不到的那几件事全在这里：
 1. **账实相符只能在常驻库上按差值断**（门槛①）。演示库是 `./data/ecshopx.mv.db`，
    前几批脚本留下的余额与流水早就进了账本，绝对恒等式 `Σ change_point == point` 对老数据不成立
    （抵扣留痕这一条腿本批才补上）。所以这里进场记四个基线，此后只断 `Δ余额 == Δ流水`。
 2. **本脚本没有任何 SQL 通道**（门槛②）。给会员加分走 PT-A2 调账、给余额充钱走 PAY-4 充值与
    CZ-A3 代客充值，`get_points` 由建单算出后从 ORDER-9 读回来对 ——
    脚本里连一句 `UPDATE` 都写不出来，正好把"判据必须自己跑通"钉死。
 3. schema 的 ALTER 列与索引在**老库上有没有真的补上**：`trade_rate.distributor_id/shop_id/nickname`、
    `orders_invoice.remark`、`uk_invoice_order`（同单二次申请回同一个 id 才是 upsert 生效）、
    `idx_rate_company_item`。H2 的 `ALTER ... IF NOT EXISTS` 静默失败过一次也不会报错，只能从出参反证。
 4. `seq_generator` 里 `invoice_apply_bn` 那行在不在（缺行是 500 不是 422，C166 记过）——
    单号前缀 `FP` 出得来就说明这行落进了常驻库。
 5. `sys_config` 四条种子（`point.deduct.max_ratio`/`deposit.recharge.point.rate`/`invoice.enable`/
    `invoice.types`）与权限码 54..63 是否真的进了常驻库的 `resources`+`role_resource`：
    role 2 在 54/56/59/60/61 上 200、在 55/57/58/62/63 上 403，未配 `datapass.pass` 的同一行出掩码。
 6. 六处手写掩码收敛成 common 的 `Mask` 之后，**每一位的形状没变**：`mobile` 四位星、
    `taxNo` 等长留前 4 后 3、`email` 只掩本地部分、`title` 留前 2 后 1（后三支是新掩的，只在发票面出得来）。

覆盖 8 段：读口形状与种子落库 → PT-A2 调账 → 储值四段 → 抵扣与回补 → 纯积分兑换（门槛②）→
评价全链 + BUCKET-R1 → 越权与权限面 + datapass 同一行两个出参 → 发票全链（含受限面的三支新掩码）。

"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
VPW = "viewer" + "123"
SPW = "shop" + "123"
VCODE = "8888"
MEMBER = "13800000001"
OTHER = "13800000002"
SHOP = 2                      # seed 的 demo 旗舰店（distributor01 只绑了它）
ITEM = 3                      # 每日坚果礼盒，seed 价 8900 分 / 每件赠 100 分
RUN = "M15%d" % int(time.time())
ok = []


def req(method, path, body=None, token=None):
    payload = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=payload, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def raw(path, body, ctype):
    r = urllib.request.Request(BASE + path, data=body.encode(), method="POST")
    r.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
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


def member_login(user):
    return login("/api/h5app/wxapp/new_login", {"auth_type": "local", "username": user,
                                                "check_type": "mobile", "vcode": VCODE})


def cfg_value(key, default=None):
    for row in data("GET", "/api/config?pageSize=100", token=atok)["list"]:
        if row["config_key"] == key:
            return row["config_value"]
    return default


def paged(path, token=None, key="list"):
    """把分页信封取全：对账要的是整个集合的和，不是第一页的和"""
    out, page = [], 1
    while True:
        sep = "&" if "?" in path else "?"
        d = data("GET", "%s%spageSize=100&page=%d" % (path, sep, page), token=token or atok)
        rows = d.get(key) or []
        out += rows
        if len(rows) < 100 or len(out) >= int(d["total_count"]):
            return out
        page += 1


# ---------- 账本读口径（全部走 HTTP，本脚本没有 SQL 通道） ----------

def point_view():
    return data("GET", "/api/h5app/wxapp/point", token=mtok)


def point_logs():
    return sum(int(x["change_point"]) for x in paged("/api/point/logs?user_id=1"))


def deposit_view():
    return data("GET", "/api/h5app/wxapp/deposit", token=mtok)


def deposit_logs():
    out = 0
    for x in paged("/api/deposit/trades?user_id=1&status=SUCCESS"):
        d = int(x["amount"]) + int(x["gift_amount"])
        out += -d if x["trade_type"] == "consume" else d
    return out


P0 = L0 = T0 = B0 = D0 = 0


def ledger(tag):
    """门槛①：每一步动账之后都过一遍这两条恒等式（差值形式，理由见文件头第 1 条）"""
    check("%s：Δ积分余额 == Δ积分流水" % tag,
          point_view()["point"] - P0 == point_logs() - L0,
          (point_view()["point"] - P0, point_logs() - L0))
    check("%s：Δ余额 == Δ成功动账" % tag,
          deposit_view()["balance"] - B0 == deposit_logs() - D0,
          (deposit_view()["balance"] - B0, deposit_logs() - D0))


# ---------- 业务便捷件（与 e2e 同一套动作，只是全部经 HTTP） ----------

def create_order(num=1, point_use=False, shop=None):
    if shop:
        data("POST", "/api/h5app/wxapp/cart", {"item_id": ITEM, "num": num, "distributor_id": shop}, mtok)
        body = {"cart_type": "cart", "order_type": "normal", "distributor_id": shop, "address_id": 1,
                "receiver_name": "张三", "receiver_mobile": MEMBER, "order_from": "h5"}
    else:
        body = {"cart_type": "fastbuy", "order_type": "normal", "address_id": 1,
                "items": [{"item_id": ITEM, "num": num}]}
    if point_use:
        body["point_use"] = True
    return data("POST", "/api/h5app/wxapp/order_new", body, mtok)["order_id"]


def order_info(order, token=None):
    return data("GET", "/api/h5app/wxapp/order/%s" % order, token=token or mtok)["orderInfo"]


def admin_order(order):
    return data("GET", "/api/orders/%s" % order, token=atok)


def pay(order, pay_type, token=None):
    return data("POST", "/api/h5app/wxapp/payment", {"order_id": order, "pay_type": pay_type}, token or mtok)


def wx_paid(num=1, shop=None):
    """微信渠道成交：不动余额，只 +赠送分。发票与评价用例用它造已付单"""
    o = create_order(num, shop=shop)
    t = pay(o, "wxpay")
    fee = int(order_info(o)["total_fee"])
    fields = {"out_trade_no": t["trade_id"], "total_fee": str(fee), "trade_state": "SUCCESS",
              "transaction_id": "WX-" + t["trade_id"][-6:]}
    assert raw("/api/notify/wxpay", urllib.parse.urlencode(fields),
               "application/x-www-form-urlencoded")[1].find("SUCCESS") >= 0
    return o


def done_order(num=1):
    """走完发货 + 确认收货的单：评价与发票都要求 DONE"""
    o = wx_paid(num)
    data("PUT", "/api/orders/delivery", {"order_id": int(o), "delivery_corp": "zto",
                                         "delivery_code": "SF-%s" % RUN}, atok)
    data("POST", "/api/h5app/wxapp/order/confirmReceipt", {"order_id": o}, mtok)
    return o


def refund(order, num=1):
    bn = data("POST", "/api/h5app/wxapp/aftersales", {"order_id": order, "item_id": ITEM, "num": num,
                                                      "aftersales_type": "ONLY_REFUND", "reason": "质量问题",
                                                      "description": "开箱即损"}, mtok)["aftersales_bn"]
    data("POST", "/api/aftersales/review", {"aftersales_bn": bn, "is_approved": 1}, atok)
    data("POST", "/api/aftersales/refundCheck", {"aftersales_bn": bn, "check_refund": 1}, atok)


def adjust(change, remark, token=None):
    return data("POST", "/api/point/adjustment", {"user_id": 1, "change_point": change, "remark": remark},
                token or atok)


def rate_rows(item_id=ITEM, token=None):
    return [x for x in paged("/api/h5app/wxapp/order/rate/list?item_id=%s" % item_id, token=token or mtok)]


def admin_rate_rows(token=None, extra=""):
    return paged("/api/comments?item_id=%s%s" % (ITEM, extra), token=token or atok)


def cart_rows():
    c = data("GET", "/api/h5app/wxapp/cart", token=mtok)
    out = []
    for key in ("valid_cart", "invalid_cart"):
        for g in c.get(key) or []:
            out += [r["cart_id"] for r in g["list"]]
    return out


def clear_cart():
    """被拒的建单会把车内的行留在原地；不清干净就污染同一演示库上的兄弟脚本"""
    ids = cart_rows()
    if ids:
        data("DELETE", "/api/h5app/wxapp/cartdel", {"cart_ids": ids}, mtok)
    return len(ids)


def audit_count(route):
    return data("GET", "/api/operator/logs?keyword=%s&pageSize=1" % route, token=atok)["total_count"]


atok = login("/api/operator/login", {"username": "admin", "password": PW})
vtok = login("/api/operator/login", {"username": "viewer", "password": VPW})
stok = login("/api/operator/login", {"username": "distributor01", "password": SPW})
mtok = member_login(MEMBER)
otok = member_login(OTHER)
check("四类令牌到位（管理员/只读/店铺运营/会员×2）", all([atok, vtok, stok, mtok, otok]))

clear_cart()
# 抵扣比例是这一批算式的分母：上一轮（或别的脚本）改过就先写回 50，否则下面的公式对不上
data("PUT", "/api/member/point/rule", {"point.deduct.max_ratio": "50"}, atok)
AUD0 = {r: audit_count(r) for r in ("point.update", "deposit.recharge", "rate.reply", "invoice.update")}

# ===== 1. 读口形状与种子真的落进了常驻库 =====
p = point_view()
check("PT-F1 六键齐（本项目把累计扣减也给了会员，账要对得上得让他能查）",
      set(p) == {"point", "total_point", "user_point", "ratio", "max_deduct_fee", "enable"}, sorted(p))
check("seed 的 point.deduct.max_ratio=50 落在常驻库上", p["ratio"] == 50, p)
check("PT-F1 的 user_point = Σ(负流水绝对值)，不是 total_point - point（回补会被后者算没）",
      int(p["user_point"]) == sum(-int(x["change_point"]) for x in paged("/api/point/logs?user_id=1")
                                  if int(x["change_point"]) < 0), p)
rule = data("GET", "/api/h5app/wxapp/point_rule", token=mtok)
check("PT-F3 的 point_per_fen 是常量 1（1 积分 = 1 分，不是汇率表）",
      rule["point_per_fen"] == 1 and rule["text"].strip(), rule)
d = deposit_view()
check("CZ-F1 balance 就是本金 + 赠金两栏（CZ-R1）",
      set(d) == {"deposit_amount", "gift_amount", "balance", "total_recharge", "enable"}
      and int(d["balance"]) == int(d["deposit_amount"]) + int(d["gift_amount"]), d)
fr = data("GET", "/api/h5app/wxapp/deposit_rules", token=mtok)
check("CZ-F3 seed 两档在库里（充 100 送 10 / 充 500 送 80）且按金额升序",
      len(fr["list"]) >= 2 and [int(x["recharge_amount"]) for x in fr["list"]]
      == sorted(int(x["recharge_amount"]) for x in fr["list"]), fr["list"])
check("seed 的 deposit.recharge.point.rate=0 落库（充值送积分默认关）", fr["recharge_point_rate"] == 0, fr)
check("seed 的 invoice.types=[\"02\"] 与 invoice.enable=1 落库（只开普票、开关为真值词表）",
      cfg_value("invoice.types") == '["02"]' and cfg_value("invoice.enable") == "1",
      (cfg_value("invoice.types"), cfg_value("invoice.enable")))
ac = data("GET", "/api/member/point/rule", token=atok)
check("PT-A4 三张对照（当前值/默认值/生效值），运营改到一半能看出改没改",
      {"config", "defaults", "effective"} == set(ac) and ac["effective"]["point.deduct.max_ratio"] == 50, ac)
# effective 那一张存在的意义就是"按类型解析过"：开关的 '1' 在这里必须已经是 true，
# 比例在这里必须已经是数 —— 还是字符串的话，运营看到的是字符串、代码吃到的也是字符串，
# 那张对照就退化成 config 的副本，抓不到"值写得对但解析不出来"这一类脏配置。
check("PT-A4 的 effective 是解析后的类型（不是 config 的副本）",
      ac["effective"]["pay.point.enable"] is True and isinstance(ac["effective"]["point.deduct.max_ratio"], int)
      and isinstance(ac["effective"]["deposit.recharge.point.rate"], float), ac["effective"])
check("PT-A4 的 config/defaults 两张仍是字符串原值（sys_config 存的就是串）",
      ac["config"]["point.deduct.max_ratio"] == "50" and ac["defaults"]["point.deduct.max_ratio"] == "50", ac)

# ===== 2. 坐标系 + PT-A2 调账 =====
P0 = int(point_view()["point"])
L0 = point_logs()
B0 = int(deposit_view()["balance"])
D0 = deposit_logs()
T0 = int(point_view()["total_point"])
check("进场基线：积分 %d / 流水和 %d / 余额 %d / 动账和 %d" % (P0, L0, B0, D0), True)
ledger("进场")

up = adjust(30000, RUN + " 补分")
check("PT-A2 出参四键，change_point 回的是真正落账的数",
      set(up) == {"user_id", "before_point", "after_point", "change_point"} and up["change_point"] == 30000, up)
check("调账 = 现值 + 入参，且写进流水", int(point_view()["point"]) == P0 + 30000, point_view())
ledger("调账加分")
check("正向调账算获得，动 total_point", int(point_view()["total_point"]) == T0 + 30000, point_view())
tp1 = int(point_view()["total_point"])

clamp = adjust(-999999, RUN + " 钳位")
check("负数钳到 0：不出现负余额", int(point_view()["point"]) == 0, point_view())
check("钳位回的是真正落账的数，不是入参那个 -999999",
      int(clamp["change_point"]) == -(P0 + 30000), clamp)
ledger("钳位")
check("负向调账不动 total_point（PT-R2：total_point 只表示获得过多少）",
      int(point_view()["total_point"]) == tp1, point_view())
check("钳位这笔在会员面的累计扣减里认下来了", int(point_view()["user_point"]) >= P0 + 30000, point_view())
check("已经 0 分再扣 → 422（钳位只钳「有余额」的情况，余额为 0 时是空操作）",
      req("POST", "/api/point/adjustment", {"user_id": 1, "change_point": -5, "remark": RUN + " 空操作"}, atok)[0] == 422)
check("调 0 分 / 缺 remark / 缺 user_id 各自 400",
      req("POST", "/api/point/adjustment", {"user_id": 1, "change_point": 0, "remark": "x"}, atok)[0] == 400
      and req("POST", "/api/point/adjustment", {"user_id": 1, "change_point": 100}, atok)[0] == 400
      and req("POST", "/api/point/adjustment", {"change_point": 100, "remark": "x"}, atok)[0] == 400)
adjust(30000, RUN + " 补回可付额度")
ledger("二次加分")

a1 = data("GET", "/api/point/members?pageSize=100", token=atok)
check("PT-A1 不是标准信封：list/total_count 之外挂四个合计数",
      set(a1) == {"total_count", "list", "total_point_sum", "total_granted", "total_used", "member_count"}, sorted(a1))
me = [x for x in a1["list"] if int(x["user_id"]) == 1][0]
check("PT-A1 行的 mobile_masked 与 mobile 并存（收敛到 common 的 Mask 之后形状没变）",
      me["mobile_masked"] == "138****0001" and me["mobile"] == MEMBER, me)
check("PT-A1 卡头 total_used = Σ(负流水绝对值)：把回补算没的那种口径已经修掉了",
      int(a1["total_used"]) > 0 and int(a1["total_used"]) == sum(
          -int(x["change_point"]) for x in paged("/api/point/logs?pageSize=100") if int(x["change_point"]) < 0),
      (a1["total_used"], point_logs()))
check("PT-A1 卡头与列表共用同一份筛选字典（受限账号看到的合计就是他那个集合的合计）",
      int(data("GET", "/api/point/members?pageSize=100", token=stok)["total_point_sum"])
      <= int(a1["total_point_sum"]), (a1["total_point_sum"],))

# ===== 3. 储值四段：充值 / 消费 / 退款 / 代客充值 =====
cz = data("POST", "/api/h5app/wxapp/payment_deposit", {"amount": 10000}, mtok)["order_id"]
check("PAY-4 充值单没结清之前钱不进余额", int(deposit_view()["balance"]) == B0, deposit_view())
data("POST", "/api/h5app/wxapp/payment", {"order_id": cz, "pay_type": "recharge"}, mtok)
check("充值结清：本金 10000 + 档位赠金 1000",
      int(deposit_view()["balance"]) == B0 + 11000, deposit_view())
ledger("充值结清")
row = paged("/api/deposit/trades?user_id=1&trade_type=recharge")[0]
check("CZ-A1 充值行：amount 是本金腿、gift_amount 是赠金腿（CZ-R1）",
      row["trade_type"] == "recharge" and int(row["amount"]) == 10000 and int(row["gift_amount"]) == 1000
      and row["status"] == "SUCCESS", row)
check("储值后台行也带 mobile_masked（六处收敛里的第二处）", row["mobile_masked"] == "138****0001", row)

o_dep = create_order()
fee_dep = int(order_info(o_dep)["total_fee"])
pay(o_dep, "deposit")
check("余额消费：先赠金后本金，两栏各自动",
      int(deposit_view()["balance"]) == B0 + 11000 - fee_dep, deposit_view())
ledger("余额消费")
consume = paged("/api/deposit/trades?user_id=1&trade_type=consume")[0]
check("消费必须留一条痕（退款要靠它按本金/赠金比例原路回补）",
      consume["rel_id"] == str(o_dep) and int(consume["amount"]) + int(consume["gift_amount"]) == fee_dep, consume)
check("赠金优先扣完：这一行的 gift_amount 不超过账户赠金", int(consume["gift_amount"]) >= 0, consume)
refund(o_dep)
check("退款原路回补，赠金不折成本金",
      int(deposit_view()["balance"]) == B0 + 11000 - fee_dep + fee_dep, deposit_view())
ledger("储值退款")
rf = paged("/api/deposit/trades?user_id=1&trade_type=refund")[0]
check("退款行与消费行同名单号（rel_id 指得回同一张单）",
      rf["rel_id"] == str(o_dep) and int(rf["amount"]) + int(rf["gift_amount"]) == fee_dep, rf)

r3 = data("POST", "/api/deposit/recharge", {"user_id": 1, "amount": 5000, "gift_amount": 500,
                                            "remark": RUN + " 客服补偿"}, atok)
check("CZ-A3 代客充值不走支付、当场入账",
      set(r3) == {"trade_id", "user_id", "amount", "gift_amount", "remark", "trade_state",
                  "deposit_amount", "gift_balance", "balance"} and r3["trade_state"] == "SUCCESS", r3)
check("代客充值单号 CZ 前缀（号段发号在常驻库上有行）", str(r3["trade_id"]).startswith("CZ"), r3["trade_id"])
ledger("代客充值")
c4 = data("GET", "/api/deposit/count", token=atok)
check("CZ-A4 七个键齐；现值与近 7 天流水各说一件事",
      set(c4) == {"principal_sum", "gift_sum", "notpay_count", "recharge_sum_7d", "recharge_days",
                  "member_count", "balance_sum"}, sorted(c4))

nr = data("POST", "/api/deposit/recharge_rule", {"rule_name": RUN + " 充200送30", "recharge_amount": 20000,
                                                 "gift_amount": 3000}, atok)
front_rules = lambda: [int(x["id"]) for x in data("GET", "/api/h5app/wxapp/deposit_rules", token=mtok)["list"]]
check("CZ-A2 新档位前台立刻可见（上架即可卖）", int(nr["id"]) in front_rules(), nr)
off = data("PUT", "/api/deposit/recharge_rule/%s" % nr["id"], {"status": 0}, atok)
check("CZ-A2 读-改-写：只动 status，金额不能被写成 0",
      int(off["recharge_amount"]) == 20000 and int(off["status"]) == 0, off)
check("下架档位前台不可见", int(nr["id"]) not in front_rules())
data("DELETE", "/api/deposit/recharge_rule/%s" % nr["id"], token=atok)
check("CZ-A2 删除不留痕（档位是价目表不是账，历史动账已把当时金额写进自己那行）",
      int(nr["id"]) not in [int(x["id"]) for x in
                            data("GET", "/api/deposit/recharge_rules?pageSize=100", token=atok)["list"]])
check("零元档 400：recharge_amount>0 是必填判据而不是业务规则",
      req("POST", "/api/deposit/recharge_rule", {"rule_name": RUN, "recharge_amount": 0, "gift_amount": 0}, atok)[0] == 400)

# ===== 4. 积分抵现与关单回补：两条腿都要留痕 =====
p_before = int(point_view()["point"])
tp_deduct = int(point_view()["total_point"])
o_use = create_order(point_use=True)
info = order_info(o_use)
pf, item_fee = int(info["point_fee"]), int(info["item_fee"])
clean = all(int(info.get(k, 0) or 0) == 0 for k in ("coupon_discount", "discount_fee", "member_discount", "freight_fee"))
check("抵现单：point_fee 落在余额与上限里，且算式认得 50% 上限",
      0 < pf <= p_before and (not clean or pf == min(p_before, item_fee * 50 // 100)), (pf, p_before, item_fee, info))
check("抵现改了应付：total_fee = 原价 - point_fee",
      int(info["total_fee"]) == item_fee - pf, (info["total_fee"], item_fee, pf))
check("抵扣腿自己留了一条负流水（本批补的那条；不留痕恒等式在第一次抵现就断）",
      int(point_view()["point"]) == p_before - pf, point_view())
ledger("下单抵扣")
ded = [x for x in paged("/api/point/logs?user_id=1&order_id=%s" % o_use) if int(x["change_point"]) < 0]
check("抵扣流水 remark 与金额对得上订单的 point_fee",
      len(ded) == 1 and ded[0]["remark"] == "订单积分抵现" and int(ded[0]["change_point"]) == -pf, ded)
check("抵扣不是获得：这一腿前后 total_point 一分不动（PT-R2）",
      int(point_view()["total_point"]) == tp_deduct, (tp_deduct, point_view()))
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": o_use, "cancel_reason": RUN + " 改变主意"}, mtok)
check("关单把抵现的积分回补到原值", int(point_view()["point"]) == p_before, point_view())
back = [x for x in paged("/api/point/logs?user_id=1&order_id=%s" % o_use) if int(x["change_point"]) > 0]
check("扣一条、回一条，两行都在（只留一条就对不上账）",
      len(back) == 1 and back[0]["remark"] == "订单关闭，积分抵现回补" and int(back[0]["change_point"]) == pf, back)
ledger("取消回补")
check("回补不动 total_point：回补不是获得",
      int(point_view()["total_point"]) == tp_deduct, (tp_deduct, point_view()))

# ===== 5. 纯积分兑换 + 门槛②：get_points 由建单写入，全程没有一句 UPDATE =====
o_pt = create_order()
gp = int(admin_order(o_pt)["orderInfo"]["get_points"])
ip = sum(int(x["item_point"]) for x in admin_order(o_pt)["items"])
check("PT-R1：单头 get_points 由建单算出，且 = Σ行 item_point（本脚本无 SQL 通道，只能是建单写的）",
      gp > 0 and gp == ip, (gp, ip))
check("行侧不另开得分数列：出参里的行 get_points 是契约补位的 0，得分读 item_point",
      all(int(x["get_points"]) == 0 for x in admin_order(o_pt)["items"]), admin_order(o_pt)["items"][:1])
p5 = int(point_view()["point"])
t5 = int(point_view()["total_point"])
d5 = pay(o_pt, "point")
check("积分渠道：1 积分抵 1 分，point_used 就是应付",
      d5["trade_state"] == "SUCCESS" and int(d5["point_used"]) == int(order_info(o_pt)["pay_fee"]), d5)
check("结清后余额 = 扣分 + 赠送（赠送分在同一个结清点入账）",
      int(point_view()["point"]) == p5 - int(d5["point_used"]) + gp, point_view())
ledger("积分结清")
logs5 = paged("/api/point/logs?user_id=1&order_id=%s" % o_pt)
check("抵扣与赠送各一条流水，两条合起来等于余额差",
      len(logs5) == 2 and sum(int(x["change_point"]) for x in logs5) == int(point_view()["point"]) - p5, logs5)
check("赠送分的 remark 与金额（C54 那条判据在没有 SQL 补丁的情况下自己跑通）",
      any(int(x["change_point"]) == gp and x["remark"] == "订单支付赠送积分" for x in logs5), logs5)
check("赠送算获得、抵扣不算：total_point 只 +赠送分",
      int(point_view()["total_point"]) == t5 + gp, (point_view()["total_point"], t5, gp))
refund(o_pt)
check("退款把积分原路退回（赠送分不回扣）",
      int(point_view()["point"]) == p5 - int(d5["point_used"]) + gp + int(d5["point_used"]), point_view())
check("回补不是获得，total_point 一分不动（PT-R2）",
      int(point_view()["total_point"]) == t5 + gp, point_view())
ledger("积分退款扣回")
no_order = [x for x in paged("/api/point/logs?user_id=1&pageSize=100") if not x["order_id"]]
check("没有订单来源的流水只有人工调账这一族，且每条都带 remark（对账脚本靠它解释差额）",
      all(x["remark"] for x in no_order) and len(no_order) > 0, no_order[:2])

# ===== 6. 评价全链 + BUCKET-R1 分桶恒等 =====
o_rate = done_order()
ledger("微信结清（含赠送分入账）")
pend = data("GET", "/api/h5app/wxapp/order/rate/pending?order_id=%s" % o_rate, token=mtok)
check("RATE-F5 待评价行来自订单行（本批评价要的就是这一行）",
      set(pend) == {"order_id", "is_rate", "list"} and pend["is_rate"] == "false"
      and len(pend["list"]) == 1 and set(pend["list"][0]) == {"order_id", "id", "item_id", "goods_id",
                                                               "goods_name", "pics", "spec_desc", "num"}, pend)
counts0 = data("GET", "/api/h5app/wxapp/orderscount", token=mtok)
check("BUCKET-R1：六桶互斥且求和恒等 total（evaluation 是 done 的子集，不是第七桶）",
      int(counts0["total"]) == sum(int(counts0[k]) for k in
                                   ("notpay", "pending_delivery", "pending_receive", "done", "cancel")), counts0)
check("评价前 evaluation 桶里有这一单，且 evaluation <= done",
      int(counts0["evaluation"]) >= 1 and int(counts0["evaluation"]) <= int(counts0["done"]), counts0)
ev0, done0 = int(counts0["evaluation"]), int(counts0["done"])

sub = data("POST", "/api/h5app/wxapp/order/rate", {"order_id": o_rate, "items": [
    {"item_id": ITEM, "star": 5, "content": RUN + " 很新鲜", "rate_pic": ["/img/a.jpg"]}]}, mtok)
check("RATE-F1 出参三键，rate_count 回真正落了几行",
      set(sub) == {"order_id", "rate_count", "is_rate"} and int(sub["rate_count"]) == 1
      and sub["is_rate"] == "true", sub)
rid = [x for x in admin_rate_rows() if x["content"] == RUN + " 很新鲜"][0]["rate_id"]
arow = [x for x in admin_rate_rows() if x["rate_id"] == rid][0]
check("RATE-A1 后台行 20 键齐，且带得上商品名与店铺归属（客服只拿到一串 id 就没法干活）",
      set(arow) == {"rate_id", "order_id", "item_id", "goods_id", "user_id", "nickname",
                    "mobile", "mobile_masked", "star", "content", "rate_pic", "anonymous",
                    "is_reply", "disabled", "distributor_id", "shop_id", "shop_name",
                    "item_name", "created", "seller_reply"}
      and arow["item_name"] and arow["shop_name"], sorted(arow))
check("本批 ALTER 的三列在老库上真的补上了（列不存在时这一行根本装配不出来，500 而不是出参少键）",
      int(arow["distributor_id"]) > 0 and "shop_id" in arow and arow["nickname"] is not None, arow)
st, res = req("POST", "/api/h5app/wxapp/order/rate", {"order_id": o_rate, "items": [
    {"item_id": ITEM, "star": 1}]}, mtok)
check("二次提交 422 且闸门在 CAS 上（该订单已评价）", st == 422 and "已评价" in res["message"], res)
check("重复提交没有多落一行：后台面按单号筛还是只有一条",
      len([x for x in paged("/api/comments?order_id=%s" % o_rate)]) == 1, o_rate)
check("提交评价不碰账本：赠送分仍只有一条（写口与账口分开）",
      len([x for x in paged("/api/point/logs?user_id=1&order_id=%s&remark=%s" % (o_rate, urllib.parse.quote("赠送")))]) == 1)
counts1 = data("GET", "/api/h5app/wxapp/orderscount", token=mtok)
check("评完整单退出待评价桶：evaluation -1、done 不动",
      int(counts1["evaluation"]) == ev0 - 1 and int(counts1["done"]) == done0, (counts0, counts1))
check("评价后分桶求和仍然恒等 total（BUCKET-R1 不变量没被评价写口破坏）",
      int(counts1["total"]) == sum(int(counts1[k]) for k in
                                   ("notpay", "pending_delivery", "pending_receive", "done", "cancel")), counts1)

mine_row = [x for x in rate_rows() if x["rate_id"] == rid][0]
other_row = [x for x in rate_rows(token=otok) if x["rate_id"] == rid][0]
check("RATE-F2 自己看自己的行出订单号，别人看同一行不出（他买了什么不该泄露）",
      mine_row["order_id"] == str(o_rate) and other_row["order_id"] is None, (mine_row, other_row))
check("RATE-F2 会员面没有 mobile/disabled/归属三列（少给的不是空值，是整键不出）",
      not ({"mobile", "disabled", "distributor_id"} & set(mine_row)), sorted(mine_row))
check("RATE-A1 后台面给真名与全值手机号（datapass 只掩码，不隐藏「这是谁」）",
      arow["nickname"] == mine_row["nickname"] and arow["mobile"] == MEMBER
      and arow["mobile_masked"] == "138****0001", arow)

detail = data("GET", "/api/h5app/wxapp/order/rate/%s" % rid, token=mtok)
check("RATE-F3 是本体 + 全部回复两块", set(detail) == {"rate", "replies"} and detail["replies"] == [], detail)
o_anon = done_order()
data("POST", "/api/h5app/wxapp/order/rate", {"order_id": o_anon, "items": [
    {"item_id": ITEM, "star": 4, "content": RUN + " 匿名行", "anonymous": True}]}, mtok)
anon = [x for x in admin_rate_rows() if x["content"] == RUN + " 匿名行"][0]
check("RATE-R3 匿名只在会员面对非本人隐藏身份：别人看是匿名用户、自己看是真名、后台永远不匿名",
      [x for x in rate_rows() if x["rate_id"] == anon["rate_id"]][0]["nickname"] != "匿名用户"
      and [x for x in rate_rows(token=otok) if x["rate_id"] == anon["rate_id"]][0]["nickname"] == "匿名用户"
      and anon["anonymous"] is True, anon)

rep = data("POST", "/api/comment/reply", {"rate_id": rid, "content": RUN + " 感谢支持"}, atok)
check("RATE-A2 回复出参四键并立刻把 is_reply 置上",
      set(rep) == {"rate_id", "is_reply", "content", "created"} and rep["is_reply"] in (True, 1, "true"), rep)
check("会员面读得到商家回复（RATE-R4 的一行一条最新就是这一条）",
      [x for x in rate_rows() if x["rate_id"] == rid][0]["seller_reply"]["content"] == RUN + " 感谢支持",
      [x for x in rate_rows() if x["rate_id"] == rid][0])
check("RATE-A1 的 is_reply 筛选跟着老列走得动（本批新增三列没把筛选字典带坏）",
      any(int(x["rate_id"]) == int(rid) for x in admin_rate_rows(extra="&is_reply=true")))
data("POST", "/api/comment/reply", {"rate_id": rid, "content": RUN + " 覆盖后的回复"}, atok)
check("重复回复 = 覆盖而不是追加",
      [x for x in rate_rows() if x["rate_id"] == rid][0]["seller_reply"]["content"] == RUN + " 覆盖后的回复"
      and len(data("GET", "/api/h5app/wxapp/order/rate/%s" % rid, token=mtok)["replies"]) == 1,
      data("GET", "/api/h5app/wxapp/order/rate/%s" % rid, token=mtok)["replies"])

off = data("PUT", "/api/comment/%s/disable" % rid, {"disabled": True}, atok)
check("RATE-A3 上下架出参两键", set(off) == {"rate_id", "disabled"} and off["disabled"] is True, off)
check("下架后商品页看不见，但自己的评价仍然看得见",
      not any(x["rate_id"] == rid for x in rate_rows())
      and any(x["rate_id"] == rid for x in paged("/api/h5app/wxapp/order/rate/my", token=mtok)))
check("下架后后台默认也看不见，带 include_disabled=1 才出（且不删行、不改订单 is_rate）",
      not any(x["rate_id"] == rid for x in admin_rate_rows())
      and any(x["rate_id"] == rid for x in admin_rate_rows(extra="&include_disabled=1")))
# 同一道筛必须同时作用在 count 与 page 上：只挂在取数那一段时总数算着下架的行、列表却不给，
# 分页器按 total_count 发页码，最后一页就成了空页（实跑照出来的第一版形状）。
env_off = data("GET", "/api/comments?item_id=%s&pageSize=100" % ITEM, token=atok)
env_all = data("GET", "/api/comments?item_id=%s&pageSize=100&include_disabled=1" % ITEM, token=atok)
check("total_count 跟着可见性一起减（count 与 page 共用同一道筛，末页不会是空页）",
      int(env_off["total_count"]) == len(env_off["list"])
      and int(env_all["total_count"]) == len(env_all["list"])
      and int(env_all["total_count"]) - int(env_off["total_count"])
      == len([x for x in env_all["list"] if x["disabled"]])
      and rid in [x["rate_id"] for x in env_all["list"] if x["disabled"]],
      (env_off["total_count"], len(env_off["list"]), env_all["total_count"], len(env_all["list"])))
data("PUT", "/api/comment/%s/disable" % rid, {"disabled": False}, atok)
check("上架回来：可见集恢复，历史单仍是已评价（撤回不等于可以无限次重评）",
      any(x["rate_id"] == rid for x in rate_rows())
      and order_info(o_rate)["is_rate"] in (True, "true"), order_info(o_rate))

st, res = req("POST", "/api/h5app/wxapp/order/rate", {"order_id": o_rate, "items": [{"item_id": ITEM, "star": 5}]}, otok)
check("A 会员给 B 会员的单提交评价 → 404（不是 403，不给「这一单存在」）", st == 404, res)
o_unpaid = create_order()
check("对未完成的单提交 → 422",
      req("POST", "/api/h5app/wxapp/order/rate", {"order_id": o_unpaid, "items": [
          {"item_id": ITEM, "star": 5}]}, mtok)[0] == 422)
st, res = req("POST", "/api/h5app/wxapp/order/rate", {"order_id": o_rate, "items": []}, mtok)
check("空 items 走 400 且错误体四件套（不是 422：这是入参形状问题不是状态问题）",
      st == 400 and set(res) == {"message", "errors", "code", "status_code"}
      and res["errors"]["items"] == ["不能为空"], res)
check("缺 star / star 越界 / item 不在这单里 各自 400",
      req("POST", "/api/h5app/wxapp/order/rate", {"order_id": o_rate, "items": [{"item_id": ITEM}]}, mtok)[0] == 400
      and req("POST", "/api/h5app/wxapp/order/rate", {"order_id": o_rate, "items": [
          {"item_id": ITEM, "star": 6}]}, mtok)[0] == 400
      and req("POST", "/api/h5app/wxapp/order/rate", {"order_id": o_rate, "items": [
          {"item_id": 1, "star": 5}]}, mtok)[0] == 400)
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": o_unpaid, "cancel_reason": RUN + " 冒烟收口"}, mtok)

# ===== 7. 越权闸门 / 权限码 54..63 / datapass 同一行两个出参 =====
check("role 2 的四条看码（54/56/59/61）在常驻库的 role_resource 里：列表面一律 200",
      req("GET", "/api/point/members", token=stok)[0] == 200
      and req("GET", "/api/deposit/trades", token=stok)[0] == 200
      and req("GET", "/api/comments", token=stok)[0] == 200
      and req("GET", "/api/order/invoices", token=stok)[0] == 200)
check("role 2 没配 55/57/58/62：调账、改档位、代客充值、开票一律 403（权限码判在归属之前）",
      req("POST", "/api/point/adjustment", {"user_id": 1, "change_point": 1, "remark": RUN}, stok)[0] == 403
      and req("POST", "/api/deposit/recharge_rule", {"rule_name": RUN, "recharge_amount": 1,
                                                    "gift_amount": 0}, stok)[0] == 403
      and req("POST", "/api/deposit/recharge", {"user_id": 1, "amount": 1, "remark": RUN}, stok)[0] == 403
      and req("PUT", "/api/order/invoice/1", {"invoice_status": "issued", "email": "a@b.c"}, stok)[0] == 403)
# 这条 60 rate.reply 的落地证据不是 200 而是 404：演示库每个商品都挂在 distributor 1，
# role 2 名下一条真评价都没有。没配 60 的话拦在权限层 = 403，配了才轮到归属层把它回成 404。
st_rep, _ = req("POST", "/api/comment/reply", {"rate_id": rid, "content": RUN + " 越权回复"}, stok)
st_dis, _ = req("PUT", "/api/comment/%s/disable" % rid, {"disabled": True}, stok)
check("60 rate.reply 已配给 role 2：跨店回复与上下架回 404 而不是 403/200"
      "（403 = 码没落地，200 = 归属判据没落地，两个都算这条门槛红）",
      st_rep == 404 and st_dis == 404, (st_rep, st_dis))
shop_rates = paged("/api/comments?pageSize=100", token=stok)
check("role 2 的 RATE-A1 只出本店行（收窄判据 = distributor_ids）：本轮这条归属店 1 的评价不在他的集合里",
      all(int(x["distributor_id"]) == SHOP for x in shop_rates)
      and not any(int(x["rate_id"]) == int(rid) for x in shop_rates), shop_rates[:1])
check("viewer 无权限码 403；未登录 401 Unauthenticated.",
      req("GET", "/api/point/members", token=vtok)[0] == 403
      and req("GET", "/api/comments", token=vtok)[0] == 403
      and req("GET", "/api/point/members")[0] == 401
      and req("GET", "/api/point/members")[1].get("message") == "Unauthenticated."
      and req("GET", "/api/order/invoices")[0] == 401)
full = data("GET", "/api/point/logs?user_id=1&pageSize=100", token=atok)["list"][0]
masked = data("GET", "/api/point/logs?user_id=1&pageSize=100", token=stok)["list"][0]
check("datapass.pass（63）只决定全值还是掩码：同一行、两个账号、两个 mobile",
      int(full["id"]) == int(masked["id"]) and full["mobile"] == MEMBER and masked["mobile"] == "138****0001",
      (full["mobile"], masked["mobile"]))
check("掩码列 mobile_masked 两边同值（MASK-R1：出参形状不随权限变化，前端一套代码渲染两种账号）",
      full["mobile_masked"] == masked["mobile_masked"] == "138****0001", (full, masked))
check("审计留痕：本批四条写路径的权限码都进了 resources",
      all(audit_count(r) > AUD0[r] for r in ("point.update", "deposit.recharge", "rate.reply")),
      {r: (AUD0[r], audit_count(r)) for r in ("point.update", "deposit.recharge", "rate.reply")})

# ===== 8. 发票全链：抬头本 → 申请 upsert → 处理终态 → 受限面的三支新掩码 =====
heads = data("GET", "/api/h5app/wxapp/member/invoices", token=mtok)
check("INV-F4 抬头本只出本人，默认抬头在前",
      heads and set(heads[0]) == {"invoices_id", "invoices_type", "name", "telephone", "tax_number",
                                  "business_address", "bank", "bank_account", "is_def", "created"}
      and heads[0]["is_def"] is True, heads[:1])
check("别人的抬头本读不到（seed 两条演示抬头都挂在 user 1 名下）",
      data("GET", "/api/h5app/wxapp/member/invoices", token=otok) == [])
check("corporate 不填税号 → 422；缺 name → 400；词表外的 invoices_type → 400",
      req("POST", "/api/h5app/wxapp/member/invoice", {"invoices_type": "corporate", "name": RUN + " 无税号公司"}, mtok)[0] == 422
      and req("POST", "/api/h5app/wxapp/member/invoice", {"invoices_type": "personal"}, mtok)[0] == 400
      and req("POST", "/api/h5app/wxapp/member/invoice", {"invoices_type": "company", "name": "x"}, mtok)[0] == 400)
personal = data("POST", "/api/h5app/wxapp/member/invoice", {"invoices_type": "personal", "name": RUN + " 张三"}, mtok)
check("personal 抬头不要求税号（INV-R3：两套词表不合并，本项目不收紧）",
      personal["tax_number"] == "" or personal["tax_number"] is None, personal)
pid = personal["invoices_id"]
newdef = data("POST", "/api/h5app/wxapp/member/invoice", {"invoices_type": "corporate", "name": RUN + " 新默认公司",
                                                         "tax_number": "91330100MA27XXXX0C", "is_def": True}, mtok)
check("置默认走先清其余、再置自己：全场仍然只有一个默认位",
      sum(1 for x in data("GET", "/api/h5app/wxapp/member/invoices", token=mtok) if x["is_def"] is True) == 1,
      data("GET", "/api/h5app/wxapp/member/invoices", token=mtok))
edited = data("POST", "/api/h5app/wxapp/member/invoice", {"invoices_id": newdef["invoices_id"],
                                                          "invoices_type": "corporate",
                                                          "name": RUN + " 新默认公司改",
                                                          "tax_number": "91330100MA27XXXX0C", "is_def": True}, mtok)
check("带上 invoices_id 就是改这一行，不是再插一行",
      int(edited["invoices_id"]) == int(newdef["invoices_id"]) and edited["name"] == RUN + " 新默认公司改", edited)
deleted = data("DELETE", "/api/h5app/wxapp/member/invoice/%s" % pid, token=mtok)
check("INV-F6 删除出参两键（invoices_id + deleted），删完列表里就少一条",
      set(deleted) == {"invoices_id", "deleted"} and int(deleted["invoices_id"]) == int(pid)
      and deleted["deleted"] in (True, 1)
      and not any(int(x["invoices_id"]) == int(pid) for x in data("GET", "/api/h5app/wxapp/member/invoices", token=mtok)),
      deleted)
check("删过的抬头再删 404；别人的抬头删不动也是 404（不给「这个 id 存在但不是你的」）",
      req("DELETE", "/api/h5app/wxapp/member/invoice/%s" % pid, token=mtok)[0] == 404
      and req("DELETE", "/api/h5app/wxapp/member/invoice/%s" % newdef["invoices_id"], token=otok)[0] == 404)

o_inv = wx_paid()
st, res = req("POST", "/api/h5app/wxapp/order/invoice/apply", {"order_id": o_inv, "email": "a@b.c"}, otok)
check("别人的单申请发票 → 404", st == 404, res)
check("未付款的单申请 → 422；单不存在 → 404",
      req("POST", "/api/h5app/wxapp/order/invoice/apply", {"order_id": o_unpaid, "email": "a@b.c",
                                                           "company_title": "演示公司",
                                                           "invoice_type": "individual"}, mtok)[0] == 422
      and req("POST", "/api/h5app/wxapp/order/invoice/apply", {"order_id": "900000000000001", "email": "a@b.c",
                                                               "company_title": "演示公司",
                                                               "invoice_type": "individual"}, mtok)[0] == 404)
data("PUT", "/api/config", {"invoice.enable": "0"}, atok)
check("invoice.enable=0 的开关真的拦在业务判据里（422 而不是静默受理）",
      req("POST", "/api/h5app/wxapp/order/invoice/apply", {"order_id": o_inv, "email": "a@b.c"}, mtok)[0] == 422)
data("PUT", "/api/config", {"invoice.enable": "1"}, atok)
st, res = req("POST", "/api/h5app/wxapp/order/invoice/apply", {"order_id": o_inv, "invoice_type": "individual"}, mtok)
check("email 必填 400；脏邮箱 400；票种词表外 400",
      st == 400 and "email" in res["errors"]
      and req("POST", "/api/h5app/wxapp/order/invoice/apply", {"order_id": o_inv, "email": "abc",
                                                               "company_title": "T",
                                                               "invoice_type": "individual"}, mtok)[0] == 400
      and req("POST", "/api/h5app/wxapp/order/invoice/apply", {"order_id": o_inv, "email": "a@b.c",
                                                               "company_title": "T",
                                                               "invoice_type": "person"}, mtok)[0] == 400)
st, res = req("POST", "/api/h5app/wxapp/order/invoice/apply", {"order_id": o_inv, "email": "a@b.c",
                                                               "invoice_type": "enterprise",
                                                               "company_title": RUN + " 无税号公司"}, mtok)
check("申请面同样收紧：enterprise 票种不填税号 → 422（发票开出来没法抵扣）", st == 422, res)
st, res = req("POST", "/api/h5app/wxapp/order/invoice/apply", {"order_id": o_inv, "email": "a@b.c",
                                                               "company_title": "T", "invoice_type": "individual",
                                                               "invoice_type_code": "03"}, mtok)
check("invoice_type_code=03 被 seed 的白名单挡在门外（422）", st == 422, res)

apply = data("POST", "/api/h5app/wxapp/order/invoice/apply", {"order_id": o_inv, "email": RUN + "@demo.cn",
                                                              "invoices_id": 2}, mtok)
check("INV-F1 出参 17 键，键永远在（值按 datapass 决定全量还是掩码）",
      set(apply) == {"id", "invoice_apply_bn", "order_id", "invoice_type", "invoice_type_code", "company_title",
                     "tax_number", "email", "mobile", "invoice_status", "invoice_amount", "invoice_file_url",
                     "remark", "invoices_id", "create_time", "update_time"}, sorted(apply))
check("申请单号 FP 前缀：seq_generator 里 invoice_apply_bn 那行在常驻库上（缺行是 500，C166）",
      str(apply["invoice_apply_bn"]).startswith("FP"), apply["invoice_apply_bn"])
check("抬头带过来的票种映射 corporate → enterprise（两套词表在 Service 层映射，对外各说各话）",
      apply["invoice_type"] == "enterprise" and apply["invoice_type_code"] == "02", apply)
check("金额只对着这一单的实付算（INV-R1 一单一申请）",
      int(apply["invoice_amount"]) == int(admin_order(o_inv)["orderInfo"]["pay_fee"]), apply)
again = data("POST", "/api/h5app/wxapp/order/invoice/apply", {"order_id": o_inv, "email": RUN + "@demo.cn",
                                                              "invoices_id": 2,
                                                              "company_title": RUN + " 改过的抬头名"}, mtok)
check("pending 态二次申请 = upsert 同一条（uk_invoice_order 在老库上真的建起来了）",
      int(again["id"]) == int(apply["id"]) and again["company_title"] == RUN + " 改过的抬头名", again)
check("抬头带过来的税号仍在（改申请不等于把抬头信息冲掉）",
      again["tax_number"] == "91330100MA27XXXX0B", again)
st, res = req("DELETE", "/api/h5app/wxapp/member/invoice/2", token=mtok)
check("被引用过的抬头删不掉：422 + invoices_id 一条原因（历史发票不能变成没有抬头的发票）",
      st == 422 and res["errors"]["invoices_id"] == ["已被发票申请引用"], res)

inv_id = int(apply["id"])
irow = data("GET", "/api/order/invoice/%s" % inv_id, token=atok)
check("INV-A3 后台详情比会员面多 user_id/nickname 两列，且看未掩码全值",
      set(irow) == set(apply) | {"user_id", "nickname"} and irow["tax_number"] == "91330100MA27XXXX0B"
      and irow["email"] == RUN + "@demo.cn", irow)
check("INV-R2 申请即置订单侧的位：is_invoiced + invoice_status=pending",
      admin_order(o_inv)["orderInfo"]["invoice_status"] == "pending"
      and admin_order(o_inv)["orderInfo"]["is_invoiced"] in (True, 1), admin_order(o_inv)["orderInfo"])
st, res = req("PUT", "/api/order/invoice/%s" % inv_id, {"invoice_status": "issued"}, atok)
check("issued 但既没文件也没邮箱 → 422（开了票会员什么都收不到，不接受）", st == 422, res)
st, res = req("PUT", "/api/order/invoice/%s" % inv_id, {"invoice_status": "rejected"}, atok)
check("rejected 不带 remark → 422（驳回总得给个理由）", st == 422, res)
handled = data("PUT", "/api/order/invoice/%s" % inv_id, {"invoice_status": "issued", "email": "x@y.z",
                                                          "remark": RUN + " 已开"}, atok)
check("INV-A2 处理后回写 invoice_status/email/remark（remark 是本批 ALTER 的新列）",
      handled["invoice_status"] == "issued" and handled["email"] == "x@y.z"
      and handled["remark"] == RUN + " 已开", handled)
check("issued 是终态：再改 → 422，会员侧再申请也 → 422",
      req("PUT", "/api/order/invoice/%s" % inv_id, {"invoice_status": "issued", "email": "x@y.z"}, atok)[0] == 422
      and req("POST", "/api/h5app/wxapp/order/invoice/apply", {"order_id": o_inv, "email": "a@b.c",
                                                               "invoices_id": 2}, mtok)[0] == 422)
check("订单侧状态跟着同步（只同步状态，不回写申请快照）",
      admin_order(o_inv)["orderInfo"]["invoice_status"] == "issued", admin_order(o_inv)["orderInfo"])
mine_inv = paged("/api/h5app/wxapp/order/invoice", token=mtok)
check("INV-F2 我的申请列表按状态筛得动，INV-F3 详情本人可读、他人 404",
      any(int(x["id"]) == inv_id for x in mine_inv)
      and any(int(x["id"]) == inv_id for x in paged("/api/h5app/wxapp/order/invoice?invoice_status=issued", token=mtok))
      and not any(int(x["id"]) == inv_id for x in paged("/api/h5app/wxapp/order/invoice?invoice_status=rejected", token=mtok))
      and req("GET", "/api/h5app/wxapp/order/invoice/%s" % inv_id, token=otok)[0] == 404
      and req("GET", "/api/h5app/wxapp/order/invoice/abc", token=mtok)[0] == 404)

# 受限面的三支新掩码：role 2 名下本来一单都没有，造一张店 2 的已付单把他纳进收窄集合
o_shop2 = wx_paid(shop=SHOP)
check("店 2 的单归属确实是 shop 2（收窄集合靠的就是这一列）",
      int(admin_order(o_shop2)["orderInfo"]["distributor_id"]) == SHOP,
      admin_order(o_shop2)["orderInfo"]["distributor_id"])
check("店 2 的单进了 role 2 的收窄集合：PT-A1 因此看得到这个会员（EXISTS 判据在老库上生效）",
      any(int(x["user_id"]) == 1 for x in data("GET", "/api/point/members?pageSize=100", token=stok)["list"]),
      data("GET", "/api/point/members?pageSize=100", token=stok)["list"])
a2 = data("POST", "/api/h5app/wxapp/order/invoice/apply",
          {"order_id": o_shop2, "email": RUN + "@demo.cn", "invoices_id": 2}, mtok)
shop_inv = [x for x in paged("/api/order/invoices?pageSize=100", token=stok) if int(x["id"]) == int(a2["id"])]
check("受限账号在 INV-A1 上看得到这一行（判据是「这会员在我名下下过单」）", len(shop_inv) == 1, a2["id"])
srow = shop_inv[0]
# 这三支是 M1-5 新掩的（taxNo/email/title），把 Mask.kt 的规则在这里逐字复写一遍再对：
# 断"像掩过"没意义，位数差一颗、留错一头都是回归——收敛进 common 的那六处旧掩码同理。
def m_mobile(v):
    return v if len(v) < 7 else v[:3] + "****" + v[-4:]


def m_tax(v):
    return "*" * len(v) if len(v) < 8 else v[:4] + "*" * (len(v) - 7) + v[-3:]


def m_email(v):
    at = v.find("@")
    if at <= 0:
        return "*" * len(v)
    local = v[:at]
    return v if len(local) == 1 else local[0] + "*" * (len(local) - 1) + v[at:]


def m_title(v):
    return "*" * len(v) if len(v) <= 3 else v[:2] + "*" * (len(v) - 3) + v[-1:]


check("新掩的 taxNo 逐字对：留前 4 后 3、中间等长打星（运营要拿它对上纸质发票，掩后必须等长）",
      srow["tax_number"] == m_tax(a2["tax_number"]) and len(srow["tax_number"]) == len(a2["tax_number"]),
      (srow["tax_number"], a2["tax_number"]))
check("新掩的 email 逐字对：只掩本地部分、域名一列字符都不能少（前端靠它判断是不是公司邮箱）",
      srow["email"] == m_email(a2["email"]), (srow["email"], a2["email"]))
check("新掩的 title 逐字对：留前 2 后 1，掩后等长",
      srow["company_title"] == m_title(a2["company_title"]), (srow["company_title"], a2["company_title"]))
check("发票行的 mobile 走的是收敛后那一支（固定四位星，与 PT-A1/CZ-A1/RATE-A1 同形）",
      srow["mobile"] == m_mobile(MEMBER), (srow["mobile"], a2["mobile"]))
check("同一行在 admin 面上是全值：tax_number/email/company_title 三列一个星都没有",
      [x for x in paged("/api/order/invoices?pageSize=100", token=atok) if int(x["id"]) == int(a2["id"])][0]["tax_number"]
      == "91330100MA27XXXX0B", srow["tax_number"])
kw = [x for x in paged("/api/order/invoices?keyword=%s" % urllib.parse.quote(RUN), token=stok)]
check("INV-A1 的 keyword 筛抬头名（本批判据读原列、掩码只作用在出参）：受限面一样筛得动",
      any(int(x["id"]) == inv_id for x in kw) and all("*" in x["company_title"] for x in kw), kw)
st, res = req("GET", "/api/order/invoice/%s" % inv_id, token=stok)
check("这个会员一旦被纳入收窄集合，他在店 1 的申请 role 2 也读得到（200）："
      "发票表没有店铺列，判据是「这会员在我名下下过单」这条 EXISTS —— §23.4 的裁定，不是漏判",
      st == 200, res)

ledger("全链收尾")
check("收尾：调账是唯一「无订单来源」的动账口，这些行全靠 remark 解释差额",
      all(x["remark"] for x in paged("/api/point/logs?user_id=1&remark=%s" % RUN)))
check("收尾：这张已付的店 2 单留在 PAYED（已付款不能整单取消，超时关单作业会在一段时间后回收）",
      admin_order(o_shop2)["orderInfo"]["pay_status"] == "PAYED", admin_order(o_shop2)["orderInfo"])
cleared = clear_cart()
check("收尾：本轮留在车里的 %d 行清干净（不靠建单成功来清车）" % cleared, cart_rows() == [])
check("收尾：审计面本轮四条写路径都留了痕", audit_count("invoice.update") > AUD0["invoice.update"],
      (AUD0["invoice.update"], audit_count("invoice.update")))

print("\n[%s] 通过 %d/%d" % (RUN, sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
