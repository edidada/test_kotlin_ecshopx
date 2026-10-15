import json, urllib.error, urllib.request

BASE = "http://127.0.0.1:18080"
VCODE = "8888"
GOLD = "1380000000" + "2"     # seed 金卡会员（grade_id=3，95 折）

ok = []


def req(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else " -> " + str(extra)[:500]))


def line_of(data, item_id):
    for g in data["valid_cart"]:
        for l in g["list"]:
            if l["item_id"] == item_id:
                return l
    raise AssertionError("车内无 item_id=%s: %s" % (item_id, data))


def group(data):
    return data["valid_cart"][0]


st, res = req("POST", "/api/h5app/wxapp/new_login",
              {"auth_type": "local", "username": GOLD, "check_type": "mobile", "vcode": VCODE})
check("AUTH-4 金卡会员登录", st == 200 and res["data"].get("token"), res)
tok = res["data"]["token"]

st, res = req("GET", "/api/h5app/wxapp/cart", token=tok)
check("CART-2 结构", st == 200 and set(res["data"]) >= {"valid_cart", "invalid_cart", "is_check_store"}, res)

st, res = req("POST", "/api/h5app/wxapp/cart", {"item_id": 2, "num": 2}, tok)
g = group(res["data"])
phone = line_of(res["data"], 2)
check("CART-1 店铺档案", g["shop_id"] == 1 and g["shop_name"] == "商城总部", g)
check("CART-1 行金额", phone["price"] == 199900 and phone["num"] == 2 and not phone["is_last_price"], phone)
check("CART-1 规格描述", phone["item_spec_desc"] == "白色、128G", phone)
check("CART-1 组金额 95 折", (g["item_fee"], g["member_discount"], g["discount_fee"], g["total_fee"]) ==
      (399800, 19990, 19990, 379810), g)
check("CART-1 默认包邮", g["freight"] == 0, g)
phone_id, nut_id = phone["cart_id"], 0

st, res = req("POST", "/api/h5app/wxapp/cart", {"item_id": 3, "num": 1}, tok)
g = group(res["data"])
nut = line_of(res["data"], 3)
nut_id = nut["cart_id"]
check("CART-1 第二行并入同店组", g["cart_total_count"] == 2 and g["cart_total_num"] == 3, g)
check("CART-1 累加金额", (g["item_fee"], g["discount_fee"], g["total_fee"]) == (408700, 19990 + 445, 388265), g)

st, a = req("GET", "/api/h5app/wxapp/cart", token=tok)
st, p = req("POST", "/api/h5app/wxapp/getFreightFee", {"cart_type": "cart", "order_type": "normal"}, tok)
ga, d = group(a["data"]), p["data"]
check("ORDER-1 预览==车内", (ga["item_fee"], ga["freight"], ga["discount_fee"], ga["member_discount"], ga["total_fee"]) ==
      (d["item_fee"], d["freight_fee"], d["discount_fee"], d["member_discount"], d["total_fee"]), (ga, d))
check("ORDER-1 件数与分组", d["totalItemNum"] == 3 and d["group_count"] == 1 and len(d["items"]) == 2, d)
st, p2 = req("POST", "/api/h5app/wxapp/getFreightFee", {"cart_type": "cart", "coupon_code": "CP1"}, tok)
check("ORDER-1 券为开关位", p2["data"]["total_fee"] == d["total_fee"] and p2["data"]["coupon_info"] == [], p2["data"])

st, res = req("PUT", "/api/h5app/wxapp/cartupdate/num", {"cart_id": nut_id, "num": 5}, tok)
g = group(res["data"])
check("CART-4 改数量重算", (g["item_fee"], g["cart_total_num"], g["discount_fee"]) == (399800 + 44500, 7, 19990 + 2225), g)

st, res = req("PUT", "/api/h5app/wxapp/cartupdate/checkstatus", {"cart_ids": [phone_id], "is_checked": False}, tok)
g = group(res["data"])
check("CART-5 未勾选不计金额", (g["item_fee"], g["cart_total_num"], g["cart_total_count"]) == (44500, 5, 1), g)
check("CART-5 未勾选行仍展示", len(g["list"]) == 2 and not line_of(res["data"], 2)["is_checked"], g["list"])

st, res = req("GET", "/api/h5app/wxapp/cartcount", token=tok)
check("CART-6 角标含未勾选", (res["data"]["count"], res["data"]["num"]) == (2, 7), res)

st, res = req("POST", "/api/h5app/wxapp/getFreightFee",
              {"cart_type": "fastbuy", "order_type": "normal", "items": [{"item_id": 3, "num": 2}]}, tok)
d = res["data"]
check("ORDER-1 fastbuy 独立取价", (d["item_fee"], d["member_discount"], d["total_fee"]) == (17800, 890, 16910), d)

st, res = req("POST", "/api/h5app/wxapp/getFreightFee", {"cart_type": "fastbuy", "item_id": 3, "item_num": 1}, tok)
check("ORDER-1 单品写法", res["data"]["item_fee"] == 8900 and res["data"]["totalItemNum"] == 1, res["data"])

st, res = req("DELETE", "/api/h5app/wxapp/cartdel", {"cart_ids": [phone_id, nut_id]}, tok)
check("CART-3 删除回吐全量", res["data"]["valid_cart"] == [] and res["data"]["invalid_cart"] == [], res)
st, res = req("GET", "/api/h5app/wxapp/cartcount", token=tok)
check("CART-3 后角标归零", res["data"]["count"] == 0, res)

st, res = req("POST", "/api/h5app/wxapp/cart", {"item_id": 3, "num": 0}, tok)
check("校验 num=0 → 400", st == 400 and res["errors"]["num"], res)
st, res = req("POST", "/api/h5app/wxapp/cart", {"item_id": 999999, "num": 1}, tok)
check("商品不存在 → 404", st == 404, res)
st, res = req("GET", "/api/h5app/wxapp/cart")
check("匿名 → 401", st == 401 and res["message"] == "Unauthenticated.", res)

print("\n%d/%d passed" % (sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
