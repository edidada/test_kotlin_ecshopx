import json
import os
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
VCODE = "8888"
MEMBER = "13800000001"        # seed 普通会员（grade 折扣 100 → 不打折）
OTHER = "13800000002"         # 金卡会员，越权用例
ITEM = 3                      # 每日坚果礼盒 8900 分 / distributor_id=1 / 模板 1 包邮
RUN = "SMK%d" % int(time.time())
TIMEOUT_ONLY = os.environ.get("ECX_SMOKE_TIMEOUT") == "1"

ok = []


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else " -> " + str(extra)[:500]))


def data(method, path, body=None, token=None):
    st, res = req(method, path, body, token)
    assert st == 200, "%s %s -> %s %s" % (method, path, st, res)
    return res["data"]


def login(path, body):
    st, res = req("POST", path, body)
    assert st == 200, res
    return res["data"]["token"]


def store(tok):
    return data("GET", "/api/goods/items/%d" % ITEM, token=tok)["store"]


def create(tok, num, extra=None):
    body = {"cart_type": "fastbuy", "order_type": "normal", "address_id": 1,
            "items": [{"item_id": ITEM, "num": num}]}
    body.update(extra or {})
    return data("POST", "/api/h5app/wxapp/order_new", body, tok)


def detail(tok, oid):
    return data("GET", "/api/h5app/wxapp/order/%s" % oid, token=tok)


mtok = login("/api/h5app/wxapp/new_login",
             {"auth_type": "local", "username": MEMBER, "check_type": "mobile", "vcode": VCODE})
atok = login("/api/operator/login", {"username": "admin", "password": PW})

# ===== 超时关单专跑：需服务以 sys_config.order.auto_cancel_minutes=0 + 秒级调度启动 =====
if TIMEOUT_ONLY:
    s0 = store(atok)
    o = create(mtok, 1)
    info = detail(mtok, o["order_id"])["orderInfo"]
    check("超时模式 建单即到期", info["auto_cancel_time"] - info["create_time"] == 0,
          (info["create_time"], info["auto_cancel_time"]))
    status_now = info["order_status"]
    deadline = time.time() + 30
    while time.time() < deadline and status_now != "CANCEL":
        status_now = detail(mtok, o["order_id"])["orderInfo"]["order_status"]
        time.sleep(1)
    check("@Scheduled 超时自动关单", status_now == "CANCEL", status_now)
    d = detail(mtok, o["order_id"])
    check("超时关单留痕 operator=system",
          any(l["operator"] == "system" and l["behavior"] == "timeout_cancel" for l in d["operate_logs"])
          or d["operate_logs"] == [], d["operate_logs"])
    check("超时回写取消申请单", d["cancelData"]["cancel_from"] == "system" and
          "自动关闭" in d["cancelData"]["cancel_reason"], d["cancelData"])
    check("超时回补库存", store(atok) == s0, (store(atok), s0))
    check("定时器已清空", detail(mtok, o["order_id"])["orderInfo"]["auto_cancel_time"] in (None, 0),
          detail(mtok, o["order_id"])["orderInfo"])
    ids = data("GET", "/api/orders/timeout", token=atok)["order_ids"]
    check("关单后不再被扫到", o["order_id"] not in ids, ids)
    print("\n%d/%d passed" % (sum(ok), len(ok)))
    raise SystemExit(0 if all(ok) else 1)

otok = login("/api/h5app/wxapp/new_login",
             {"auth_type": "local", "username": OTHER, "check_type": "mobile", "vcode": VCODE})
vtok = login("/api/operator/login", {"username": "viewer", "password": "viewer" + "123"})
check("AUTH 会员/后台/越权账号就绪", bool(mtok and atok and otok and vtok))

s0 = store(atok)
check("基线库存可读", s0 > 2, s0)

# ===== ORDER-2 =====
o1 = create(mtok, 2)
oid1 = o1["order_id"]
check("ORDER-2 出参字段", set(o1) >= {"order_id", "pay_type", "order_type", "team_id", "total_fee"}, o1)
check("ORDER-2 单号 16 位字符串", isinstance(oid1, str) and len(oid1) == 16, oid1)
check("ORDER-2 金额只来自服务端算价", o1["total_fee"] == 17800, o1)
check("ORDER-2 CAS 扣减 2 件", store(atok) == s0 - 2, store(atok))

forged = create(mtok, 1, {"total_fee": 1, "price": 1})
oid_f = forged["order_id"]
check("ORDER-2 忽略前端金额", forged["total_fee"] == 8900, forged)

tok_body = {"client_token": RUN}
a = create(mtok, 1, tok_body)
b = create(mtok, 1, tok_body)
sa = store(atok)
check("ORDER-2 client_token 幂等", a["order_id"] == b["order_id"], (a, b))
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": b["order_id"], "cancel_reason": "幂等用例清理"}, mtok)
check("幂等单取消回补 1 件", store(atok) == sa + 1, (store(atok), sa))

st, res = req("POST", "/api/h5app/wxapp/order_new",
              {"cart_type": "fastbuy", "address_id": 1, "items": [{"item_id": ITEM, "num": 100000}]}, mtok)
check("库存不足 → 422", st == 422 and res["status_code"] == 422, res)
st, res = req("POST", "/api/h5app/wxapp/order_new",
              {"cart_type": "fastbuy", "items": [{"item_id": ITEM, "num": 1}]}, mtok)
check("收货信息缺失 → 400 且 errors 三键",
      st == 400 and set(res["errors"]) >= {"receiver_name", "receiver_mobile", "receiver_address"}, res)
st, res = req("POST", "/api/h5app/wxapp/order_new",
              {"cart_type": "fastbuy", "address_id": 2, "items": [{"item_id": ITEM, "num": 1}]}, mtok)
check("他人地址 → 422", st == 422, res)
st, res = req("POST", "/api/h5app/wxapp/order_new",
              {"cart_type": "fastbuy", "order_type": "seckill", "address_id": 1,
               "items": [{"item_id": ITEM, "num": 1}]}, mtok)
check("非 normal 订单类型 → 501", st == 501, res)

# ===== ORDER-5 / ORDER-4 =====
d = detail(mtok, oid1)
info = d["orderInfo"]
check("ORDER-5 出参结构", set(d) >= {"orderInfo", "items", "order_status_des", "order_status_msg",
                                     "distributor", "tradeInfo", "cancelData", "logistics_items"}, list(d))
check("ORDER-5 金额逐分", (info["item_fee"], info["freight_fee"], info["member_discount"],
                          info["total_fee"], info["pay_fee"]) == (17800, 0, 0, 17800, 0),
      (info["item_fee"], info["freight_fee"], info["total_fee"], info["pay_fee"]))
check("ORDER-5 状态文案", d["order_status_des"] == "待付款" and "超时订单将自动关闭" in d["order_status_msg"], d["order_status_msg"])
check("ORDER-5 店铺档案", d["distributor"]["name"] == "商城总部" and info["shop_name"] == "商城总部", d["distributor"])
check("ORDER-5 地址簿展开", (info["receiver_name"], info["receiver_state"], info["receiver_city"]) ==
      ("张三", "广东省", "深圳市"), info["receiver_name"])
check("ORDER-5 超时时刻=创建+30min", info["auto_cancel_time"] - info["create_time"] == 1800,
      (info["create_time"], info["auto_cancel_time"]))
check("ORDER-5 明细成交价", (d["items"][0]["price"], d["items"][0]["num"], d["items"][0]["item_fee"],
                            d["items"][0]["order_item_type"]) == (8900, 2, 17800, "normal"), d["items"][0])
check("ORDER-5 前台不给流转日志", d["operate_logs"] == [] and d["tradeInfo"] == {}, d["operate_logs"])
st, res = req("GET", "/api/h5app/wxapp/order/%s" % oid1, token=otok)
check("他人详情 → 403", st == 403 and res["message"] == "无权访问该资源", res)

lst = data("GET", "/api/h5app/wxapp/orders?status=5", token=mtok)
check("ORDER-4 待付款页签含新单", oid1 in [r["order_id"] for r in lst["list"]], lst["total_count"])
check("ORDER-4 行契约", set(lst["list"][0]) >= {"order_id", "order_status", "order_status_des", "total_fee",
                                                 "receiver_name", "distributor", "items"}, list(lst["list"][0]))
check("ORDER-4 单号字符串", all(isinstance(r["order_id"], str) for r in lst["list"]))
check("ORDER-4 待收货页签不含未付款单",
      oid1 not in [r["order_id"] for r in data("GET", "/api/h5app/wxapp/orders?status=1", token=mtok)["list"]])
pg = data("GET", "/api/h5app/wxapp/orders?pageSize=1&page=1", token=mtok)
check("ORDER-4 分页", len(pg["list"]) == 1 and pg["total_count"] > 1, pg["total_count"])

# ===== ORDER-6 =====
c = data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": oid1, "cancel_reason": "下错规格"}, mtok)
check("ORDER-6 关闭", (c["order_status"], c["cancel_status"], c["order_status_des"]) ==
      ("CANCEL", "CANCEL_AGREE", "交易关闭"), c)
check("ORDER-6 回补库存（伪造单在途 1 件）", store(atok) == s0 - 1, (store(atok), s0))
cd = detail(mtok, oid1)
check("ORDER-6 取消申请单", (cd["cancelData"]["progress"], cd["cancelData"]["cancel_from"],
                            cd["cancelData"]["cancel_reason"], cd["cancelData"]["refund_status"]) ==
      (1, "buyer", "下错规格", "NONE"), cd["cancelData"])
st, res = req("POST", "/api/h5app/wxapp/order/cancel", {"order_id": oid1}, mtok)
check("重复取消 → 422", st == 422, res)
st, res = req("POST", "/api/h5app/wxapp/order/confirmReceipt", {"order_id": oid1}, mtok)
check("关闭单确认收货 → 422", st == 422 and "不允许该操作" in res["message"], res)
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": oid_f, "cancel_reason": "清理"}, mtok)
check("在途单清空后库存回基线", store(atok) == s0, (store(atok), s0))

# ===== ORDER-8 =====
cnt = data("GET", "/api/h5app/wxapp/orderscount", token=mtok)
check("ORDER-8 角标字段", set(cnt) == {"notpay", "pending_delivery", "pending_receive", "done", "evaluation", "cancel", "total"}, cnt)
check("ORDER-8 分桶覆盖全态",
      cnt["total"] == cnt["notpay"] + cnt["pending_delivery"] + cnt["pending_receive"] + cnt["done"] + cnt["cancel"], cnt)

# ===== 后台 ORDER-9 族（未付款侧）=====
ad = data("GET", "/api/orders/%s" % oid1, token=atok)
check("后台详情含留痕", len(ad["operate_logs"]) >= 2 and ad["operate_logs"][0]["behavior"] == "create",
      [l["behavior"] for l in ad["operate_logs"]])
check("后台取消留痕角色", any(l["operator_role"] == "buyer" for l in ad["operate_logs"]), ad["operate_logs"])
rm = data("PUT", "/api/orders/remark", {"order_id": oid1, "remark": "实跑备注 " + RUN}, atok)
check("后台备注回显", rm["remark"] == "实跑备注 " + RUN, rm)
check("备注留痕", data("GET", "/api/orders/%s" % oid1, token=atok)["operate_logs"][-1]["behavior"] == "remark")
st, res = req("PUT", "/api/orders/delivery", {"order_id": oid1, "delivery_corp": "zto", "delivery_code": "Z1"}, atok)
check("未付款发货 → 422（结清挂点属 M0-7）", st == 422 and "不允许该操作" in res["message"], res)
to = data("GET", "/api/orders/timeout", token=atok)
check("超时排查端点结构", isinstance(to["order_ids"], list), to)
check("后台列表可按用户筛", data("GET", "/api/orders?user_id=1&pageSize=1", token=atok)["total_count"] >= 1)

# ===== 鉴权 =====
st, res = req("GET", "/api/h5app/wxapp/orders")
check("前台匿名 → 401", st == 401 and res["message"] == "Unauthenticated.", res)
st, res = req("GET", "/api/orders")
check("后台匿名 → 401", st == 401, res)
st, res = req("GET", "/api/orders", token=vtok)
check("viewer 无权限 → 403", st == 403, res)
st, res = req("PUT", "/api/orders/delivery", {"order_id": oid1, "delivery_corp": "zto"}, vtok)
check("viewer 发货 → 403", st == 403 and set(res) == {"message", "errors", "code", "status_code"}, res)
st, res = req("GET", "/api/orders", token=mtok)
check("会员令牌走后台链 → 401", st == 401, res)
st, res = req("GET", "/api/h5app/wxapp/order/not-a-number", token=mtok)
check("非法单号 → 404", st == 404, res)

print("\n%d/%d passed" % (sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
