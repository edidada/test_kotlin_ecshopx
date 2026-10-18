import json
import time
import urllib.error
import urllib.parse
import urllib.request

"""M1-4 第三批（自提点 PL + 取货码核销 WO）实跑冒烟：真 fat jar 上走一遍 docs/04 §18.6 的口径。

PickupWriteOffEndToEndTest（15 例）管"口径对不对"，本脚本管"起得来的服务对不对"：
`seq_generator` 里 `ziti_code` 那行在真实启动的库里存不存在（缺行是 check 抛错、不是 422）、
`orders_normal_orders.pickup_location_id` 这一列在常驻库上有没有 ALTER 到、seed 追加的两条演示点
与三条权限码是否真的落进了 `resources` —— 这些都是内存测试碰不到的东西。

覆盖：
 1. PL-A1 列表：`shop_name` 是 JOIN 出来的（H2 只认 snake 别名）、平台通用点归属名为空串、
    过滤位 `status/rel_distributor_id/keyword` 各自生效、店铺账号按归属列收窄；
 2. PL-A2/A3/A4/A5：必填校验 400、`status` 脏值 400（不再归一为 1）、`distributor_id=0` 的通用点
    只有 admin 建得了（店铺账号 403 —— **权限判定在归属之前**）、读-改-写只动传进来的字段、
    **停用前数未完结自提单**（422），核销完才停得掉；
 3. PL-F1 / DS-F2：匿名可访、电话掩码、传坐标才出 `distance` 键、通用点靠 `rel_distributor_id=0` 出得来；
 4. 建单发码：6 位码落库且 `ziti_status='false'`、没显式选点回落该店第一个启用点、显式传 0 同义、
    选了别人店的点 422、**未开自提的店走 ziti → 422**（这条只在实跑里补，e2e 没覆盖）；
 5. ZT-F1：会员看得着自己的码（电话不掩码），别人的单 404，未登录 401，物流单 422；
 6. WO-A1/A2 四闸门：未付款 / 非自提 / 码逐字 / 空串与缺字段各自的码；核销即完成
    （DONE + delivery DONE + end_time + 清空两个自动时间 + 留痕 + 再核 422 + 码文案变"已失效"）；
 7. ORDER-4 页签 4 进出与 ORDER-9 详情 `orderInfo.ziti_code`；权限码、跨面 401、审计留痕；
 8. 收口：本轮建的点全部停用 → 前台取点页回到 seed 态（脚本按差值断言，可连跑两遍）。

"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
VPW = "viewer" + "123"
SPW = "shop" + "123"
VCODE = "8888"
MEMBER = "13800000001"
OTHER = "13800000002"
SHOP = 2                      # seed 的 demo 旗舰店（distributor01 只绑了它，且 is_ziti）
HQ = 1                        # seed 的商城总部店
ITEM = 3                      # 每日坚果礼盒 8900 分
RUN = "ZT%d" % int(time.time())
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


def member_login(user):
    return login("/api/h5app/wxapp/new_login", {"auth_type": "local", "username": user,
                                                "check_type": "mobile", "vcode": VCODE})


def point(shop, name, **kw):
    body = {"distributor_id": shop, "name": name, "province": "浙江省", "city": "杭州市",
            "area": "西湖区", "address": name + " 门口", "lng": "120.026000", "lat": "30.279000",
            "contract_phone": kw.pop("phone", "057166668888"), "status": kw.pop("status", 1)}
    if "rel" in kw:
        body["rel_distributor_id"] = kw.pop("rel")
    body.update(kw)
    return body


def new_point(shop, name, token=None, **kw):
    return data("POST", "/api/pickuplocation", point(shop, name, **kw), token or atok)


def pl_count(**query):
    q = "&".join("%s=%s" % (k, urllib.parse.quote(str(v))) for k, v in query.items())
    return data("GET", "/api/pickuplocation?pageSize=100&" + q, token=atok)["total_count"]


def front_points(shop, lng=None, lat=None):
    path = "/api/h5app/wxapp/distributor/pickuplocation?distributor_id=%s" % shop
    if lng:
        path += "&lng=%s&lat=%s" % (lng, lat)
    return data("GET", path)["list"]


def ziti_order(shop, point_id=None, tok=None):
    """车内结算建自提单：先加购物车（一单一店，混合会 422）"""
    data("POST", "/api/h5app/wxapp/cart", {"item_id": ITEM, "num": 1, "distributor_id": shop}, tok or mtok)
    body = {"cart_type": "cart", "order_type": "normal", "distributor_id": shop,
            "receipt_type": "ziti", "receiver_name": "张三", "receiver_mobile": MEMBER, "order_from": "h5"}
    if point_id is not None:
        body["pickup_location_id"] = point_id
    return data("POST", "/api/h5app/wxapp/order_new", body, tok or mtok)


def pay(order):
    t = data("POST", "/api/h5app/wxapp/payment", {"order_id": order, "pay_type": "wxpay"}, mtok)
    fields = {"out_trade_no": t["trade_id"], "total_fee": str(order_fee(order)), "trade_state": "SUCCESS",
              "transaction_id": "WX-" + t["trade_id"][-6:]}
    assert raw("/api/notify/wxpay", urllib.parse.urlencode(fields), "application/x-www-form-urlencoded")[1].find("SUCCESS") >= 0


def order_fee(order):
    return int(data("GET", "/api/h5app/wxapp/order/%s" % order, token=mtok)["orderInfo"]["total_fee"])


def code_of(order):
    return data("GET", "/api/h5app/wxapp/order/%s/ziticode" % order, token=mtok)["ziti_code"]


def wrong(code):
    return str((int(code) + 1) % 1000000).zfill(6)


def wo(order, body=None, token=None):
    return req("POST", "/api/writeoff/%s" % order, body if body is not None else {"ziti_code": code_of(order)},
               token if token is not None else atok)


def cart_rows():
    c = data("GET", "/api/h5app/wxapp/cart", token=mtok)
    out = []
    for key in ("valid_cart", "invalid_cart"):
        for g in c.get(key) or []:
            out += [r["cart_id"] for r in g["list"]]
    return out


def clear_cart():
    """被拒的建单会把车内的行留在原地；不清干净就污染同一演示库上的兄弟脚本（m14_ds 数的是分组数）"""
    ids = cart_rows()
    if ids:
        data("DELETE", "/api/h5app/wxapp/cartdel", {"cart_ids": ids}, mtok)
    return len(ids)


def audit_count(route):
    """SUP-4 的 keyword 筛的是 route_name LIKE：拿它做差值，才知道"这一轮"留没留痕"""
    return data("GET", "/api/operator/logs?keyword=%s&pageSize=1" % route, token=atok)["total_count"]


atok = login("/api/operator/login", {"username": "admin", "password": PW})
vtok = login("/api/operator/login", {"username": "viewer", "password": VPW})
stok = login("/api/operator/login", {"username": "distributor01", "password": SPW})
mtok = member_login(MEMBER)
otok = member_login(OTHER)
check("四类令牌到位（管理员/只读/店铺运营/会员×2）", all([atok, vtok, stok, mtok, otok]))

# 预清理：上一轮（或兄弟脚本）留在车内的行会让"一单一店"的建单一次结算多行，先清场
clear_cart()

# 审计基线：库里是常驻数据，只有差值能证明"这一轮写的"
PL_LOG0 = audit_count("pickup.update")
WO_LOG0 = audit_count("order.writeoff")

# ===== 1. 库里真的有这一批的东西（实跑专属判据） =====
page = data("GET", "/api/pickuplocation?pageSize=50", token=atok)
need = {"id", "company_id", "distributor_id", "shop_name", "rel_distributor_id", "name", "province", "city",
        "area", "address", "full_address", "lng", "lat", "contract_phone", "status", "distance", "created", "updated"}
check("PL-A1 外层是 PageResult 两键", set(page) == {"total_count", "list"}, sorted(page))
check("PL-A1 行列齐（含 JOIN 的 shop_name 与可空的 distance）",
      page["list"] and all(need <= set(x) for x in page["list"]), sorted(need - set(page["list"][0])))
# 按**名字**认 seed 的两条点：列表默认按 id 倒序，重复跑时本轮留下的点会排在前面（第二轮就是这么脏的）
own = [x for x in page["list"] if x["name"] == "demo 旗舰店自提柜台"][0]
plat = [x for x in page["list"] if x["name"] == "平台中心仓（全城可取）"][0]
check("seed 追加的两条演示点在常驻库里真的存在", own["shop_name"] == "demo 旗舰店" and plat["shop_name"] == "", (own, plat))
check("后台列表给明文电话（运营要打就是这个号）", own["contract_phone"] == "057188886666", own)
check("整段地址在服务层拼，不让前端各拼一遍", own["full_address"] == "浙江省杭州市西湖区文一西路 969 号 A 座 1 层", own["full_address"])
check("distance 没传坐标是 null 键（Jackson 没配 NON_NULL，键必须在）", "distance" in own and own["distance"] is None)
check("PL-A1 过滤位：status 两值互斥且并起来是全量；keyword 精确命中 seed 行",
      pl_count(status=0) + pl_count(status=1) == pl_count()
      and pl_count(keyword="自提柜台") == 1 and pl_count(keyword="中心仓") == 1,
      (pl_count(status=0), pl_count(status=1), pl_count()))
mine = data("GET", "/api/pickuplocation?pageSize=100", token=stok)["list"]
check("店铺账号只看得到自己归属的点（通用点不在集合里）",
      mine and set([x["distributor_id"] for x in mine]) == {SHOP}, [x["distributor_id"] for x in mine])
check("viewer 无权限码 403；匿名 401 Unauthenticated.",
      req("GET", "/api/pickuplocation", token=vtok)[0] == 403
      and (req("GET", "/api/pickuplocation")[0] == 401
           and req("GET", "/api/pickuplocation")[1].get("message") == "Unauthenticated."))

# ===== 2. PL-A2/A3：建与改 =====
before = pl_count(keyword=RUN)
n1 = new_point(SHOP, RUN + " 一号柜台")
check("PL-A2 出参形状与列表一致（建完直接插进列表不用两套渲染）", need <= set(n1) and n1["shop_name"] == "demo 旗舰店", sorted(need - set(n1)))
check("PL-A2 默认启用；不传 rel 就是 0 = 使用方不设限（归属列才是「这是谁的点」）",
      n1["status"] == 1 and n1["rel_distributor_id"] == 0, n1)
check("PL-A1 本轮计数 +1", pl_count(keyword=RUN) == before + 1, pl_count(keyword=RUN))
st, res = req("POST", "/api/pickuplocation", {"distributor_id": SHOP, "name": "", "contract_phone": "1"}, atok)
check("空名 + 缺地址：两个键各自报错（400 而不是 422）", st == 400 and set(res["errors"]) >= {"name", "address"}, res)
st, res = req("POST", "/api/pickuplocation", {"distributor_id": SHOP, "name": RUN + " 缺电话", "address": "a"}, atok)
check("缺 contract_phone 单独报一条", st == 400 and "contract_phone" in res["errors"], res)
st, res = req("POST", "/api/pickuplocation", point(SHOP, RUN + " 坐标脏", lng="东经120"), atok)
check("lng 非数字 400：列是 VARCHAR 但入参不验就把脏值存进库", st == 400 and "lng" in res["errors"], res)
st, res = req("POST", "/api/pickuplocation", point(SHOP, RUN + " 状态脏", status=7), atok)
check("status 脏值 400（这批不再顺手归一为 1）", st == 400 and "status" in res["errors"], res)
st, res = req("POST", "/api/pickuplocation", point(987654, RUN + " 别人店的点"), atok)
check("归属店铺不存在 404（引用完整性走 404，使用方不存在才走 422）", st == 404, res)
st, res = req("POST", "/api/pickuplocation", point(SHOP, RUN + " 使用方是空气", rel=999999), atok)
check("使用方店不存在 422：挂在校验里，不是挂在权限里", st == 422 and "rel_distributor_id" in res["errors"], res)
plat2 = new_point(0, RUN + " 平台通用点")
check("admin 建得了 distributor_id=0 的全平台通用点", plat2["distributor_id"] == 0 and plat2["shop_name"] == "", plat2)
st, res = req("POST", "/api/pickuplocation", point(0, RUN + " 店铺想建通用点"), stok)
check("店铺账号建通用点 → 403（§18.7 只给它 pickup.lists + order.writeoff，写权限一条没配）", st == 403, res)
st, res = req("POST", "/api/pickuplocation", point(SHOP, RUN + " 店铺建自己店的点"), stok)
check("连建自己店的点也是 403：权限码判在归属之前，403 是常态不是 404 的形状试探", st == 403, res)

n2 = data("PUT", "/api/pickuplocation/%s" % n1["id"], {"contract_phone": "057188880001"}, atok)
check("PL-A3 读-改-写：只动传进来的字段",
      n2["contract_phone"] == "057188880001" and n2["name"] == RUN + " 一号柜台"
      and n2["address"] == RUN + " 一号柜台 门口" and n2["status"] == 1, n2)
st, res = req("PUT", "/api/pickuplocation/%s" % n1["id"], {"name": ""}, atok)
check("改的时候把必填清空同样 400", st == 400 and "name" in res["errors"], res)
st, res = req("PUT", "/api/pickuplocation/%s" % n1["id"], {"status": 5}, atok)
check("status 只收 1/0：越界值 400", st == 400 and "status" in res["errors"], res)
st, res = req("PUT", "/api/pickuplocation/987654", {"name": "不存在的点"}, atok)
check("不存在的点 404", st == 404, res)
st, res = req("PUT", "/api/pickuplocation/abc", {"name": "不是数字"}, atok)
check("id 不是数字也 404（与源系统「查不到这条记录」一致）", st == 404, res)
st, res = req("PUT", "/api/pickuplocation/%s" % plat2["id"], {"name": "越权改"}, stok)
check("店铺账号改不了平台通用点：403", st == 403, res)

# ===== 3. PL-A5 挂店/摘店 =====
b = data("PUT", "/api/pickuplocation/reldistributor", {"id": plat2["id"], "distributor_id": HQ, "bind": True}, atok)
check("PL-A5 把通用点挂给总部用", b["rel_distributor_id"] == HQ and b["bind"] is True, b)
check("挂上之后通用点仍归 0（挂店动的是使用方列，不是归属列）",
      [x for x in data("GET", "/api/pickuplocation?keyword=%s" % RUN, token=atok)["list"]
       if x["id"] == plat2["id"]][0]["distributor_id"] == 0)
check("PL-A5 摘回全平台通用（rel=0）",
      data("PUT", "/api/pickuplocation/reldistributor", {"id": plat2["id"], "bind": False}, atok)["rel_distributor_id"] == 0)
st, res = req("PUT", "/api/pickuplocation/reldistributor", {"id": plat2["id"], "bind": True, "distributor_id": 999999}, atok)
check("挂给不存在的店 422（不是 404：这是引用完整性，不是资源不存在）", st == 422, res)

# ===== 4. PL-F1 / DS-F2 前台 =====
f = front_points(SHOP)
check("PL-F1 匿名可访，且只出启用的点", RUN + " 一号柜台" in [x["name"] for x in f], [x["name"] for x in f])
check("前台电话一律掩码（前 3 后 4，改过的号码也一样）",
      [x for x in f if x["name"] == RUN + " 一号柜台"][0]["contract_phone"] == "057****0001", f)
check("平台通用点靠 rel_distributor_id=0 出得来", RUN + " 平台通用点" in [x["name"] for x in f], [x["name"] for x in f])
fd = front_points(SHOP, "120.026000", "30.279000")
same = [x for x in fd if x["name"] == RUN + " 一号柜台"][0]
check("传坐标才出 distance，同点算得 0.0 km", same["distance"] == 0.0, same)
far = front_points(SHOP, "121.500000", "31.200000")
check("换一个坐标距离就变（不是常数）",
      [x for x in far if x["name"] == RUN + " 一号柜台"][0]["distance"] > 0.5, far)
st, res = req("GET", "/api/h5app/wxapp/distributor/pickuplocation")
check("PL-F1 缺 distributor_id 是 400", st == 400 and "distributor_id" in res["errors"], res)
home = data("GET", "/api/h5app/wxapp/distributors/%s" % SHOP)
check("DS-F2 店铺主页带 pickup_locations（与 PL-F1 同一份装配）",
      home["pickup_locations"] and home["pickup_locations"][0]["contract_phone"].count("*") >= 4, home["pickup_locations"][:1])

# ===== 5. 建单发码（含两条只在实跑里补的闸门） =====
def pool():
    return data("GET", "/api/h5app/wxapp/goods/items_price_store/%s" % ITEM)["store"]


pool0 = pool()
plain = data("POST", "/api/distributor", {"name": RUN + " 只发快递的店", "mobile": "13700000007",
                                          "distribution_type": 1, "is_ziti": False}, atok)["distributor_id"]
data("POST", "/api/h5app/wxapp/cart", {"item_id": ITEM, "num": 1, "distributor_id": plain}, mtok)
st, res = req("POST", "/api/h5app/wxapp/order_new", {"cart_type": "cart", "order_type": "normal",
                                                    "distributor_id": plain, "receipt_type": "ziti",
                                                    "receiver_name": "张三", "receiver_mobile": MEMBER}, mtok)
check("未开自提的店走 ziti → 422", st == 422 and "不支持自提" in res["message"], res)
check("闸门在扣库存之前：这一拒分毫没动池子（%d）" % pool0, pool() == pool0, pool())

nopoint = data("POST", "/api/distributor", {"name": RUN + " 开了自提没配点", "mobile": "13700000008",
                                            "distribution_type": 1, "is_ziti": True}, atok)["distributor_id"]
np_order = ziti_order(nopoint)["order_id"]
np_point = data("GET", "/api/h5app/wxapp/order/%s/ziticode" % np_order, token=mtok)["pickup"]["id"]
check("本店一个点都没建，但全平台通用点（rel_distributor_id=0）那条通路让它成交（§18.6）",
      np_point in (plat["id"], plat2["id"]), (np_point, plat["id"], plat2["id"]))
check("这一单扣了总店池一格（未挂品的加盟店走总部池）", pool() == pool0 - 1, pool())
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": np_order, "cancel_reason": "M1-4 冒烟收口"}, mtok)
check("取消回补同一条腿：自提单没有第二套库存口径", pool() == pool0, pool())
check("取消后的自提单不留下「占着点」的未核销单（停用闸门数的是 order_status<>DONE）",
      data("GET", "/api/orders/%s" % np_order, token=atok)["orderInfo"]["order_status"] == "CANCEL", np_order)

o = ziti_order(SHOP)["order_id"]
code = code_of(o)
check("建单即发码：6 位数字（号段值取末 6 位，可回溯）", len(code) == 6 and code.isdigit(), code)
check("ZT-F1 未核销态与单号", data("GET", "/api/h5app/wxapp/order/%s/ziticode" % o, token=mtok)["ziti_status"] == "false", o)
zc = data("GET", "/api/h5app/wxapp/order/%s/ziticode" % o, token=mtok)
check("会员面给自己看码：电话不掩码", zc["pickup"]["contract_phone"] == "057188886666", zc["pickup"])
check("码文案在生效前是「到店出示本码」", "到店出示本码" in zc["valid_text"], zc["valid_text"])
check("没显式选点 → 落该店第一个启用点", zc["pickup"]["id"] == own["id"], zc["pickup"])
st, res = req("GET", "/api/h5app/wxapp/order/%s/ziticode" % o, token=otok)
check("别人的单 404（不给「这单存在」的信息）", st == 404, res)
st, res = req("GET", "/api/h5app/wxapp/order/%s/ziticode" % o)
check("未登录 401 Unauthenticated.", st == 401 and res.get("message") == "Unauthenticated.", res)

o2 = ziti_order(SHOP, point_id=plat2["id"])["order_id"]
check("显式选平台通用点：落的是选的那个",
      data("GET", "/api/h5app/wxapp/order/%s/ziticode" % o2, token=mtok)["pickup"]["id"] == plat2["id"])
check("两单两张码", code_of(o2) != code, (code, code_of(o2)))
check("码是存下来的：再建一单也不回改上一单", code_of(o) == code)
data("POST", "/api/h5app/wxapp/cart", {"item_id": ITEM, "num": 1, "distributor_id": SHOP}, mtok)
poolG = pool()
st, res = req("POST", "/api/h5app/wxapp/order_new", {"cart_type": "cart", "order_type": "normal",
                                                    "distributor_id": SHOP, "receipt_type": "ziti",
                                                    "pickup_location_id": int(code) + 90000,
                                                    "receiver_name": "张三", "receiver_mobile": MEMBER}, mtok)
check("选了不属于这店、也不通用的点 → 422（闸门仍在扣库存之前）",
      st == 422 and "pickup_location_id" in res["errors"] and pool() == poolG, (res, pool()))
o3 = ziti_order(SHOP, point_id=0)["order_id"]
check("显式传 0 = 买家没选点：回落该店第一个启用点而不是报错",
      data("GET", "/api/h5app/wxapp/order/%s/ziticode" % o3, token=mtok)["pickup"]["id"] == own["id"])
lorder = data("POST", "/api/h5app/wxapp/order_new", {"cart_type": "fastbuy", "order_type": "normal",
                                                    "items": [{"item_id": ITEM, "num": 1}], "address_id": 1,
                                                    "receiver_name": "张三", "receiver_mobile": MEMBER,
                                                    "order_from": "h5"}, mtok)["order_id"]
st, res = req("GET", "/api/h5app/wxapp/order/%s/ziticode" % lorder, token=mtok)
check("物流单没有码：422（不是 404，这单确实存在）", st == 422, res)

# ===== 6. WO-A1 查询面 =====
v = data("GET", "/api/writeoff/%s" % o, token=atok)
need_wo = {"order_id", "order_no", "ziti_code", "ziti_status", "ziti_status_text", "order_status", "pay_status",
           "receipt_type", "pickup_location_id", "distributor_id", "user_id", "receiver_name", "receiver_mobile",
           "total_fee", "pay_fee", "created", "updated", "pickup", "items"}
check("WO-A1 出形状（后台核销页要的一切都在这一份里）", need_wo <= set(v), sorted(need_wo - set(v)))
check("单号就是 order_id（本项目口径）", v["order_no"] == str(o) == v["order_id"])
check("待核销文案", v["ziti_status_text"] == "待核销" and v["ziti_status"] == "false", v)
check("自提单没有发货环节：receipt_type 就是 ziti", v["receipt_type"] == "ziti", v)
check("明细行带品名与数量", v["items"] and {"item_id", "item_name", "num", "price", "total_fee"} == set(v["items"][0]), v["items"][:1])
check("取货块给的是点现值（名字/地址/电话齐）",
      v["pickup"]["id"] == own["id"] and v["pickup"]["full_address"] and v["pickup"]["contract_phone"] == "057188886666", v["pickup"])
st, res = req("GET", "/api/writeoff/%s?ziti_code=%s" % (o, wrong(code)), token=atok)
check("查询面也逐字比码：不符 422", st == 422 and "取货码不正确" in res["message"], res)
st, res = req("GET", "/api/writeoff/abc", token=atok)
check("路径变量非法形状 → 404（不当 500 报）", st == 404, res)

# ===== 7. 四闸门 =====
st, res = wo(o2)
check("闸门②：未付款 422 且没改成行", st == 422 and "未付款" in res["message"], res)
st, res = wo(lorder, {"ziti_code": "123456"})
check("闸门①：物流单不是自提单 422", st == 422 and "不是自提订单" in res["message"], res)
pay(o)
st, res = wo(o, {"ziti_code": wrong(code)})
check("闸门③：码不符 422（逐字，只 trim 两侧空白）", st == 422 and "取货码不正确" in res["message"], res)
st, res = wo(o, {"ziti_code": ""})
check("空串走比对而不是「没传」：422", st == 422, res)
st, res = wo(o, {})
check("缺字段才 400", st == 400 and "ziti_code" in res["errors"], res)
check("四条拦下来之后这张单还是待核销",
      data("GET", "/api/writeoff/%s" % o, token=atok)["ziti_status"] == "false")

# ===== 8. 核销即完成 =====
done = data("POST", "/api/writeoff/%s" % o, {"ziti_code": " %s " % code}, atok)
check("码两侧空白算 trim（§18.6「不区分前后空格」）", done["ziti_status"] == "true", done)
check("核销即完成：order_status=DONE 且文案变已核销",
      done["order_status"] == "DONE" and done["ziti_status_text"] == "已核销", done)
check("核销页读出来也是已核销", data("GET", "/api/writeoff/%s" % o, token=atok)["ziti_status"] == "true")
d9 = data("GET", "/api/orders/%s" % o, token=atok)
check("ORDER-9 详情带这两个列（嵌套在 orderInfo 下）",
      d9["orderInfo"]["ziti_code"] == code and d9["orderInfo"]["ziti_status"] == "true", d9["orderInfo"])
check("发货态一起 DONE（自提没有发货环节）", d9["orderInfo"]["delivery_status"] == "DONE", d9["orderInfo"])
check("两个自动时间清空：定时器不该再动已完成的单",
      int(d9["orderInfo"]["auto_cancel_time"] or 0) == 0 and int(d9["orderInfo"]["auto_finish_time"] or 0) == 0,
      (d9["orderInfo"]["auto_cancel_time"], d9["orderInfo"]["auto_finish_time"]))
check("end_time 落在核销这一刻", int(d9["orderInfo"]["end_time"]) > 0, d9["orderInfo"]["end_time"])
check("会员侧文案变「已失效」", "已失效" in data("GET", "/api/h5app/wxapp/order/%s/ziticode" % o, token=mtok)["valid_text"])
st, res = wo(o)
check("闸门④：重复核销 422（防双核靠 UPDATE 的受影响行数）",
      st == 422 and "已核销" in res["message"], res)

# ===== 9. 待自提页签进出 =====
def ziti_tab():
    return [str(x["order_id"]) for x in data("GET", "/api/h5app/wxapp/orders?status=4&pageSize=100", token=mtok)["list"]]


check("未付款的自提单不在待自提页签里（页签要 pay_status=PAYED）", str(o3) not in ziti_tab(), ziti_tab()[:8])
pay(o3)
check("付款后即进待自提页签（§2.4：ziti + 已付款 + 未发齐）", str(o3) in ziti_tab(), ziti_tab()[:8])
data("POST", "/api/writeoff/%s" % o3, {"ziti_code": code_of(o3)}, atok)
check("核销后即离开待自提页签（DONE 不再满足页签条件）", str(o3) not in ziti_tab(), ziti_tab()[:8])
check("已核销的那张也早就不在页签里", str(o) not in ziti_tab(), ziti_tab()[:8])

# ===== 10. 停用闸门：数的是"未完结"，不是"有过单" =====
hold = ziti_order(SHOP, point_id=n1["id"])["order_id"]
st, res = req("DELETE", "/api/pickuplocation/%s" % n1["id"], token=atok)
check("有未核销自提单指着 → 停用 422（买家拿着码到店却无处取货）", st == 422 and "未核销" in res["message"], res)
check("被拦之后点还是启用态（422 不落库）",
      [x for x in data("GET", "/api/pickuplocation?keyword=%s" % RUN, token=atok)["list"]
       if x["id"] == n1["id"]][0]["status"] == 1)
# 单上只存 id：门牌改了，核销页要显示"现在去哪儿取"（§18.6 不存快照的理由）
data("PUT", "/api/pickuplocation/%s" % n1["id"], {"address": RUN + " 改成新门牌"}, atok)
check("核销页显示的是改后的地址，不是建单那一刻的",
      data("GET", "/api/writeoff/%s" % hold, token=atok)["pickup"]["address"] == RUN + " 改成新门牌")
pay(hold)
data("POST", "/api/writeoff/%s" % hold, {"ziti_code": code_of(hold)}, atok)
dis = data("DELETE", "/api/pickuplocation/%s" % n1["id"], token=atok)
check("核销完就停得掉（PL-A4 出参回这一行）", dis["status"] == 0 and dis["id"] == n1["id"], dis)
check("停用的点从前台取点页消失", RUN + " 一号柜台" not in [x["name"] for x in front_points(SHOP)])
check("历史单的取货地址不能被「停用」抹掉：核销页仍读得到并如实报停用态",
      (lambda p: p["id"] == n1["id"] and p["status"] == 0)(data("GET", "/api/writeoff/%s" % hold, token=atok)["pickup"]))

# ===== 11. 数据权限 / 跨面 / 审计 =====
oid = ziti_order(SHOP)["order_id"]
pay(oid)
check("店铺 token 核销自己店的单可以（role2 配了 order.writeoff）",
      data("POST", "/api/writeoff/%s" % oid, {"ziti_code": code_of(oid)}, stok)["ziti_status"] == "true")
hqo = ziti_order(HQ)["order_id"]
pay(hqo)
st, res = req("POST", "/api/writeoff/%s" % hqo, {"ziti_code": code_of(hqo)}, stok)
check("别人店的单：店铺账号 404（不是 403）", st == 404, res)
check("无角色 viewer → 403", req("GET", "/api/writeoff/%s" % hqo, token=vtok)[0] == 403
      and req("POST", "/api/writeoff/%s" % hqo, {"ziti_code": code_of(hqo)}, vtok)[0] == 403)
check("跨面一律 401：会员令牌打后台核销面、运营令牌打会员取码面",
      req("GET", "/api/writeoff/%s" % hqo, token=mtok)[0] == 401
      and req("POST", "/api/h5app/wxapp/order/%s/ziticode" % hqo, {"x": 1}, atok)[0] == 401)
audit = data("GET", "/api/operator/logs?pageSize=50", token=atok)["list"]
check("审计：本轮的写路径都留了痕（@Audited 的 route 就是权限码）",
      audit_count("pickup.update") > PL_LOG0 and audit_count("order.writeoff") > WO_LOG0,
      (PL_LOG0, audit_count("pickup.update"), WO_LOG0, audit_count("order.writeoff")))
check("审计行里能看到这条路由是哪个账号写的",
      any(x["route_name"] == "order.writeoff" for x in audit if "route_name" in x), [x.get("route_name") for x in audit][:8])

# ===== 12. 收口：本轮的点全部停用，回到 seed 态 =====
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": o2, "cancel_reason": "M1-4 冒烟收口"}, mtok)
check("未付款的自提单可以直接取消（它与物流单共用同一条状态机腿）",
      data("GET", "/api/orders/%s" % o2, token=atok)["orderInfo"]["order_status"] == "CANCEL", o2)
data("POST", "/api/writeoff/%s" % hqo, {"ziti_code": code_of(hqo)}, atok)
for pid in [x["id"] for x in data("GET", "/api/pickuplocation?keyword=%s&status=1" % RUN, token=atok)["list"]]:
    data("DELETE", "/api/pickuplocation/%s" % pid, token=atok)
check("收尾：本轮建的点全部停用，前台取点页只剩 seed 的两条通路",
      pl_count(keyword=RUN, status=1) == 0
      and RUN not in "".join(x["name"] for x in front_points(SHOP)), pl_count(keyword=RUN, status=1))
check("取消掉那张未付款单之后，它占着的平台通用点也停得下来了（闸门数的是未完结）",
      plat2["id"] not in [x["id"] for x in data("GET", "/api/pickuplocation?status=1", token=atok)["list"]])
check("seed 的两条演示点没被本轮动过",
      own["id"] in [x["id"] for x in data("GET", "/api/pickuplocation?status=1", token=atok)["list"]])
# 被拦的建单（未开自提 / 选错点）会把车内的行留在原地：不清就污染同一演示库上的兄弟脚本
cleared = clear_cart()
check("收尾：本轮留在车里的 %d 行清干净（不靠建单成功来清车）" % cleared, cart_rows() == [])

print("\n[%s] 通过 %d/%d" % (RUN, sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
