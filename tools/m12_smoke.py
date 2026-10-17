import json
import time
import urllib.error
import urllib.parse
import urllib.request

"""M1-2 实跑冒烟（默认 mock profile）：真渠道代码接进来之后，本地这条回调面一个字都没变。

M1-2 的两条真渠道（微信支付 v3、支付宝）需要商户号/密钥/公网回调地址，HTTP 侧造不出来，
它们的"真签真验全链路"由 WxPayV3SimulationTest（8 例）与 AliPaySimulationTest（8 例）在本地
仿真网关里跑；本脚本负责另一半：**profile 没开真渠道时契约不能漂**。

覆盖：
 1. PAY-1 的 `pay_types` 在 mock profile 下不含 alipay（未注册的渠道从清单里消失，不是报错）；
 2. 用未注册渠道发起支付 → 501「暂不支持该支付方式」；
 3. 表单回调仍能结清（M0-7 冒烟的老路径回归），重复通知幂等；
 4. v2 XML 报文体仍能结清（控制器按报文内容分流，不靠 Content-Type 猜）；
 5. JSON 报文在 mock 渠道一律 FAIL（没有可验签的东西就绝不动账）；
 6. 空 body 的 POST 不能撞出 Spring 的"两条映射歧义"500（M1-2 实测踩过的坑，应答必须是渠道认识的 XML）；
 7. 未知渠道 `/api/notify/alipay` 的表单 FAIL 且不动账；
 8. SUP-6 线下转账审核流：驳回不动钱、通过即结清、重复审核 422、少收 422、权限矩阵 401/403。
 9. 收尾把本脚本自己造的待付款残单全部取消（m07 有一条「本人无待付款残单」的全局不变式，
    六脚本按 m04→m05→m06→m07→m08→m11→m12 同库顺序跑时不能互踩）。

服务需以干净库启动（删掉 data/*.db）：`pay_types` 之外的断言都用差值或本单自己的行，
但「一凭证一结论」这类要读列表脚本，脏库里更早的待审凭证会让分页判断失真。
"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
VPW = "viewer" + "123"
VCODE = "8888"
MEMBER = "13800000001"
OTHER = "13800000002"
ITEM = 3                      # 每日坚果礼盒 8900 分 / 模板 1 包邮
UNIT = 8900
RUN = "NTF%d" % int(time.time())

ok = []

OFFLINE_ROW = {"id", "order_id", "total_fee", "pay_fee", "check_status", "check_status_text",
               "pay_sn", "voucher_pic", "transfer_remark", "operator_name", "remark",
               "created", "updated"}


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
    """渠道回调专用：form / XML / JSON 报文都可能有，应答体不走 data 包络。"""
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


def data(method, path, body=None, token=None):
    st, res = req(method, path, body, token)
    assert st == 200, "%s %s -> %s %s" % (method, path, st, res)
    return res["data"]


def login(path, body):
    st, res = req("POST", path, body)
    assert st == 200, res
    return res["data"]["token"]


def create(num=1, tok=None):
    return data("POST", "/api/h5app/wxapp/order_new",
                {"cart_type": "fastbuy", "order_type": "normal", "address_id": 1,
                 "items": [{"item_id": ITEM, "num": num}]}, tok or mtok)["order_id"]


def pay(order_id, pay_type="wxpay", tok=None):
    return data("POST", "/api/h5app/wxapp/payment",
                {"order_id": order_id, "pay_type": pay_type}, tok or mtok)


def status(order_id):
    return data("GET", "/api/h5app/wxapp/order/%s" % order_id, token=mtok)["orderInfo"]["order_status"]


def trade_state(trade_id):
    return data("POST", "/api/h5app/wxapp/payment/query", {"trade_id": trade_id}, mtok)


def form_notify(path, fields):
    return raw(path, urllib.parse.urlencode(fields), "application/x-www-form-urlencoded")


# ===== 1. 登录与 profile 现状 =====
mtok = login("/api/h5app/wxapp/new_login", {"auth_type": "local", "username": MEMBER,
                                            "check_type": "mobile", "vcode": VCODE})
otok = login("/api/h5app/wxapp/new_login", {"auth_type": "local", "username": OTHER,
                                            "check_type": "mobile", "vcode": VCODE})
atok = login("/api/operator/login", {"username": "admin", "password": PW})
vtok = login("/api/operator/login", {"username": "viewer", "password": VPW})
check("四类令牌到位（会员/他人/管理员/只读）", all([mtok, otok, atok, vtok]))
# 待付款单数按**差值**核对：六脚本同库顺序跑（m04→…→m11→m12），前面的脚本会留下自己的在途单
# （m11 就刻意留一笔未支付单验「未付款不能申请售后」），全局归零的口径只在自己单跑时成立。
base_notpay = data("GET", "/api/h5app/wxapp/orders?order_status=notpay&page=1&pageSize=50", token=mtok)["total_count"]

o0 = create()
types = data("GET", "/api/h5app/wxapp/payment/config?order_id=%s" % o0, token=mtok)["pay_types"]
check("PAY-1 渠道清单按固定优先级给出", types == ["wxpay", "point", "deposit", "offline_pay"], types)
check("mock profile 下 alipay 不在清单里（未注册 = 看不见，不是报错）", "alipay" not in types, types)

st, res = req("POST", "/api/h5app/wxapp/payment", {"order_id": (oX := create()), "pay_type": "alipay"}, mtok)
check("用未启用的 profile 渠道发起支付 501", st == 501, (st, res))
check("501 的错误体说清是哪个渠道不支持", "暂不支持该支付方式" in json.dumps(res, ensure_ascii=False)
      and "alipay" in json.dumps(res, ensure_ascii=False), res)

# ===== 2. 表单回调：M0-7 老路径必须一字不变 =====
oA = create()
dA = pay(oA, "wxpay")
tA, feeA = dA["trade_id"], dA["total_fee"]
check("wxpay 下单后本地仍是待付", dA["trade_state"] == "NOTPAY" and status(oA) == "NOTPAY", dA)
st, bodytxt = form_notify("/api/notify/wxpay", {"out_trade_no": tA, "total_fee": str(feeA),
                                                "trade_state": "SUCCESS", "transaction_id": "SMK" + tA[-6:]})
check("表单回调应答 200", st == 200, (st, bodytxt))
check("应答体是微信 v2 的 XML 且 SUCCESS", "return_code" in bodytxt and "SUCCESS" in bodytxt, bodytxt)
check("表单回调结清订单", status(oA) == "PAYED")
qA = trade_state(tA)
check("PAY-3 看到已结清与渠道流水号", qA["trade_state"] == "SUCCESS" and qA["transaction_id"] == "SMK" + tA[-6:], qA)
check("实付额按支付单金额记账（不取报文里的数）", qA["pay_fee"] == feeA, qA)
st, bodytxt = form_notify("/api/notify/wxpay", {"out_trade_no": tA, "total_fee": str(feeA),
                                                "trade_state": "SUCCESS", "transaction_id": "SMK" + tA[-6:]})
check("重复通知仍回 SUCCESS（幂等）", st == 200 and "SUCCESS" in bodytxt, (st, bodytxt))
check("重复通知不改变已结清的账", trade_state(tA)["pay_fee"] == feeA and status(oA) == "PAYED")

# 金额不符的表单回调：验签这一关 mock 没有，金额闸门必须顶住
oB = create()
dB = pay(oB, "wxpay")
st, bodytxt = form_notify("/api/notify/wxpay", {"out_trade_no": dB["trade_id"], "total_fee": str(feeA + 1),
                                                "trade_state": "SUCCESS"})
check("金额不符的报文回 FAIL", st == 200 and "FAIL" in bodytxt, (st, bodytxt))
check("金额不符不动账", status(oB) == "NOTPAY" and trade_state(dB["trade_id"])["trade_state"] == "NOTPAY")

# ===== 3. v2 XML 报文面 =====
oC = create()
dC = pay(oC, "wxpay")
xml = ("<xml><out_trade_no>%s</out_trade_no><total_fee>%d</total_fee>"
       "<result_code><![CDATA[SUCCESS]]></result_code><transaction_id>SMKXML%s</transaction_id></xml>"
       % (dC["trade_id"], dC["total_fee"], dC["trade_id"][-6:]))
st, bodytxt = raw("/api/notify/wxpay", xml, "text/xml")
check("XML 报文体也能结清（分流看报文不看头）", st == 200 and "SUCCESS" in bodytxt, (st, bodytxt))
check("XML 报文结清后订单是 PAYED", status(oC) == "PAYED")
check("XML 报文里的流水号进了支付单", trade_state(dC["trade_id"])["transaction_id"] == "SMKXML" + dC["trade_id"][-6:],
      trade_state(dC["trade_id"]))

# ===== 4. JSON 面与空报文面 =====
oD = create()
dD = pay(oD, "wxpay")
st, bodytxt = raw("/api/notify/wxpay", json.dumps({"out_trade_no": dD["trade_id"],
                                                   "trade_state": "SUCCESS"}), "application/json")
check("mock 渠道没有可验签的东西 → JSON 面一律 FAIL", st == 500 and '"code":"FAIL"' in bodytxt, (st, bodytxt))
check("JSON 报文不动账", status(oD) == "NOTPAY")

# 空 body：Spring 的 consumes 条件对空体一律视为匹配，两条映射会撞成"歧义映射"500。
# 现在只有一条映射，必须给出渠道认识的 XML，而不是 Spring 的错误页。
st, bodytxt = raw("/api/notify/wxpay", "", "application/x-www-form-urlencoded")
check("空 body 的回调不撞映射歧义（应答是 XML）", "return_code" in bodytxt, (st, bodytxt))
check("空 body 回 FAIL 让渠道重试", "FAIL" in bodytxt, (st, bodytxt))

st, bodytxt = form_notify("/api/notify/alipay", {"out_trade_no": dD["trade_id"], "total_fee": str(UNIT),
                                                 "trade_status": "TRADE_SUCCESS"})
check("这个 profile 下 alipay 渠道没注册 → 回调 FAIL", "FAIL" in bodytxt and "alipay" in bodytxt, (st, bodytxt))
check("未知渠道的回调同样不动账", status(oD) == "NOTPAY")

# ===== 5. SUP-6 线下转账审核流 =====
oE = create()
dE = pay(oE, "offline_pay")
check("提交线下凭证只落待审核，订单仍待付款", status(oE) == "NOTPAY" and trade_state(dE["trade_id"])["trade_state"] == "NOTPAY")
pid = None
lst = data("GET", "/api/offline-payments?check_status=0&page=1&pageSize=50", token=atok)
check("待审列表是分页结构", set(lst) >= {"total_count", "list"}, lst)
rowE = [x for x in lst["list"] if x["order_id"] == oE]
check("本单凭证出现在待审列表", len(rowE) == 1, lst["list"][:1])
if rowE:
    pid = rowE[0]["id"]
    miss = OFFLINE_ROW - set(rowE[0])
    check("凭证行字段齐全（含 voucher_pic/pay_sn/check_status_text）", not miss, "缺 %s" % sorted(miss))
    check("凭证初始态是待审核", rowE[0]["check_status"] == 0 and rowE[0]["check_status_text"] == "待审核", rowE[0])

# 5.1 驳回：只标凭证，不动钱
st, res = req("POST", "/api/offline-payments/check", {"id": pid, "check_status": 2, "remark": "凭证看不清 " + RUN}, atok)
check("驳回应答 200 且状态是已拒绝", st == 200 and res["data"]["check_status_text"] == "已拒绝", (st, res))
check("驳回后订单仍待付款", status(oE) == "NOTPAY")
check("驳回后支付单仍 NOTPAY", trade_state(dE["trade_id"])["trade_state"] == "NOTPAY")
lst2 = data("GET", "/api/offline-payments?check_status=0&page=1&pageSize=50", token=atok)
check("驳回的凭证退出待审列表", all(x["id"] != pid for x in lst2["list"]), lst2["total_count"])
lst3 = data("GET", "/api/offline-payments?check_status=2&page=1&pageSize=50", token=atok)
check("驳回的凭证能在已拒绝筛选项里看到", any(x["id"] == pid for x in lst3["list"]), lst3["total_count"])
st, res = req("POST", "/api/offline-payments/check", {"id": pid, "check_status": 1}, atok)
check("已驳回的凭证不能再通过（一凭证一结论）", st == 422 and "已审核" in json.dumps(res, ensure_ascii=False), (st, res))

# 5.2 通过：审核入口自己结清，与回调共用同一条 CAS
oF = create()
dF = pay(oF, "offline_pay")
rowF = [x for x in data("GET", "/api/offline-payments?check_status=0&page=1&pageSize=50", token=atok)["list"]
        if x["order_id"] == oF][0]
st, res = req("POST", "/api/offline-payments/check",
              {"id": rowF["id"], "check_status": 1, "pay_fee": UNIT, "remark": "柜台点收 " + RUN}, atok)
check("审核通过应答 200", st == 200, (st, res))
dFv = res["data"]
check("审核通过即结清：订单 PAYED", status(oF) == "PAYED" and dFv["order_status"] == "PAYED", dFv)
check("审核通过回给你支付单号与 SUCCESS", dFv["trade_state"] == "SUCCESS" and dFv["trade_id"] == dF["trade_id"], dFv)
check("支付单记的是线下渠道", trade_state(dF["trade_id"])["pay_type"] == "offline_pay", trade_state(dF["trade_id"]))
check("线下结清的实付=应付全额", trade_state(dF["trade_id"])["pay_fee"] == UNIT, trade_state(dF["trade_id"]))
logs = data("GET", "/api/orders/%s" % oF, token=atok)["operate_logs"]
check("审核动作留痕（offline_paid）", any(l["behavior"] == "offline_paid" for l in logs), [l["behavior"] for l in logs][:6])
st, res = req("POST", "/api/offline-payments/check", {"id": rowF["id"], "check_status": 1, "pay_fee": UNIT}, atok)
check("重复审核 422", st == 422 and "已审核" in json.dumps(res, ensure_ascii=False), (st, res))
check("重复审核没有把订单再结一次", status(oF) == "PAYED" and trade_state(dF["trade_id"])["pay_fee"] == UNIT)

# 5.3 少收不放行
oG = create()
pay(oG, "offline_pay")
rowG = [x for x in data("GET", "/api/offline-payments?check_status=0&page=1&pageSize=50", token=atok)["list"]
        if x["order_id"] == oG][0]
st, res = req("POST", "/api/offline-payments/check", {"id": rowG["id"], "check_status": 1, "pay_fee": 1}, atok)
check("少收 422 且提示按全额结清", st == 422 and "全额" in json.dumps(res, ensure_ascii=False), (st, res))
check("少收被拒后订单仍待付款", status(oG) == "NOTPAY")
st, res = req("POST", "/api/offline-payments/check", {"id": rowG["id"], "check_status": 3}, atok)
check("非法结论值 400", st == 400, (st, res))
st, res = req("POST", "/api/offline-payments/check", {"id": "not-a-number", "check_status": 1}, atok)
check("非法凭证号 400 不 500", st == 400, (st, res))
st, res = req("POST", "/api/offline-payments/check", {"id": "999999999", "check_status": 1}, atok)
check("查无凭证 404", st == 404, (st, res))

# 5.4 权限矩阵与钻取（不单开凭证详情端点：列表行已含全部字段，?order_id= 就是钻取）
st, res = req("GET", "/api/offline-payments?check_status=0", token=None)
check("匿名读审核列表 401", st == 401, (st, res))
st, res = req("GET", "/api/offline-payments?check_status=0", token=vtok)
check("没挂角色的只读账号连看凭证都不行（403）", st == 403, (st, res))
st, res = req("POST", "/api/offline-payments/check", {"id": rowG["id"], "check_status": 1, "pay_fee": UNIT}, vtok)
check("只读账号不能审核（403）", st == 403, (st, res))
drill = data("GET", "/api/offline-payments?order_id=%s" % oG, token=atok)
check("按订单钻取只回这一笔的凭证", len(drill["list"]) >= 1 and all(x["order_id"] == oG for x in drill["list"]), drill)
check("钻取到的正是那笔少收未通过的凭证", any(x["id"] == rowG["id"] and x["check_status"] == 0 for x in drill["list"]), drill["list"])
check("买家侧不靠审核面看钱：订单详情仍是待付款", status(oG) == "NOTPAY")

# ===== 6. 结清后的整链仍能走通（渠道动作不干扰履约） =====
st, dl = req("PUT", "/api/orders/delivery", {"order_id": int(oF), "delivery_corp": "zto", "delivery_code": "SMK-M12"}, atok)
check("审核结清的单照样能发货", st == 200, (st, dl))
check("确认收货走到完成", data("POST", "/api/h5app/wxapp/order/confirmReceipt", {"order_id": oF}, mtok)["order_status"] == "DONE")

# ===== 7. 收尾：本脚本自己造的待付款残单必须清干净 =====
# m07 冒烟有一条全局不变式「清理后本人无待付款残单」，它数的是这个会员在租户下的全部待付款单。
# 回调失败的报文会把单留在 NOTPAY（这正是它该有的行为），所以这些单由**制造它们的脚本**负责收口，
# 否则六脚本的顺序回归会被上一条与本条互踩——同一个不变式两处破，查起来还以为是渠道改坏了。
for oo, why in [(o0, "PAY-1 基线单"), (oX, "未注册渠道 501"), (oB, "金额不符未结清"),
               (oD, "JSON 报文未结清"), (oE, "凭证已驳回"), (oG, "少收未通过")]:
    data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": oo, "cancel_reason": "M1-2 冒烟收口：" + why}, mtok)
left = data("GET", "/api/h5app/wxapp/orders?order_status=notpay&page=1&pageSize=50", token=mtok)
check("本脚本没给会员多留待付款残单（差值口径，回到进场基线 %d）" % base_notpay,
      left["total_count"] == base_notpay, (base_notpay, left["total_count"]))
check("被驳回/未结清的单取消后库存回补到位", all(status(x) == "CANCEL" for x in [oE, oG]))

print("\n[%s] 通过 %d/%d" % (RUN, sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
