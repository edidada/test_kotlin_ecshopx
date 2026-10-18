import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

"""M0-7 支付闭环实跑冒烟：充值到账 → 余额支付 → wxpay 模拟 + 异步回调 → 线下凭证 → 全链路发货收货。

服务需以干净库启动（删掉 data/*.db），否则上一轮的 user_deposit / trade 会让「余额只扣一次」这类
相对断言失真。脚本一律用差值而不是绝对值判账，充值档位（10000 送 1000）来自 sql/seed.sql。
"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
VCODE = "8888"
MEMBER = "13800000001"
OTHER = "13800000002"
ITEM = 3                      # 每日坚果礼盒 8900 分 / 模板 1 包邮
UNIT = 8900
RUN = "PAY%d" % int(time.time())
TIMEOUT_ONLY = os.environ.get("ECX_SMOKE_TIMEOUT") == "1"

ok = []


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def raw(path, body, content_type, token=None):
    """渠道回调专用：form / XML 报文，应答也是 XML，不走 data 包络。"""
    r = urllib.request.Request(BASE + path, data=body.encode("utf-8"), method="POST")
    r.add_header("Content-Type", content_type)
    if token:
        r.add_header("Authorization", "Bearer " + token)
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


def fail(method, path, body, code, token=None):
    st, res = req(method, path, body, token)
    return st, res


def store(tok):
    return data("GET", "/api/goods/items/%d" % ITEM, token=tok)["store"]


def create(tok, num=1):
    return data("POST", "/api/h5app/wxapp/order_new",
                {"cart_type": "fastbuy", "order_type": "normal", "address_id": 1,
                 "items": [{"item_id": ITEM, "num": num}]}, tok)["order_id"]


def cfg(oid, tok):
    return data("GET", "/api/h5app/wxapp/payment/config?order_id=%s" % oid, token=tok)


def pay(oid, pay_type, tok):
    return data("POST", "/api/h5app/wxapp/payment", {"order_id": oid, "pay_type": pay_type}, tok)


def query(trade_id, tok):
    return data("POST", "/api/h5app/wxapp/payment/query", {"trade_id": trade_id}, tok)


def recharge(amount, tok):
    return data("POST", "/api/h5app/wxapp/payment_deposit", {"amount": amount}, tok)["order_id"]


def notify(trade_id, fee, tid=None, state="SUCCESS"):
    fields = {"out_trade_no": trade_id, "total_fee": str(fee), "trade_state": state,
              "transaction_id": tid or "WX-" + trade_id[-6:]}
    return raw("/api/notify/wxpay", urllib.parse.urlencode(fields), "application/x-www-form-urlencoded")


mtok = login("/api/h5app/wxapp/new_login",
             {"auth_type": "local", "username": MEMBER, "check_type": "mobile", "vcode": VCODE})
otok = login("/api/h5app/wxapp/new_login",
             {"auth_type": "local", "username": OTHER, "check_type": "mobile", "vcode": VCODE})
atok = login("/api/operator/login", {"username": "admin", "password": PW})

sold = 0                      # 实跑消耗的件数，用于收尾库存核对

# ===== 超时关单专跑：验证 M0-7 新增的「关单必收支付单」走真实 @Scheduled 链路 =====
# 需服务以 sys_config.order.auto_cancel_minutes=0 + 秒级调度启动（详见 docs/05 M0-6 踩坑 #8）
if TIMEOUT_ONLY:
    o = create(mtok)
    info = data("GET", "/api/h5app/wxapp/order/%s" % o, token=mtok)["orderInfo"]
    check("超时模式 建单即到期", info["auto_cancel_time"] - info["create_time"] == 0,
          (info["create_time"], info["auto_cancel_time"]))
    t = pay(o, "wxpay", mtok)
    check("到期单仍可预支付挂单", query(t["trade_id"], mtok)["trade_state"] == "NOTPAY", t["trade_id"])
    now = info["order_status"]
    deadline = time.time() + 30
    while time.time() < deadline and now != "CANCEL":
        now = data("GET", "/api/h5app/wxapp/order/%s" % o, token=mtok)["orderInfo"]["order_status"]
        time.sleep(1)
    check("@Scheduled 超时自动关单", now == "CANCEL", now)
    check("关单同时收掉在途支付单", query(t["trade_id"], mtok)["trade_state"] == "CLOSED",
          query(t["trade_id"], mtok))
    st, body = notify(t["trade_id"], UNIT)
    check("已收单的回调幂等应答且不复活订单",
          "SUCCESS" in body and cfg(o, mtok)["order_status"] == "CANCEL", (st, body))
    print("\n[超时模式 %s] 通过 %d/%d" % (RUN, sum(ok), len(ok)))
    raise SystemExit(0 if all(ok) else 1)

# ===== 1. PAY-1 支付参数与账户基线 =====
s0 = store(atok)                      # 建单前先取基线，建单本身已扣 1 件
# 待付款残单同样按**基线差值**判：演示库是常驻的，m11 等脚本跑完会留下自己的在途单，
# 断言"必须为 0"会把邻居的残留算到本案头上（实测：第二轮回归就是这么红的）
base_notpay = data("GET", "/api/h5app/wxapp/orders?order_status=notpay&page=1&pageSize=50",
                   token=mtok)["total_count"]
o0 = create(mtok)
c = cfg(o0, mtok)
check("PAY-1 出参键位（16 键，多一少一都算破契约）",
      set(c) == {"order_id", "order_type", "title", "total_fee", "pay_fee", "order_status", "pay_status",
                 "trade_state", "trade_id", "pay_types", "deposit", "point", "auto_cancel_time",
                 "receipt_type", "shop_id", "distributor_id"}, sorted(c))
check("PAY-1 未付款实付为 0", c["pay_fee"] == 0 and c["pay_status"] == "NOTPAY", c)
check("PAY-1 应付取订单表", c["total_fee"] == UNIT, c["total_fee"])
check("PAY-1 渠道清单", c["pay_types"] == ["wxpay", "point", "deposit", "offline_pay"], c["pay_types"])
check("PAY-1 待付款单必有超时时刻", c["auto_cancel_time"] > 0, c["auto_cancel_time"])
bal0 = c["deposit"]["balance"]
pt0 = c["point"]["point"]
check("PAY-1 积分不足时 can_pay=false",
      c["point"]["can_pay"] == (pt0 >= UNIT), (pt0, c["point"]))
st, res = req("GET", "/api/h5app/wxapp/payment/config?order_id=999999999999999", token=mtok)
check("PAY-1 订单不存在 404", st == 404, (st, res))
st, res = req("GET", "/api/h5app/wxapp/payment/config?order_id=%s" % o0)
check("PAY-1 无令牌 401", st == 401 and res["message"] == "Unauthenticated.", (st, res))

# ===== 2. PAY-4 充值 + 到账（档位赠金） =====
cz = recharge(10000, mtok)
check("PAY-4 充值单号 CZ + 16 位号段", cz.startswith("CZ") and len(cz) == 18, cz)
r = pay(cz, "recharge", mtok)
check("PAY-2 充值即时结清", r["trade_state"] == "SUCCESS", r)
check("PAY-4 到账 = 本金 + 档位赠金 1000", r["deposit"]["balance"] == bal0 + 11000, r["deposit"])
q = query(cz, mtok)
check("PAY-3 充值单查询", q["trade_state"] == "SUCCESS" and q["pay_fee"] == 10000, q)
check("PAY-3 出参含 transaction_id 键（充值无渠道流水）", "transaction_id" in q, sorted(q))
st, res = fail("POST", "/api/h5app/wxapp/payment", {"order_id": cz, "pay_type": "recharge"}, 422, mtok)
check("重复结清充值单 422", st == 422 and "已结清" in res["message"], (st, res))
check("重复结清不动账", query(cz, mtok)["pay_fee"] == 10000 and
      cfg(o0, mtok)["deposit"]["balance"] == bal0 + 11000)
cz2 = recharge(12345, mtok)
r2 = pay(cz2, "recharge", mtok)
check("非档位金额不送赠金", r2["gift_amount"] == 0 and r2["deposit"]["balance"] == bal0 + 23345, r2)
bal = r2["deposit"]["balance"]

# ===== 3. PAY-2 余额支付：赠金优先、只扣一次 =====
oA = create(mtok)
d = pay(oA, "deposit", mtok)
check("PAY-2 余额支付即时结清", d["trade_state"] == "SUCCESS" and d["pay_fee"] == UNIT, d)
check("PAY-2 结清写订单 pay_status", cfg(oA, mtok)["pay_status"] == "PAYED")
check("PAY-2 结清写订单 pay_fee", cfg(oA, mtok)["pay_fee"] == UNIT, cfg(oA, mtok)["pay_fee"])
check("PAY-2 余额只扣一次", cfg(o0, mtok)["deposit"]["balance"] == bal - UNIT, cfg(o0, mtok)["deposit"])
check("PAY-2 支付单进 SUCCESS", query(d["trade_id"], mtok)["trade_state"] == "SUCCESS")
st, res = fail("POST", "/api/h5app/wxapp/payment", {"order_id": oA, "pay_type": "deposit"}, 422, mtok)
check("已支付订单再付 422", st == 422 and "已支付" in res["message"], (st, res))
check("被拒的重复支付不动账", cfg(o0, mtok)["deposit"]["balance"] == bal - UNIT)
sold += 1

# ===== 4. 余额/积分不足、非法渠道、参数校验 =====
oBig = create(mtok, num=9)          # 80100 分，必然超出实跑余额 → 走不足分支
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": o0, "cancel_reason": "基线单清理"}, mtok)
st, res = fail("POST", "/api/h5app/wxapp/payment", {"order_id": oBig, "pay_type": "deposit"}, 422, mtok)
check("余额不足 422 且错误体四件套",
      st == 422 and "余额不足" in res["message"] and
      set(res) == {"message", "errors", "code", "status_code"}, (st, res))
st, res = fail("POST", "/api/h5app/wxapp/payment", {"order_id": oBig, "pay_type": "point"}, 422, mtok)
check("积分不足 422", st == 422 and "积分不足" in res["message"], (st, res))
st, res = fail("POST", "/api/h5app/wxapp/payment", {"order_id": oBig, "pay_type": "alipaymini"}, 501, mtok)
check("未接入渠道 501", st == 501, (st, res))
st, res = fail("POST", "/api/h5app/wxapp/payment", {"pay_type": "deposit"}, 400, mtok)
check("缺 order_id 400", st == 400, (st, res))
check("失败尝试不改订单状态", cfg(oBig, mtok)["pay_status"] == "NOTPAY", cfg(oBig, mtok)["pay_status"])
st, res = fail("POST", "/api/h5app/wxapp/payment/query", {}, 400, mtok)
check("PAY-3 缺 trade_id 400", st == 400, (st, res))
st, res = fail("POST", "/api/h5app/wxapp/payment/query", {"trade_id": "T9999999999999999"}, 404, mtok)
check("PAY-3 支付单不存在 404", st == 404, (st, res))
st, res = fail("POST", "/api/h5app/wxapp/payment/query", {"trade_id": d["trade_id"]}, 403, otok)
check("PAY-3 越权查询 403", st == 403, (st, res))

# ===== 5. PAY-2 wxpay 预支付 + PAY-5 异步回调幂等 =====
oWx = create(mtok)
w = pay(oWx, "wxpay", mtok)
check("wxpay 出 JSAPI 参数包",
      all(k in w for k in ("appId", "timeStamp", "nonceStr", "package", "signType", "paySign")), sorted(w))
check("JSAPI package 携带 prepay_id", w["package"] == "prepay_id=" + w["trade_id"], w["package"])
check("出参数不等于收到钱", w["trade_state"] == "NOTPAY" and cfg(oWx, mtok)["pay_status"] == "NOTPAY", cfg(oWx, mtok)["pay_status"])
check("预支付不落实付", cfg(oWx, mtok)["pay_fee"] == 0)
st, body = notify(w["trade_id"], 1)
check("金额不符的回调应答 FAIL", "FAIL" in body, body)
check("金额不符不结清", cfg(oWx, mtok)["pay_status"] == "NOTPAY", cfg(oWx, mtok)["pay_status"])
st, body = notify(w["trade_id"], UNIT)
check("正确回调应答 SUCCESS", "SUCCESS" in body, body)
check("回调后订单结清", cfg(oWx, mtok)["pay_status"] == "PAYED" and cfg(oWx, mtok)["pay_fee"] == UNIT)
check("回调后支付单 SUCCESS", query(w["trade_id"], mtok)["trade_state"] == "SUCCESS")
st, body2 = notify(w["trade_id"], UNIT)
check("重复回调幂等应答成功", "SUCCESS" in body2, body2)
check("重复回调不重复入账", cfg(oWx, mtok)["pay_fee"] == UNIT)
logs = data("GET", "/api/orders/%s" % oWx, token=atok)["operate_logs"]
check("后台详情留有支付流水（payed）", any(l["behavior"] == "payed" for l in logs), [l["behavior"] for l in logs])
check("回调留渠道流水号", query(w["trade_id"], mtok)["transaction_id"].startswith("WX-"),
      query(w["trade_id"], mtok))
sold += 1

# ===== 6. PAY-5 XML 报文回调 =====
oXml = create(mtok)
x = pay(oXml, "wxpay", mtok)
xml = ("<xml><return_code><![CDATA[SUCCESS]]></return_code>"
       "<out_trade_no><![CDATA[%s]]></out_trade_no>"
       "<total_fee><![CDATA[%d]]></total_fee>"
       "<transaction_id><![CDATA[WX-XML-%s]]></transaction_id>"
       "<trade_state><![CDATA[SUCCESS]]></trade_state></xml>") % (x["trade_id"], UNIT, RUN)
st, body = raw("/api/notify/wxpay", xml, "text/xml")
check("XML 回调可解析并结清", "SUCCESS" in body and cfg(oXml, mtok)["pay_status"] == "PAYED", (st, body))
check("XML 回调写入渠道流水号", query(x["trade_id"], mtok)["transaction_id"] == "WX-XML-" + RUN,
      query(x["trade_id"], mtok))
st, body = raw("/api/notify/unknown_channel", xml, "application/x-www-form-urlencoded")
check("未知渠道回调仍应答不误结清", st in (200, 404), (st, body))
sold += 1

# ===== 7. 线下转账只落凭证 =====
oOff = create(mtok)
off = pay(oOff, "offline_pay", mtok)
check("线下转账待审核", off["trade_state"] == "NOTPAY" and cfg(oOff, mtok)["pay_status"] == "NOTPAY", (off["trade_state"], cfg(oOff, mtok)["pay_status"]))
check("线下转账不写实付", cfg(oOff, mtok)["pay_fee"] == 0)

# ===== 8. ORDER-3 下单 + 预支付一步结清 =====
o3 = data("POST", "/api/h5app/wxapp/order",
          {"cart_type": "fastbuy", "order_type": "normal", "address_id": 1,
           "items": [{"item_id": ITEM, "num": 1}], "pay_type": "deposit"}, mtok)
check("ORDER-3 建单即结清", o3["trade_state"] == "SUCCESS" and o3["pay_fee"] == UNIT, o3)
check("ORDER-3 订单落实付", cfg(o3["order_id"], mtok)["pay_status"] == "PAYED")
sold += 1

# ===== 9. 取消未付单 → 支付单收口 =====
oC = create(mtok)
cc = pay(oC, "wxpay", mtok)
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": oC, "cancel_reason": "关单收口 " + RUN}, mtok)
check("关单后支付单 CLOSED", query(cc["trade_id"], mtok)["trade_state"] == "CLOSED")
st, body = notify(cc["trade_id"], UNIT)
check("已关单收到钱仍应答成功（款项人工退回）", "SUCCESS" in body, body)
check("关单后回调不得复活订单", cfg(oC, mtok)["order_status"] == "CANCEL")

# ===== 10. 全链路：下单 → 余额支付 → 后台发货 → 确认收货 =====
# 前面几节的实付把充值余额吃得只剩 5545，这里再充一档（50000 送 8000）保证全链路够付
pay(recharge(50000, mtok), "recharge", mtok)
oF = create(mtok)
f = pay(oF, "deposit", mtok)
bal_after_pay = cfg(o0, mtok)["deposit"]["balance"]
st, dl = req("PUT", "/api/orders/delivery",
             {"order_id": oF, "delivery_corp": "zto", "delivery_code": "SF" + RUN}, atok)
check("结清后后台可发货", st == 200, (st, dl))
fin = data("POST", "/api/h5app/wxapp/order/confirmReceipt", {"order_id": oF}, mtok)
check("确认收货进完成", fin["order_status"] == "DONE" or cfg(oF, mtok)["order_status"] == "DONE", fin)
check("发货与收货都不动余额", cfg(o0, mtok)["deposit"]["balance"] == bal_after_pay, (bal_after_pay, cfg(o0, mtok)["deposit"]))
ad = data("GET", "/api/orders/%s" % oF, token=atok)
check("全链路流水含支付与发货",
      {"payed", "delivery"} <= set(l["behavior"] for l in ad["operate_logs"]),
      [l["behavior"] for l in ad["operate_logs"]])
sold += 1

# ===== 11. 收尾：清理在途单，库存回到「基线 - 已售件数」 =====
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": oBig, "cancel_reason": "清理"}, mtok)
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": oOff, "cancel_reason": "清理"}, mtok)
left = data("GET", "/api/h5app/wxapp/orders?order_status=notpay&page=1&pageSize=50", token=mtok)
check("清理后没给会员多留待付款残单（基线 %d）" % base_notpay,
      left["total_count"] == base_notpay, (base_notpay, left["total_count"]))
check("库存 = 基线 - 已结清件数（取消全部回补）", store(atok) == s0 - sold, (store(atok), s0, sold))

print("\n[%s] 通过 %d/%d" % (RUN, sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
