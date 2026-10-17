import json
import time
import urllib.error
import urllib.parse
import urllib.request

"""M1-1 售后域实跑冒烟：申请 → 审核 → 回寄 → 确认收货 → 退款确认 → 原路退，
外加门槛用例「部分退款多次申请 + 剩余可退额守恒」与面鉴权（401/403/越权 404）。

一律用差值与守恒读口径判账，不在脚本里连库；库里残留上一轮的售后单不影响结论。
线下打款（AF-A10）的 deferred 分支需要把支付单标成 offline_pay 才能走到，HTTP 侧造不出来，
所以这里只能验它的单号校验、404 与状态守门；「待打款 + 缺 bank_account 400」与登记成功后落
SUCCESS 的完整分支由 AftersalesEndToEndTest 第 17 例（SQL 直改 refund_status）覆盖。
"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
VPW = "viewer" + "123"
VCODE = "8888"
MEMBER = "13800000001"
OTHER = "13800000002"
ITEM = 3                      # 每日坚果礼盒 8900 分 / 模板 1 包邮
UNIT = 8900
RUN = "AF%d" % int(time.time())

ok = []

AF_ROW = {"aftersales_bn", "order_id", "item_id", "item_name", "item_bn", "pic", "num",
          "refund_fee", "refund_point", "aftersales_type", "aftersales_status", "progress",
          "status_msg", "progress_msg", "type_msg", "reason", "description", "refuse_reason", "memo",
          "evidence_pic", "return_type", "contact", "mobile", "freight", "company_id", "user_id",
          "distributor_id", "can_apply", "created", "updated", "sendback_data"}
DETAIL_ROW = {"detail_id", "item_id", "item_name", "pic", "num", "refunded_num", "refund_fee",
              "aftersales_status", "progress"}
ORDER_INFO = {"order_id", "order_status", "pay_status", "total_fee", "pay_fee", "item_fee", "freight_fee"}
ITEM_PRICE = {"price", "num", "applied_num", "can_num", "can_fee"}
REFUND_ROW = {"refund_bn", "aftersales_bn", "order_id", "trade_id", "item_id", "company_id",
              "refund_type", "refund_channel", "refund_status", "status_msg",
              "order_fee", "refund_fee", "refunded_fee", "refund_point", "refunded_point", "return_freight",
              "pay_type", "refund_id", "refund_time", "refund_success_time",
              "payee_name", "bank_name", "bank_account", "bank_branch", "bank_province", "bank_city",
              "memo", "created", "updated"}
REFUND_DETAIL = REFUND_ROW | {"trade_can_refund", "order_refunded_fee", "order_refunded_point", "aftersales"}
APPLY_MIN = {"aftersales_bn", "aftersales_status", "progress", "refund_fee", "refund_point", "num",
             "item_name", "can_apply"}
LIST = {"total_count", "list"}


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


def raw(path, body, content_type):
    r = urllib.request.Request(BASE + path, data=body.encode("utf-8"), method="POST")
    r.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")


def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else " -> " + str(extra)[:400]))


def complete(node, expected, label):
    miss = set(expected) - set(node)
    check(label + " 字段齐全", not miss, "缺 %s / 多 %s" % (sorted(miss), sorted(set(node) - set(expected))))


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


def create(num=1, tok=None):
    return data("POST", "/api/h5app/wxapp/order_new",
                {"cart_type": "fastbuy", "order_type": "normal", "address_id": 1,
                 "items": [{"item_id": ITEM, "num": num}]}, tok or mtok)["order_id"]


def notify(trade_id, fee):
    fields = {"out_trade_no": trade_id, "total_fee": str(fee), "trade_state": "SUCCESS",
              "transaction_id": "WX-" + trade_id[-6:]}
    return raw("/api/notify/wxpay", urllib.parse.urlencode(fields), "application/x-www-form-urlencoded")


def paid(num=1):
    """wxpay 结清：售后只认「已付款」，不依赖实跑账户余额"""
    o = create(num)
    t = data("POST", "/api/h5app/wxapp/payment", {"order_id": o, "pay_type": "wxpay"}, mtok)
    assert notify(t["trade_id"], UNIT * num)[1].find("SUCCESS") >= 0
    assert t["trade_id"]
    return o


def apply_af(order_id, num=1, atype="ONLY_REFUND", fee=None, tok=None, **extra):
    body = {"order_id": order_id, "item_id": ITEM, "num": num, "aftersales_type": atype,
            "reason": "质量问题", "description": "开箱即损 " + RUN}
    if fee is not None:
        body["refund_fee"] = fee
    body.update(extra)
    return data("POST", "/api/h5app/wxapp/aftersales", body, tok or mtok)


def af_detail(bn, tok=None):
    return data("GET", "/api/h5app/wxapp/aftersales/%s" % bn, token=tok or mtok)


def admin_af(bn):
    return data("GET", "/api/aftersales/%s" % bn, token=atok)


def approve(bn, fee=None):
    body = {"aftersales_bn": bn, "is_approved": 1}
    if fee is not None:
        body["refund_fee"] = fee
    return data("POST", "/api/aftersales/review", body, atok)


def refuse(bn, reason="凭证不足"):
    return data("POST", "/api/aftersales/review",
                {"aftersales_bn": bn, "is_approved": 0, "refuse_reason": reason}, atok)


def refund_check(bn, check_refund=1, fee=None, memo=None):
    body = {"aftersales_bn": bn, "check_refund": check_refund}
    if fee is not None:
        body["refund_fee"] = fee
    if memo:
        body["refunds_memo"] = memo
    return data("POST", "/api/aftersales/refundCheck", body, atok)


def refund_detail(rbn):
    return data("GET", "/api/refund/detail/%s" % rbn, token=atok)


mtok = login("/api/h5app/wxapp/new_login",
             {"auth_type": "local", "username": MEMBER, "check_type": "mobile", "vcode": VCODE})
otok = login("/api/h5app/wxapp/new_login",
             {"auth_type": "local", "username": OTHER, "check_type": "mobile", "vcode": VCODE})
atok = login("/api/operator/login", {"username": "admin", "password": PW})
vtok = login("/api/operator/login", {"username": "viewer", "password": VPW})

# ===== 1. AF-F7 原因字典 + 面鉴权基线 =====
reasons = data("GET", "/api/h5app/wxapp/aftersales/reason/list", token=mtok)
check("AF-F7 读 sys_config 售后原因", isinstance(reasons["list"], list) and len(reasons["list"]) >= 1, reasons)
st, res = req("GET", "/api/h5app/wxapp/aftersales/reason/list")
check("AF-F7 无令牌 401", st == 401 and res["message"] == "Unauthenticated.", (st, res))

# ===== 2. AF-F1 申请：未付款拒、成交后落地 =====
o0 = create()
st, res = fail("POST", "/api/h5app/wxapp/aftersales",
               {"order_id": o0, "item_id": ITEM, "num": 1, "aftersales_type": "ONLY_REFUND"}, 422, mtok)
check("未付款订单不得申请售后", st == 422 and "未支付" in res["message"], (st, res))

oA = paid()
row = apply_af(oA)
bnA = row["aftersales_bn"]
complete(row, APPLY_MIN, "AF-F1 申请出参")
check("AF-F1 单号是 16 位 AS 号段串", len(bnA) == 16 and bnA.isdigit(), bnA)
check("AF-F1 申请即待处理（0-0）", row["aftersales_status"] == 0 and row["progress"] == 0, row)
check("AF-F1 缺省退款额按件分摊", row["refund_fee"] == UNIT, row["refund_fee"])
check("AF-F1 出参带类型与状态文案", row["type_msg"] == "仅退款" and row["status_msg"] == "待处理", row)
check("AF-F1 手机号掩码", "*" in row["mobile"], row["mobile"])

d = af_detail(bnA)
complete(d, AF_ROW | {"detail", "refund_info", "order_info"}, "AF-F3 详情")
complete(d["detail"][0], DETAIL_ROW, "AF-F3 detail[]")
complete(d["order_info"], ORDER_INFO, "AF-F3 order_info")
check("AF-F3 审核前不得有退款单（未审先退）", d["refund_info"] is None, d["refund_info"])
check("AF-F3 order_info 与订单实付一致", d["order_info"]["pay_fee"] == UNIT, d["order_info"])

p = data("GET", "/api/h5app/wxapp/aftersales/item/price?order_id=%s&item_id=%d" % (oA, ITEM), token=mtok)
complete(p, ITEM_PRICE, "AF-F6 单件可退额")
check("AF-F6 申请后剩余额度归零", p["applied_num"] == 1 and p["can_num"] == 0 and p["can_fee"] == 0, p)
st, res = fail("POST", "/api/h5app/wxapp/aftersales",
               {"order_id": oA, "item_id": ITEM, "num": 1, "aftersales_type": "EXCHANGING_GOODS"}, 501, mtok)
check("换货类型 M2 前 501", st == 501, (st, res))

ad = admin_af(bnA)
complete(ad, AF_ROW | {"detail", "refund_info", "order_info", "buttons"}, "AF-A2 后台详情")
check("AF-A2 待处理单只给审核与备注按钮", ad["buttons"] == ["review", "remark"], ad["buttons"])

# ===== 3. AF-A3 驳回：缺理由 400、驳回后额度归还、可重新申请 =====
st, res = fail("POST", "/api/aftersales/review", {"aftersales_bn": bnA, "is_approved": 0}, 400, atok)
check("驳回必须填理由 400", st == 400 and "refuse_reason" in res["errors"], (st, res))
rej = refuse(bnA)
check("AF-A3 驳回进终态 3-3", rej["aftersales_status"] == 3 and rej["progress"] == 3, rej)
check("驳回后释放额度",
      data("GET", "/api/h5app/wxapp/aftersales/item/price?order_id=%s&item_id=%d" % (oA, ITEM), token=mtok)["can_num"] == 1)
again = apply_af(oA)
bnA = again["aftersales_bn"]
check("驳回后同一行可重新申请", again["aftersales_status"] == 0 and bnA != rej["aftersales_bn"], again)

# ===== 4. AF-A3 同意仅退款 → 待退款 + READY 退款单（不审不退）=====
ap = approve(bnA)
check("AF-A3 同意仅退款进 1-8", ap["aftersales_status"] == 1 and ap["progress"] == 8, ap)
ri = af_detail(bnA)["refund_info"]
complete(ri, REFUND_ROW, "AF-F3 refund_info")
check("退款单只在待退款阶段生成且为 READY", ri["refund_status"] == "READY", ri)
check("生成退款单不等于退钱", ri["refunded_fee"] == 0 and ri["refund_channel"] == "original", ri)
rbn = ri["refund_bn"]
st, res = fail("POST", "/api/aftersales/review", {"aftersales_bn": bnA, "is_approved": 1}, 422, atok)
check("重复审核 422 且不再生成退款单", st == 422, (st, res))
check("退款单仍只有一张", len([x for x in data("GET", "/api/refund?aftersales_bn=%s&page=1&pageSize=10" % bnA,
                                             token=atok)["list"]]) == 1)
rd = refund_detail(rbn)
complete(rd, REFUND_DETAIL, "AF-A9 退款详情")
check("AF-A9 未确认退款不占支付侧额度", rd["trade_can_refund"] == UNIT and rd["order_refunded_fee"] == 0, rd)

# ===== 5. AF-A4 退款确认：原路退 + 订单终态 =====
st, res = fail("POST", "/api/aftersales/refundCheck",
               {"aftersales_bn": bnA, "check_refund": 1, "refund_fee": 99999}, 422, atok)
check("退款额不得高过申请额", st == 422 and "不能超过申请额" in res["message"], (st, res))
st, res = fail("POST", "/api/aftersales/refundCheck", {"aftersales_bn": bnA, "check_refund": 0}, 400, atok)
check("拒绝退款必须写理由 400", st == 400, (st, res))
st, res = fail("POST", "/api/aftersales/refundCheck", {"aftersales_bn": bnA, "check_refund": 0}, 403, vtok)
check("viewer 无退款确认权限 403", st == 403, (st, res))
rc = refund_check(bnA, memo="质量问题属实 " + RUN)
check("AF-A4 退款即时成功", rc["refund_status"] == "SUCCESS" and rc["settled"] is True, rc)
check("AF-A4 渠道退款号回显", rc["refund_id"].startswith("MOCKREF"), rc)
fin = af_detail(bnA)
check("退款成功即售后结案 2-4", fin["aftersales_status"] == 2 and fin["progress"] == 4, fin)
check("退款单落 refunded_fee 与到账时间",
      fin["refund_info"]["refunded_fee"] == UNIT and fin["refund_info"]["refund_success_time"] > 0, fin["refund_info"])
check("整单退完 → 订单 pay_status REFUND", fin["order_info"]["pay_status"] == "REFUND", fin["order_info"])
rd = refund_detail(rbn)
check("AF-A9 支付侧余量归零", rd["trade_can_refund"] == 0 and rd["order_refunded_fee"] == UNIT, rd)
check("AF-A9 关联售后摘要同步结案", rd["aftersales"]["aftersales_status"] == 2, rd["aftersales"])

# ===== 6. 门槛：部分退款多次申请 + 剩余可退额守恒 =====
oB = paid(num=2)
b1 = apply_af(oB)["aftersales_bn"]
approve(b1)
refund_check(b1)
r1 = af_detail(b1)["refund_info"]["refund_bn"]
check("部分退款第 1 笔成功", refund_detail(r1)["refund_status"] == "SUCCESS", r1)
check("第 1 笔后订单仍可按件继续申请（REFUND_PART 放行）",
      af_detail(b1)["order_info"]["pay_status"] == "REFUND_PART", af_detail(b1)["order_info"])
b2 = apply_af(oB)["aftersales_bn"]
approve(b2)
refund_check(b2)
rd2 = refund_detail(af_detail(b2)["refund_info"]["refund_bn"])
check("两笔部分退款累加正好等于行货款", rd2["order_refunded_fee"] == UNIT * 2, rd2)
check("支付侧余量归零（守恒不变）", rd2["trade_can_refund"] == 0, rd2)
check("件数守恒：2 件全退后 AF-F6 无可退件",
      data("GET", "/api/h5app/wxapp/aftersales/item/price?order_id=%s&item_id=%d" % (oB, ITEM), token=mtok)["can_num"] == 0)
st, res = fail("POST", "/api/h5app/wxapp/aftersales",
               {"order_id": oB, "item_id": ITEM, "num": 1, "aftersales_type": "ONLY_REFUND"}, 422, mtok)
check("额度吃完后第 3 笔申请被拒 422", st == 422, (st, res))

# ===== 7. 退货退款全链路：待回寄 → 回寄 → 确认收货（D11）→ 退款 =====
oC = paid()
bnC = apply_af(oC, atype="REFUND_GOODS")["aftersales_bn"]
c1 = approve(bnC)
check("退货退款同意后进待回寄 1-1", c1["aftersales_status"] == 1 and c1["progress"] == 1, c1)
check("待回寄阶段不生成退款单", af_detail(bnC)["refund_info"] is None, af_detail(bnC)["refund_info"])
st, res = fail("POST", "/api/h5app/wxapp/aftersales/sendback",
               {"aftersales_bn": bnC, "corp_code": "zto", "logi_no": "A1"}, 400, mtok)
check("回寄单号校验（公司码 6..30、单号必填）400", st == 400, (st, res))
data("POST", "/api/h5app/wxapp/aftersales/sendback",
     {"aftersales_bn": bnC, "corp_code": "zhongtong", "logi_no": "ZTO" + RUN}, mtok)
sd = af_detail(bnC)
check("回寄后进 2 且回对象 sendback_data",
      sd["progress"] == 2 and sd["sendback_data"]["logi_no"] == "ZTO" + RUN and sd["sendback_data"]["filled_by"] == "buyer",
      sd["sendback_data"])
check("待回寄阶段仍无退款单", sd["refund_info"] is None, sd["refund_info"])
approve(bnC)
c2 = af_detail(bnC)
check("D11 确认收货 = 对 progress=2 再审核一次，进 1-8",
      c2["aftersales_status"] == 1 and c2["progress"] == 8, c2)
check("确认收货按申请件数入库", c2["detail"][0]["refunded_num"] == 1, c2["detail"][0])
refund_check(bnC)
c3 = af_detail(bnC)
check("退货退款退完结案 2-4", c3["aftersales_status"] == 2 and c3["progress"] == 4, c3)

# ===== 8. AF-F5 撤销 + AF-A5 备注 =====
oD = paid(num=2)
bnD = apply_af(oD, num=2)["aftersales_bn"]
cl = data("POST", "/api/h5app/wxapp/aftersales/close", {"aftersales_bn": bnD, "memo": "不想了 " + RUN}, mtok)
check("AF-F5 撤销进 4-7", cl["aftersales_status"] == 4 and cl["progress"] == 7, cl)
check("撤销归还全部件数",
      data("GET", "/api/h5app/wxapp/aftersales/item/price?order_id=%s&item_id=%d" % (oD, ITEM), token=mtok)["can_num"] == 2)
st, res = fail("POST", "/api/h5app/wxapp/aftersales/close", {"aftersales_bn": bnD}, 422, mtok)
check("已撤销单再撤 422", st == 422, (st, res))
bnE = apply_af(oD)["aftersales_bn"]
rm = data("PUT", "/api/aftersales/remark", {"aftersales_bn": bnE, "memo": "缺货已联系 " + RUN}, atok)
check("AF-A5 备注只回显单号与 memo", set(rm) == {"aftersales_bn", "memo"}, rm)
check("AF-A5 备注只改 memo 不动状态",
      rm["memo"].startswith("缺货已联系") and admin_af(bnE)["aftersales_status"] == 0, rm)
st, res = fail("PUT", "/api/aftersales/remark", {"aftersales_bn": bnE, "memo": "x" * 151}, 400, atok)
check("备注超长 400", st == 400, (st, res))

# ===== 9. AF-A6 代客申请 / AF-A7 代填回寄 =====
oF = paid()
ac = data("POST", "/api/aftersales/apply",
          {"order_id": oF, "item_id": ITEM, "num": 1, "aftersales_type": "ONLY_REFUND", "reason": "质量问题"}, atok)
check("AF-A6 代客申请归属买家且当场进待退款",
      ac["user_id"] == 1 and ac["aftersales_status"] == 1 and ac["progress"] == 8, ac)
oG = paid()
bnG = apply_af(oG, atype="REFUND_GOODS")["aftersales_bn"]
approve(bnG)
data("POST", "/api/aftersales/sendback", {"aftersales_bn": bnG, "corp_code": "shunfeng", "logi_no": "SF" + RUN}, atok)
check("AF-A7 后台代填记 seller",
      admin_af(bnG)["sendback_data"]["filled_by"] == "seller", admin_af(bnG)["sendback_data"])

# ===== 10. AF-A1 / AF-A8 列表筛选 =====
l = data("GET", "/api/aftersales?aftersales_type=ONLY_REFUND&page=1&pageSize=50", token=atok)
complete(l, LIST, "AF-A1 信封")
check("AF-A1 类型筛选生效", all(x["aftersales_type"] == "ONLY_REFUND" for x in l["list"]), l["list"][:1])
check("AF-A1 列表按单号倒序", [x["aftersales_bn"] for x in l["list"]] == sorted([x["aftersales_bn"] for x in l["list"]], reverse=True))
lr = data("GET", "/api/refund?refund_status=READY&page=1&pageSize=50", token=atok)
complete(lr, LIST, "AF-A8 信封")
check("AF-A8 状态筛选生效", all(x["refund_status"] == "READY" for x in lr["list"]), lr["list"][:1])
la = data("GET", "/api/refund?page=1&pageSize=50", token=atok)
check("AF-A8 全量比 READY 多（含成功/拒退/作废）", la["total_count"] > lr["total_count"], (la["total_count"], lr["total_count"]))

# ===== 11. AF-A10 线下打款的入参与状态守门 =====
st, res = fail("POST", "/api/refund/offline",
               {"refund_bn": "abc", "payee_name": "张三", "bank_account": "6222"}, 400, atok)
check("退款单号格式非法 400", st == 400 and "refund_bn" in res["errors"], (st, res))
st, res = fail("POST", "/api/refund/offline",
               {"refund_bn": "9999999999999999", "payee_name": "张三", "bank_account": "6222"}, 404, atok)
check("退款单不存在 404", st == 404, (st, res))
st, res = fail("POST", "/api/refund/offline",
               {"refund_bn": "0", "payee_name": "张三", "bank_account": "1"}, 404, atok)
check("非法单号 404 不 500", st == 404, (st, res))
st, res = fail("POST", "/api/refund/offline", {"refund_bn": r1, "payee_name": "张三"}, 422, atok)
check("状态守门先于参数校验（非待打款一律 422，缺 bank_account 也轮不到 400）",
      st == 422 and "待打款" in res["message"], (st, res))
check("被拒的登记不动退款单",
      refund_detail(r1)["refund_status"] == "SUCCESS" and refund_detail(r1)["refunded_fee"] == UNIT, r1)

# ===== 12. 越权与面隔离 =====
st, res = req("GET", "/api/h5app/wxapp/aftersales/%s" % bnE, token=otok)
check("别人的售后单在会员面按 404 处理", st == 404, (st, res))
st, res = fail("POST", "/api/h5app/wxapp/aftersales/close", {"aftersales_bn": bnE}, 404, otok)
check("越权撤销 404", st == 404, (st, res))
st, res = fail("POST", "/api/h5app/wxapp/aftersales/close", {"aftersales_bn": bnE}, 401, atok)
check("operator 令牌打会员面 401", st == 401, (st, res))
st, res = req("GET", "/api/aftersales/%s" % bnE, token=mtok)
check("会员令牌打后台面 401", st == 401, (st, res))
st, res = req("GET", "/api/aftersales", token=vtok)
check("后台面缺权限 403", st == 403, (st, res))
st, res = req("GET", "/api/h5app/wxapp/aftersales/not-a-number", token=mtok)
check("非数字单号 404 不 500", st == 404, (st, res))

# ===== 13. 收尾：清掉在途单，列表守恒自查 =====
data("POST", "/api/h5app/wxapp/aftersales/close", {"aftersales_bn": bnE, "memo": "冒烟清理 " + RUN}, mtok)
check("收尾撤销后 2 件全部归还（含更早撤销的那笔）",
      data("GET", "/api/h5app/wxapp/aftersales/item/price?order_id=%s&item_id=%d" % (oD, ITEM), token=mtok)["can_num"] == 2)
tr = refund_detail(rbn)
check("已结案退款单仍可查（留痕不删）", tr["refund_bn"] == rbn, tr)
check("现金池守恒：任一退款成功单已退额不超过申请额",
      all(x["refunded_fee"] <= x["refund_fee"] for x in la["list"]), la["list"][:1])

print("\n[%s] 通过 %d/%d" % (RUN, sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
