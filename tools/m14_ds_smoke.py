import json
import time
import urllib.error
import urllib.request

"""M1-4 第一批实跑冒烟（店铺档案 DS + 店铺商品双模 SI + 两条门槛）：真 fat jar 上走一遍 §18 的口径。

MockMvc 的 DistributorEndToEndTest（16 例）管"口径对不对"，本脚本管"起得来的服务对不对"：
权限码缓存与 `operators.distributor_ids` 的 JWT 落地、演示库里真实的商品与总部池、
挂品行在两次运行之间的残留 —— 这些都是内存测试碰不到的东西。

覆盖：
 1. DS-A1 建店：出参契约、`rate` 万分比与 `rate_text` 百分比文案、自营唯一（400）、表单校验 400；
 2. **三列状态各有各的出口**（D42）：DS-A4 改档案动不到审核位，DS-A5 重复审核 422，
    DS-A6 的 `closed` 是终态（改回 `true` 422），DS-A7 费率越界 400；
 3. DS-A8 七个派生数与 §18.5 余额公式（本案新店没账，恒 0，但字段必须齐）；
 4. 会员面四筛一致：DS-F1/F2 与 SI-F1/F2 只出"已审核 + 启用 + 营业"的店，未营业一律 404，
    前台不外露 `balance/rate/origin_price`（配置台与前台读同一份列）；
 5. **门槛①**：同一 SKU 挂进加盟店改价改池 → `CART-2` 按店铺拆两组、各按本店价与本店池出数，
    ORDER-2 混合单 422（一单一店）、按所选店扣店铺池且总部池分毫不动、取消回补同一条腿；
 6. **门槛②**：`distributor01`（`operator_type='distributor'`，`distributor_ids='[2]'`）
    列表面越界=0 行、资源引用面越界=404、无 `review/status` 码=403；`viewer` 403、会员令牌 401；
 7. 收口：SI-A4 删干净挂品行 → 本店价回落总部现价（演示库回到 seed 态）。

脚本按**差值**断言而不是绝对值：演示库会被 m05/m06/m13 等脚本动过，池子现值不是常数。
"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
VPW = "viewer" + "123"
SPW = "shop" + "123"
VCODE = "8888"
MEMBER = "13800000001"
SHOP = 2                      # seed 的 demo 旗舰店（distributor01 只绑了它）
HQ = 1                        # seed 的商城总部店（自营主店铺位）
ITEM = 3                      # 每日坚果礼盒 8900 分 / 模板 1 全国包邮
UNIT = 8900
SHOP_PRICE = 5900             # 挂进加盟店后的本店价
SHOP_STORE = 30               # 本店池
NUM = 2
RUN = "DS%d" % int(time.time())
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


def price_store(item=ITEM, shop=None):
    path = "/api/h5app/wxapp/goods/items_price_store/%s" % item
    if shop:
        path += "?distributor_id=%s" % shop
    return data("GET", path)


def cart(shop=None):
    return data("GET", "/api/h5app/wxapp/cart" + ("?shop_id=%s" % shop if shop else ""), token=mtok)


def group(node, shop_id):
    for g in node["valid_cart"]:
        if g["shop_id"] == shop_id:
            return g
    raise AssertionError("购物车没有店铺 %s 的分组：%s" % (shop_id, node))


atok = login("/api/operator/login", {"username": "admin", "password": PW})
vtok = login("/api/operator/login", {"username": "viewer", "password": VPW})
stok = login("/api/operator/login", {"username": "distributor01", "password": SPW})
mtok = login("/api/h5app/wxapp/new_login", {"auth_type": "local", "username": MEMBER,
                                            "check_type": "mobile", "vcode": VCODE})
check("四类令牌到位（管理员/只读/店铺运营/会员）", all([atok, vtok, stok, mtok]))

# 预清理：上一次跑到一半挂掉留下的挂品行会污染"删干净之后本店价==总部价"这条收尾断言
req("DELETE", "/api/distributor/items", {"distributor_id": SHOP, "item_ids": [2, 3]}, atok)
check("预清理后本店价回落总部现价", price_store()["price"] == UNIT, price_store())

# ===== 1. DS-A1 建店：契约 + 校验 =====
row = data("POST", "/api/distributor", {
    "name": RUN + " 加盟店", "mobile": "13700000001", "province": "浙江省", "city": "杭州市",
    "address": "文三路 1 号", "distribution_type": 1, "is_ziti": True, "rate": 150,
    "payment_subject": 1, "introduce": RUN + " 的简介",
}, atok)
need = {"distributor_id", "name", "is_valid", "review_status", "source_from", "distribution_type",
        "distribution_type_text", "payment_subject", "payment_subject_text", "rate", "rate_text",
        "item_count", "order_count", "pay_fee", "refund_fee", "commission_fee", "balance"}
sid = row["distributor_id"]
check("DS-A1 建店出参字段齐（%s）" % sid, need <= set(row), sorted(need - set(row)))
check("管理端建店 source_from=1 且默认过审", row["source_from"] == 1 and row["review_status"] is True, row)
check("万分比列 + 百分比文案同时给", row["rate"] == 150 and row["rate_text"] == "1.5%", row)
check("店铺收款有中文文案", row["payment_subject_text"] == "店铺收款", row)

st, res = req("POST", "/api/distributor", {"name": RUN + " 第二个自营", "mobile": "13700000002",
                                          "distribution_type": 0}, atok)
check("自营主店铺位全场唯一（seed 已占）", st == 400 and "自营主店铺已存在" in json.dumps(res, ensure_ascii=False), res)
st, res = req("POST", "/api/distributor", {"name": RUN, "mobile": "13700000003", "rate": 20000}, atok)
check("费率越界是表单不合法 400 而不是 422", st == 400 and "rate" in res["errors"], res)
st, res = req("POST", "/api/distributor", {}, atok)
check("缺店名/缺电话各自报错", st == 400 and set(res["errors"]) >= {"name", "mobile"}, res)

# 未审核分店：专门用来验"改档案动不到审核位"
bid = data("POST", "/api/distributor", {"name": RUN + " 待审店", "mobile": "13700000004",
                                        "distribution_type": 1, "review_status": False}, atok)["distributor_id"]
b = data("PUT", "/api/distributor/%s" % bid, {"name": RUN + " 待审店改个电话", "mobile": "13700000005"}, atok)
check("DS-A4 改档案不会顺手把未审核的店过审（D42）", b["review_status"] is False, b)

# ===== 2. DS-A5/A6/A7：三列状态各有各的出口 =====
check("DS-A5 过审后审核位落地", data("PUT", "/api/distributor/%s/review" % bid, {"review_status": True}, atok)["review_status"] is True)
st, res = req("PUT", "/api/distributor/%s/review" % bid, {"review_status": True}, atok)
check("重复审核 422（那条 UPDATE 自然回 0 行）", st == 422 and "无需重复审核" in res["message"], res)
st, res = req("PUT", "/api/distributor/%s/review" % bid, {}, atok)
check("审核表单缺 review_status 是 400", st == 400, res)

check("DS-A6 停用", data("PUT", "/api/distributor/%s/status" % bid, {"is_valid": "false"}, atok)["is_valid"] == "false")
check("DS-A2 停用过滤看得见（配置台看全量）",
      bid in [x["distributor_id"] for x in data("GET", "/api/distributors?is_valid=false&pageSize=100", token=atok)["list"]])
st, res = req("PUT", "/api/distributor/%s/status" % bid, {"is_valid": "yes"}, atok)
check("is_valid 只支持 true/false/closed", st == 400 and "is_valid" in res["errors"], res)
check("DS-A6 关店（无佣金无提现 → 放行）", data("PUT", "/api/distributor/%s/status" % bid, {"is_valid": "closed"}, atok)["is_valid"] == "closed")
st, res = req("PUT", "/api/distributor/%s/status" % bid, {"is_valid": "true"}, atok)
check("closed 是终态：改回启用 422，得走重新开店", st == 422 and "已关店" in res["message"], res)
st, res = req("POST", "/api/distributor/items", {"distributor_id": bid, "items": [{"item_id": ITEM}]}, atok)
check("关了店的店不许再挂品（422）", st == 422 and "已关店" in res["message"], res)

d = data("PUT", "/api/distributor/%s/payment-subject" % sid, {"payment_subject": 0, "rate": 300}, atok)
check("DS-A7 收款主体与费率各自改到位", d["payment_subject_text"] == "平台收款" and d["rate_text"] == "3%", d)
st, res = req("PUT", "/api/distributor/%s/payment-subject" % sid, {"payment_subject": 9}, atok)
check("收款主体枚举 400", st == 400 and "payment_subject" in res["errors"], res)

# ===== 3. DS-A8 统计：§18.5 余额公式的七个组成数 =====
st8 = data("GET", "/api/distributor/count/%s" % sid, token=atok)
need8 = {"distributor_id", "order_count", "pay_fee", "refund_fee", "commission_total",
         "commission_open", "commission_closed", "balance", "withdraw_in_flight"}
check("DS-A8 七个派生数字段齐", need8 <= set(st8), sorted(need8 - set(st8)))
check("本案新店没账：balance = 已结 0 − 提现占用 0", all(st8[k] == 0 for k in need8 - {"distributor_id"}), st8)
st, res = req("GET", "/api/distributor/count/999999", token=atok)
check("不存在的店 404", st == 404, res)

# ===== 4. 会员面：三筛一致 + 不外露账 =====
front = data("GET", "/api/h5app/wxapp/distributors?pageSize=100")["list"]
check("DS-F1 匿名可访且营业中的店都在", SHOP in [x["distributor_id"] for x in front], [x["distributor_id"] for x in front])
check("未过审/已关店的 B 不进前台", bid not in [x["distributor_id"] for x in front])
check("DS-F1 行不外露账（balance/rate 一个都不给）", all("balance" not in x and "rate" not in x for x in front))
st, res = req("GET", "/api/h5app/wxapp/distributors/%s" % bid)
check("DS-F2 未营业的店对前台就是 404（不是 403）", st == 404, res)
home = data("GET", "/api/h5app/wxapp/distributors/%s" % SHOP)
check("DS-F2 店铺主页带简介与挂品数", "introduce" in home and home["item_count"] == 0, home)

# 挂品：本店价 5900 + 本店池 30 + 扣自己的池子
saved = data("POST", "/api/distributor/items", {"distributor_id": SHOP, "items": [
    {"item_id": ITEM, "price": SHOP_PRICE, "store": SHOP_STORE, "is_total_store": False}]}, atok)
check("SI-A1 挂品落库（价/池/两态位）",
      saved["items"][0]["price"] == SHOP_PRICE and saved["items"][0]["store"] == SHOP_STORE
      and saved["items"][0]["is_total_store"] is False, saved)
up = data("POST", "/api/distributor/items", {"distributor_id": SHOP, "items": [{"item_id": ITEM, "price": 6900}]}, atok)
check("SI-A1 重复挂品只覆盖传进来的字段（池子与两态位保留）",
      up["items"][0]["price"] == 6900 and up["items"][0]["store"] == SHOP_STORE
      and up["items"][0]["is_total_store"] is False, up)
data("PUT", "/api/distributors/item", {"distributor_id": SHOP, "item_id": ITEM, "price": SHOP_PRICE}, atok)
st, res = req("PUT", "/api/distributors/item", {"distributor_id": SHOP, "item_id": 999}, atok)
check("未挂品的店改价 404（要先挂品，不悄悄插行）", st == 404 and "未挂此商品" in res["message"], res)
st, res = req("POST", "/api/distributor/items", {"distributor_id": SHOP, "items": [{"item_id": 999}]}, atok)
check("挂不存在的商品 404", st == 404, res)

lst = data("GET", "/api/distributor/items?distributor_id=%s" % SHOP, token=atok)["list"]
row3 = [x for x in lst if x["item_id"] == ITEM][0]
check("SI-A2 双态并排：左列店铺态、右列总部态",
      row3["shop_price"] == SHOP_PRICE and row3["price"] == UNIT
      and row3["effect_price"] == SHOP_PRICE and row3["effect_store"] == SHOP_STORE, row3)
sif1 = data("GET", "/api/h5app/wxapp/goods/shopitems?distributor_id=%s" % SHOP)["list"]
check("SI-F1 前台只出店铺价与店铺池",
      len(sif1) == 1 and sif1[0]["price"] == SHOP_PRICE and sif1[0]["store"] == SHOP_STORE
      and "origin_price" not in sif1[0], sif1)
check("SI-F1 缺 distributor_id 是 400", req("GET", "/api/h5app/wxapp/goods/shopitems")[0] == 400)
ps = price_store(shop=SHOP)
check("SI-F2 两腿都给：price/store + shop_*/origin_price",
      ps["price"] == SHOP_PRICE and ps["store"] == SHOP_STORE and ps["origin_price"] == UNIT, ps)
hq = price_store()
check("SI-F2 不给 distributor_id = 总部态", hq["price"] == UNIT and hq["is_total_store"] is True and hq["store"] > 0, hq)
miss = price_store(shop=HQ)
check("未挂品的总部店回落总部态（页面价与购物车价不分叉）",
      miss["price"] == UNIT and miss["shop_price"] == 0, miss)

# ===== 5. 门槛①：多店铺混合购物车拆单展示 =====
data("POST", "/api/h5app/wxapp/cart", {"item_id": ITEM, "num": 1, "distributor_id": HQ}, mtok)
data("POST", "/api/h5app/wxapp/cart", {"item_id": ITEM, "num": NUM, "distributor_id": SHOP}, mtok)
c = cart()
check("门槛①：同一 SKU 在两家店的车里各成一组",
      set([g["shop_id"] for g in c["valid_cart"]]) >= {HQ, SHOP}, [g["shop_id"] for g in c["valid_cart"]])
g1, g2 = group(c, HQ), group(c, SHOP)
check("两组各按自己的店铺价出数（总部 8900 / 本店 5900×2）",
      g1["cart_total_price"] == UNIT and g2["cart_total_price"] == SHOP_PRICE * NUM, (g1["cart_total_price"], g2["cart_total_price"]))
check("组外壳有店名与自提/配送位", g2["shop_name"] and "is_ziti" in g2 and "hour" in g2, g2.keys())
l1, l2 = g1["list"][0], g2["list"][0]
check("行上带 §18.4 两条腿：is_total_store/stock_source/origin_price",
      l1["stock_source"] == "TOTAL" and l2["stock_source"] == "SHOP"
      and l2["is_total_store"] is False and l2["origin_price"] == UNIT, (l1, l2))
check("加盟店那一行看的是本店池 30，不是总店 200", l2["store"] == SHOP_STORE and l1["store"] != l2["store"], (l1["store"], l2["store"]))

pv = data("POST", "/api/h5app/wxapp/getFreightFee", {"cart_type": "cart"}, mtok)
check("ORDER-1 与 CART-2 同一个 quote：group_count=2", pv["group_count"] == 2, pv["group_count"])
check("预览里两组金额分别分摊，合计=Σ行+运费",
      pv["item_fee"] == UNIT + SHOP_PRICE * NUM and sum(i["total_fee"] for i in pv["items"]) == pv["total_fee"], pv["total_fee"])
st, res = req("POST", "/api/h5app/wxapp/order_new", {
    "cart_type": "cart", "order_type": "normal", "address_id": 1, "order_from": "h5"}, mtok)
check("门槛①：混合单不拆，直接 422 请按店铺分别结算", st == 422 and "多店铺" in res["message"], res)

pool_before = price_store(shop=SHOP)["store"]
hq_before = price_store()["store"]
o2 = data("POST", "/api/h5app/wxapp/order_new", {
    "cart_type": "cart", "order_type": "normal", "distributor_id": SHOP, "address_id": 1, "order_from": "h5"}, mtok)
check("门槛①：按加盟店成交，价是本店价（%d 分）" % (SHOP_PRICE * NUM), o2["total_fee"] == SHOP_PRICE * NUM, o2)
check("扣的是店铺池，总部池分毫不动",
      price_store(shop=SHOP)["store"] == pool_before - NUM and price_store()["store"] == hq_before,
      (pool_before, price_store(shop=SHOP)["store"], hq_before, price_store()["store"]))
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": o2["order_id"], "cancel_reason": "M1-4 冒烟收口"}, mtok)
check("门槛①：取消走同一条腿回补（店铺池回到 %d）" % pool_before, price_store(shop=SHOP)["store"] == pool_before)

# 另一家店那一行单独结算：这一单才动总部池
o1 = data("POST", "/api/h5app/wxapp/order_new", {
    "cart_type": "cart", "order_type": "normal", "distributor_id": HQ, "address_id": 1, "order_from": "h5"}, mtok)
check("总部那一单按总部价成交且只动总部池",
      o1["total_fee"] == UNIT and price_store()["store"] == hq_before - 1
      and price_store(shop=SHOP)["store"] == pool_before, (o1, price_store(), price_store(shop=SHOP)))
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": o1["order_id"], "cancel_reason": "M1-4 冒烟收口"}, mtok)
check("两单都取消：池子回到下单前", price_store()["store"] == hq_before and price_store(shop=SHOP)["store"] == pool_before)

# 店铺停售位排在总部库存之前（§18.4 判序）
data("PUT", "/api/distributors/item", {"distributor_id": SHOP, "item_id": ITEM, "is_can_sale": False}, atok)
data("POST", "/api/h5app/wxapp/cart", {"item_id": ITEM, "num": 1, "distributor_id": SHOP}, mtok)
inv = [g for g in cart()["invalid_cart"] if g["shop_id"] == SHOP]
check("加盟店停售 → 那一行进 invalid_cart（总部池还满着）", len(inv) == 1, cart())
why = data("POST", "/api/h5app/wxapp/getFreightFee", {"cart_type": "cart", "distributor_id": SHOP}, mtok)["invalid_items"]
check("失效原因报「该店铺暂不可售」而不是「库存不足」",
      why and why[0]["reason"] == "该店铺暂不可售", why)
data("PUT", "/api/distributors/item", {"distributor_id": SHOP, "item_id": ITEM, "is_can_sale": True}, atok)

# ===== 6. 门槛②：distributor_id 过滤越权 =====
mine = data("GET", "/api/distributors?pageSize=100", token=stok)["list"]
check("门槛②：店铺运营账号的列表只出绑定的店",
      mine and set([x["distributor_id"] for x in mine]) == {SHOP}, [x["distributor_id"] for x in mine])
check("门槛②：显式要别人家的店 → 过滤到 0 行（条件是查询参数）",
      data("GET", "/api/distributors?distributor_id=%s" % HQ, token=stok)["total_count"] == 0)
st, res = req("GET", "/api/distributors/info?distributor_id=%s" % HQ, token=stok)
check("门槛②：资源引用面越界 404（不给可枚举的信息）", st == 404, res)
st, res = req("GET", "/api/distributor/count/%s" % sid, token=stok)
check("别人家店的经营数据 404", st == 404, res)
st, res = req("GET", "/api/distributor/items?distributor_id=%s" % HQ, token=stok)
check("别人家店的挂品列表 404", st == 404, res)
st, res = req("PUT", "/api/distributor/%s/review" % SHOP, {"review_status": False}, stok)
check("有 update 码但没 review 码 → 403（权限码与数据权限是两条线）", st == 403, res)
st, res = req("PUT", "/api/distributor/%s/status" % SHOP, {"is_valid": "false"}, stok)
check("运营开关另有一条码，同样 403", st == 403, res)
oo = data("GET", "/api/orders?pageSize=100", token=stok)["list"]
check("门槛②：后台订单列表按 distributor_ids 收窄", oo and set([x["distributor_id"] for x in oo]) == {SHOP},
      [(x["order_id"], x["distributor_id"]) for x in oo][:5])
st, res = req("GET", "/api/orders/%s" % o1["order_id"], token=stok)
check("门槛②：别人家店的那张单 404", st == 404, res)
check("门槛②：自己家店的那张单 200",
      data("GET", "/api/orders/%s" % o2["order_id"], token=stok)["orderInfo"]["order_id"] == str(o2["order_id"]))
check("admin 型不受数据权限限制（两家店的单都看得见）",
      str(o1["order_id"]) in [str(x["order_id"]) for x in data("GET", "/api/orders?pageSize=100", token=atok)["list"]])
st, res = req("GET", "/api/distributors", token=vtok)
check("viewer（seed 无 distributor 码）→ 403", st == 403, res)
st, res = req("GET", "/api/distributors", token=mtok)
check("会员 JWT 打后台面 → 401，不泄露端点存在性", st == 401, res)
st, res = req("GET", "/api/distributors")
check("匿名打后台面 → 401 Unauthenticated.", st == 401 and res.get("message") == "Unauthenticated.", res)

# ===== 7. 收口：取消挂品，回到 seed 态 =====
deleted = data("DELETE", "/api/distributor/items", {"distributor_id": SHOP, "item_ids": [ITEM]}, atok)
check("SI-A4 取消挂品（只删关联行，不动 items）", deleted["deleted"] == 1, deleted)
check("门槛① 收尾：删干净后本店价回落总部现价", price_store(shop=SHOP)["price"] == UNIT, price_store(shop=SHOP))
check("删干净后店铺池行消失（shop_store 归 0、两腿回总部态）",
      price_store(shop=SHOP)["shop_store"] == 0 and price_store(shop=SHOP)["is_total_store"] is True)
check("挂品行删完，前台店铺商品列表回空",
      data("GET", "/api/h5app/wxapp/goods/shopitems?distributor_id=%s" % SHOP)["total_count"] == 0)
check("总部池与本店池都回到下单前（本案没留任何库存漂）",
      price_store()["store"] == hq_before, price_store())

print("\n[%s] 通过 %d/%d" % (RUN, sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
