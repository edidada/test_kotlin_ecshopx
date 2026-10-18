import json
import time
import urllib.error
import urllib.parse
import urllib.request

"""M1-3 #57 实跑冒烟（秒杀 SK-A1..A7 / SK-F1..F2 + 拼团 GP-A1..A6 / GP-F1..F4 + 活动单算价）。

MockMvc 的 SeckillGroupsEndToEndTest（23 例）管"口径对不对"，本脚本管"起得来的服务对不对"：
权限码 27..37 是否真的进了缓存、演示库里 seed 的两条已结束活动会不会误入算价链、
定时器（关单 / 秒杀对账 / 拼团结算）在真实进程里会不会跟本案打架 —— 这些都是内存测试碰不到的。

覆盖：
 1. SK-A1/A6 建场次圈品：`limited_time_sale` 这种 17 字符的类型列值真能写进 MySQL/H2 列（VARCHAR 放宽的回归位）；
 2. SK-A4/A5 PATCH 语义与停用闸门：改时限不动限额，停用后前台列表、商详角标、算价链、下单四处一起关门；
 3. SK-A7 报表读三个池子数（冒烟不能连库，池子状态全靠它）：占 hold → 结清 hold→sales → 重放回调不双计 → 取消释放；
 4. 限购 2 件把"页面说还能买、提交被拒"的口子堵住（未付在途单一起算）；
 5. ORDER-1/2 活动单：活动价改的是单价基准（`activity_price_fee`），守恒式在真报价上成立，预览==下单；
 6. GP-A1..A5 + GP-F1..F4：开团占名额、参团、未成团不得发货、两人结清当场成团、发货放行；
 7. GP-A5 `end=true` 强制结束 → 进行中的团立刻失败，退款走 M1-1 链路（订单 `pay_status` 变 REFUND）；
 8. 权限面：viewer 缺 27..37 全部码 → 403，匿名前台 → 401 `Unauthenticated.`；
 9. 收口：本案场次与拼团停用，本脚本产生的待付款单全部取消，演示库回到"没有本案活动"的状态。

金额基准（`sql/seed.sql`）：商品 3 = 8900 分（模板 1 全国包邮）、商品 2 = 199900 分；
seed 自带的秒杀场次 1 与拼团活动 1 都是**已结束窗口**，本案自建活动全部用 "SG<时间戳>" 前命名，
每次开跑先停用上一轮残留，避免"前台列表只该有本案活动"这类断言被污染。
"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
VPW = "viewer" + "123"
VCODE = "8888"
MEMBER = "13800000001"
OTHER = "13800000002"
NUT = 3                       # 每日坚果礼盒 8900 分
PHONE = 2                     # 测试手机 X1 白色 199900 分
NUT_PRICE = 8900
SK_PRICE = 5900               # 秒杀价：让利 3000/件
SK_LIMIT = 2                  # 限购 2 件
GP_PRICE = 6900               # 拼团价：让利 2000/件

RUN = "SG%d" % int(time.time())
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
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except ValueError:
            return e.code, {}


def raw(path, form, content_type="application/x-www-form-urlencoded"):
    """渠道回调专用：form 报文，应答不是 data 包络。"""
    r = urllib.request.Request(BASE + path, data=urllib.parse.urlencode(form).encode("utf-8"), method="POST")
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


atok = login("/api/operator/login", {"username": "admin", "password": PW})
vtok = login("/api/operator/login", {"username": "viewer", "password": VPW})
mtok = login("/api/h5app/wxapp/new_login", {"auth_type": "local", "username": MEMBER,
                                            "check_type": "mobile", "vcode": VCODE})
otok = login("/api/h5app/wxapp/new_login", {"auth_type": "local", "username": OTHER,
                                            "check_type": "mobile", "vcode": VCODE})
check("四类令牌到位（管理员/只读/两个会员）", all([atok, vtok, mtok, otok]))

NOW = int(time.time())


def ts(off):
    return NOW + off


# 预清理：把上一轮残留的 SG* 活动停用（秒杀与拼团都没有删除口，停用就是关门）
for x in data("GET", "/api/seckill/activities?pageSize=100", token=atok)["list"]:
    if x["activity_name"].startswith("SG") and not x["disabled"]:
        data("PUT", "/api/seckill/activities/%s/status" % x["seckill_id"], {"disabled": True}, atok)
for x in data("GET", "/api/groups/activities?pageSize=100", token=atok)["list"]:
    if x["act_name"].startswith("SG") and not x["disabled"]:
        data("PUT", "/api/groups/activities/%s/status" % x["groups_activity_id"], {"disabled": True}, atok)

base_notpay = data("GET", "/api/h5app/wxapp/orders?order_status=notpay&page=1&pageSize=50", token=mtok)["total_count"]


def pairs(*specs):
    return [{"item_id": i, "num": n} for i, n in specs]


def preview(item, num, cls=None, act=None, tok=mtok):
    body = {"cart_type": "fastbuy", "items": pairs((item, num))}
    if cls:
        body["order_class"] = cls
        body["act_id"] = act
    return data("POST", "/api/h5app/wxapp/getFreightFee", body, tok)


def order_body(item, num, cls=None, act=None, team=None, tok=mtok):
    body = {"cart_type": "fastbuy", "order_type": "normal", "items": pairs((item, num)), "order_from": "h5"}
    if tok == mtok:
        # 会员 1 用地址簿（seed 有 address_id=1）；会员 2 没有地址行，正好走收货六件套那条腿
        body["address_id"] = 1
    else:
        body.update({"receiver_name": "冒烟二号", "receiver_mobile": OTHER, "receiver_state": "浙江省",
                     "receiver_city": "杭州市", "receiver_district": "西湖区", "receiver_address": "文三路 1 号"})
    if cls:
        body["order_class"] = cls
        body["act_id"] = act
    if team:
        body["team_id"] = team
    return body


def order(item, num, cls=None, act=None, team=None, tok=mtok):
    return data("POST", "/api/h5app/wxapp/order_new", order_body(item, num, cls, act, team, tok), tok)


def try_order(item, num, cls=None, act=None, team=None, tok=mtok):
    """该被拒的单：把状态码与错误体交回断言，不在这里炸"""
    return req("POST", "/api/h5app/wxapp/order_new", order_body(item, num, cls, act, team, tok), tok)


def pay(oid, tok=mtok):
    t = data("POST", "/api/h5app/wxapp/payment", {"order_id": oid, "pay_type": "wxpay"}, tok)
    st, body = raw("/api/notify/wxpay", {"out_trade_no": t["trade_id"], "total_fee": str(t["total_fee"]),
                                         "transaction_id": "WX-" + t["trade_id"][-6:], "trade_state": "SUCCESS"})
    assert st == 200 and "SUCCESS" in body, (st, body)
    return t["trade_id"]


def replay(trade_id, fee):
    return raw("/api/notify/wxpay", {"out_trade_no": trade_id, "total_fee": str(fee),
                                     "transaction_id": "WX-" + trade_id[-6:], "trade_state": "SUCCESS"})


def cancel(oid, tok=mtok):
    return data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": oid, "cancel_reason": "M1-3 #57 冒烟收口"}, tok)


def report(sk):
    return data("GET", "/api/seckill/activities/%s/report" % sk, token=atok)


def row_of(items, item):
    return [x for x in items if x["item_id"] == item][0]


# ===== 1. SK-A1 建场次：整数码与列值两种写法都收 =========================

sk = data("POST", "/api/seckill/activities", {
    "activity_name": "%s 秒杀主战场" % RUN, "activity_type": 1, "ad_pic": "/img/sk.jpg",
    "distributor_id": "1", "activity_start_time": ts(-3600), "activity_end_time": ts(86400),
    "validity_period": 10, "limit_money": 0,
}, atok)
check("SK-A1 建场次即返回完整行（date_status=doing）",
      sk["date_status"] == "doing" and sk["activity_type"] == 1 and sk["seckill_type"] == "normal"
      and sk["seckill_type_text"] == "普通秒杀", sk)
skid = sk["seckill_id"]

ltd = data("POST", "/api/seckill/activities", {
    "activity_name": "%s 限时抢购" % RUN, "seckill_type": "limited_time_sale",
    "activity_start_time": ts(-3600), "activity_end_time": ts(86400), "validity_period": 10,
}, atok)
check("类型列值 17 个字符写得进去（seckill_type 已放宽到 VARCHAR(32)）",
      ltd["seckill_type"] == "limited_time_sale" and ltd["activity_type"] == 2
      and ltd["seckill_type_text"] == "限时抢购", ltd)

st, res = req("POST", "/api/seckill/activities", {"activity_name": "%s 窗口反了" % RUN,
                                                 "activity_start_time": ts(3600), "activity_end_time": ts(-100)}, atok)
check("SK-A1 窗口反了 → 400 且报错落在列上",
      st == 400 and res.get("errors", {}).get("activity_end_time"), (st, res))
st, res = req("POST", "/api/seckill/activities", {"activity_name": "%s 不认识类型" % RUN, "activity_type": 7,
                                                 "activity_start_time": ts(-3600), "activity_end_time": ts(3600)}, atok)
check("整数码只认 1/2，别的类型不再静默降级成普通秒杀",
      st == 400 and "只支持" in str(res.get("errors", {}).get("seckill_type")), (st, res))
st, res = req("POST", "/api/seckill/activities", {"activity_name": "%s 时限越界" % RUN, "validity_period": 1441,
                                                 "activity_start_time": ts(-3600), "activity_end_time": ts(3600)}, atok)
check("支付时限 0..1440 分钟（D24：auto_cancel_time 就是它，不给 Redis 资格票）", st == 400, (st, res))

# ===== 2. SK-A6 圈品定价：库存只加不减 ==================================

saved = data("PUT", "/api/seckill/activities/%s/goods" % skid, {"items": [
    {"item_id": NUT, "item_name": "坚果", "activity_price": SK_PRICE, "activity_store": 10, "limit_num": SK_LIMIT, "is_show": True},
    {"item_id": PHONE, "item_name": "手机", "activity_price": 179900, "activity_store": 2, "is_show": True},
]}, atok)
nut = row_of(saved["items"], NUT)
check("SK-A6 商品行三列齐（available = 总 − 已售 − 占用）",
      nut["activity_store"] == 10 and nut["sales_store"] == 0 and nut["hold_store"] == 0
      and nut["available"] == 10 and nut["item_title"] == "每日坚果礼盒", nut)
plus = data("PUT", "/api/seckill/activities/%s/goods" % skid, {"items": [
    {"item_id": NUT, "activity_price": SK_PRICE, "activity_store": 5, "limit_num": SK_LIMIT},
]}, atok)
check("重复提交同一 SKU 是覆盖 + 加量，没提交的那行原样留着（整表覆盖不是差量删除）",
      row_of(plus["items"], NUT)["activity_store"] == 15 and row_of(plus["items"], PHONE)["activity_store"] == 2
      and len(plus["items"]) == 2, plus["items"])
st, res = req("PUT", "/api/seckill/activities/%s/goods" % skid, {"items": [
    {"item_id": NUT, "activity_price": SK_PRICE, "activity_store": -5}]}, atok)
check("秒杀库存只能加不能减 → 422 业务拒", st == 422 and "只能加不能减" in res.get("message", ""), (st, res))
st, res = req("PUT", "/api/seckill/activities/%s/goods" % skid, {"items": []}, atok)
check("空圈品 → 400 表单错（不是 422）", st == 400 and res.get("errors", {}).get("items"), (st, res))

# ===== 3. SK-F1/F2 + MK-F3 前台面 ========================================

front = data("GET", "/api/h5app/wxapp/seckill/activities?pageSize=100", token=mtok)
fids = [x["seckill_id"] for x in front["list"]]
check("SK-F1 停用不出、未开始/已结束照出（秒杀页 tab 靠 date_status 分，与 GP-F1 默认只看在窗不同）",
      skid in fids and 1 in fids
      and [x for x in front["list"] if x["seckill_id"] == 1][0]["date_status"] == "end"
      and [x for x in front["list"] if x["seckill_id"] == skid][0]["items_count"] == 2,
      [(x["seckill_id"], x["date_status"]) for x in front["list"]])
fitems = data("GET", "/api/h5app/wxapp/seckill/activities/%s/items" % skid, token=mtok)["items"]
fnut = row_of(fitems, NUT)
check("SK-F2 商品行给足下单坐标（activity_type/activity_id）",
      fnut["activity_price"] == SK_PRICE and fnut["origin_price"] == NUT_PRICE and fnut["market_price"] == 9900
      and fnut["available"] == 15 and fnut["remain_for_me"] == SK_LIMIT and fnut["bought_by_me"] == 0
      and fnut["activity_type"] == "seckill" and fnut["activity_id"] == skid, fnut)
badge = data("GET", "/api/h5app/wxapp/items/%d/promotions" % NUT, token=mtok)
check("MK-F3 的 seckill 键位补齐（此前占位 null）",
      badge["seckill"] and badge["seckill"]["seckill_id"] == skid
      and badge["seckill"]["activity_price"] == SK_PRICE, badge.get("seckill"))
st, res = req("GET", "/api/h5app/wxapp/items/%d/promotions" % PHONE, token=mtok)
check("商品 2 也进角标（同一商品多场次取在窗最低活动价）",
      res.get("data", {}).get("seckill", {}).get("seckill_id") == skid, (st, res.get("data")))

# ===== 4. ORDER-1 预览：改的是单价基准，不是折让 =========================

plain = preview(NUT, 2)
check("没申报活动的行按现价（库里存在场次也不变价）",
      plain["item_fee"] == NUT_PRICE * 2 and plain["activity_price_fee"] == 0, plain["item_fee"])
p = preview(NUT, 2, "seckill", skid)
check("秒杀预览：行基准换成活动价，让利进 activity_price_fee",
      p["item_fee"] == SK_PRICE * 2 and p["activity_price_fee"] == (NUT_PRICE - SK_PRICE) * 2
      and p["discount_fee"] == 0 and p["total_fee"] == SK_PRICE * 2, p)
check("守恒式 Σ行应付 + 运费 == 单应付（活动让利不进 discount_fee）",
      sum(l["total_fee"] for l in p["items"]) + p["freight_fee"] == p["total_fee"], p["items"])
check("折让构成第一条就是秒杀（限时秒杀，现价 89 元，活动价 59 元）",
      p["discount_info"][0]["type"] == "seckill" and "限时秒杀" in p["discount_info"][0]["rule"]
      and p["discount_info"][0]["discount_fee"] == 6000, p["discount_info"])
check("错报类目归一成 normal，不打断结算",
      preview(NUT, 2, "bargain", skid)["item_fee"] == NUT_PRICE * 2)

# ===== 5. ORDER-2 占池 → 结清 hold→sales → 重放不双计 ===================

o = order(NUT, 2, "seckill", skid)
oid = o["order_id"]
check("下单重算与预览逐分一致，池子只占 hold_store",
      o["total_fee"] == SK_PRICE * 2 and report(skid)["hold_store"] == 2
      and report(skid)["sales_store"] == 0, report(skid))
act_ = data("GET", "/api/h5app/wxapp/order/%s" % oid, token=mtok)["orderInfo"]["auto_cancel_time"]
check("auto_cancel_time 落在建单 + 10 分钟这一档（D24：用它代替 Redis 资格票）",
      int(time.time()) - 60 <= act_ <= NOW + 700, act_)
tr = pay(oid)
r = report(skid)
check("结清：占用转已售，同一语句一起动两个数（available_store 是**全场次求和**，单行看 items[]）",
      r["hold_store"] == 0 and r["sales_store"] == 2
      and row_of(r["items"], NUT)["available"] == 13 and r["available_store"] == 15, r)
check("已成交额只按已售计（占用的钱不算业绩）", r["sales_fee"] == SK_PRICE * 2 and r["hold_fee"] == 0, r)
st, body = replay(tr, SK_PRICE * 2)
r = report(skid)
check("渠道重放同一条回调：销量不双计（settle 的 CAS 守卫）",
      st == 200 and "SUCCESS" in body and r["sales_store"] == 2 and r["hold_store"] == 0, (st, body, r))
f2 = row_of(data("GET", "/api/h5app/wxapp/seckill/activities/%s/items" % skid, token=mtok)["items"], NUT)
check("限购把已付与在途一起算：remain_for_me 只剩 0",
      f2["bought_by_me"] == SK_LIMIT and f2["remain_for_me"] == 0, f2)
st, res = try_order(NUT, 1, "seckill", skid)
check("第 3 件被限购挡住：页面余量与判据同一个数",
      st == 422 and "限购 2 件" in res.get("message", "") and "已使用 2" in res.get("message", ""), (st, res))
check("被拒的单没占池子", report(skid)["hold_store"] == 0, report(skid))

o2 = order(PHONE, 1, "seckill", skid)
check("普通单不占秒杀池、秒杀单占对应商品行",
      report(skid)["hold_store"] == 1 and row_of(report(skid)["items"], PHONE)["hold_store"] == 1, report(skid))
cancel(o2["order_id"])
check("取消即释放占用（关单腿）", report(skid)["hold_store"] == 0, report(skid))

# ===== 6. SK-A4 PATCH 语义 + SK-A5 停用闸门 ==============================

upd = data("PUT", "/api/seckill/activities/%s" % skid, {"validity_period": 20}, atok)
check("SK-A4 是 PATCH：改时限不抹限额也不动商品行",
      upd["validity_period"] == 20 and upd["limit_money"] == 0 and upd["item_count"] == 2, upd)
o3 = order(PHONE, 1, "seckill", skid)
check("改完之后新单按新时限（20 分钟 = 1200 秒）",
      data("GET", "/api/h5app/wxapp/order/%s" % o3["order_id"], token=mtok)["orderInfo"]["auto_cancel_time"]
      - int(time.time()) >= 600, o3)
cancel(o3["order_id"])

data("PUT", "/api/seckill/activities/%s/status" % skid, {"disabled": True}, atok)
check("停用后前台列表与商详角标一起关门（本商品再无在窗场次 → seckill 键位为 null）",
      skid not in [x["seckill_id"] for x in data("GET", "/api/h5app/wxapp/seckill/activities?pageSize=100", token=mtok)["list"]]
      and data("GET", "/api/h5app/wxapp/items/%d/promotions" % NUT, token=mtok).get("seckill") is None,
      data("GET", "/api/h5app/wxapp/items/%d/promotions" % NUT, token=mtok).get("seckill"))
check("停用场次不改单价基准（算价链按 disabled 过滤）",
      preview(NUT, 2, "seckill", skid)["item_fee"] == NUT_PRICE * 2, preview(NUT, 2, "seckill", skid))
st, res = try_order(PHONE, 1, "seckill", skid)
check("停用场次下单直接被拒（绝不按活动价下一笔普通单）",
      st == 422 and "不在此场次" in res.get("message", ""), (st, res))
st, res = req("PUT", "/api/seckill/activities/%s/status" % skid, {}, atok)
check("SK-A5 不给 disabled → 400", st == 400, (st, res))

# ===== 7. GP-A1 建拼团 + 配置校验 =======================================

gp = data("POST", "/api/groups/activities", {
    "act_name": "%s 双人拼团" % RUN, "goods_id": NUT, "act_price": GP_PRICE, "person_num": 2,
    "store": 5, "limit_buy_num": 0, "limit_time": 24, "begin_time": ts(-3600), "end_time": ts(86400),
    "robot": False, "instruction": "冒烟拼团说明",
}, atok)
gpid = gp["groups_activity_id"]
check("GP-A1 建活动带出商品名与现价，origin_price 取商品表",
      gp["goods_name"] == "每日坚果礼盒" and gp["origin_price"] == NUT_PRICE and gp["act_price"] == GP_PRICE
      and gp["date_status"] == "doing" and gp["store"] == 5, gp)
st, res = req("POST", "/api/groups/activities", {"act_name": "%s 价不低于现价" % RUN, "goods_id": NUT,
                                                "act_price": NUT_PRICE, "person_num": 2, "store": 1,
                                                "begin_time": ts(-3600), "end_time": ts(3600)}, atok)
check("拼团价必须低于商品现价（400 落列报错）",
      st == 400 and str(res.get("errors", {}).get("act_price")).find("必须低于") >= 0, (st, res))
st, res = req("POST", "/api/groups/activities", {"act_name": "%s 一人团" % RUN, "goods_id": NUT, "act_price": 100,
                                                "person_num": 1, "store": 1, "begin_time": ts(-3600),
                                                "end_time": ts(3600)}, atok)
check("成团人数 2..1000", st == 400, (st, res))
st, res = req("PUT", "/api/groups/activities/%s" % gpid, {"store": 1}, atok)
check("拼团额度只能加不能减 → 422", st == 422 and "只能加不能减" in res.get("message", ""), (st, res))

# ===== 8. GP-F1/F2 前台面 + ORDER-1 拼团预览 ============================

ga = data("GET", "/api/h5app/wxapp/groups/activities?pageSize=100", token=mtok)["list"]
check("GP-F1 前台列表不出停用位、坐标给全",
      gpid in [x["groups_activity_id"] for x in ga] and 1 not in [x["groups_activity_id"] for x in ga]
      and [x for x in ga if x["groups_activity_id"] == gpid][0]["activity_type"] == "groups", ga[:2])
gd = data("GET", "/api/h5app/wxapp/groups/activities/%s" % gpid, token=mtok)
check("GP-F2 详情带 teams[] 与说明", gd["teams"] == [] and gd["instruction"] == "冒烟拼团说明"
      and gd["person_num"] == 2, gd)
gp_prev = preview(NUT, 1, "groups", gpid)
check("拼团预览：行基准换成拼团价，让利 2000 记在活动价字段",
      gp_prev["item_fee"] == GP_PRICE and gp_prev["activity_price_fee"] == NUT_PRICE - GP_PRICE, gp_prev)
gold = preview(NUT, 1, "groups", gpid, tok=otok)
check("等级折打在拼团价之后（金卡 95%：6900×5%%=345）",
      gold["member_discount"] == 345 and gold["total_fee"] == GP_PRICE - 345
      and gold["activity_price_fee"] == NUT_PRICE - GP_PRICE, gold)

# ===== 9. 开团 → 参团 → 未成团不得发货 → 结清成团 =======================

go = order(NUT, 1, "groups", gpid)
team = go["team_id"]
oidA = go["order_id"]
check("ORDER-2 开团返回 GT 团号（D29 号段）且单头落 team_id",
      team.startswith("GT") and data("GET", "/api/h5app/wxapp/order/%s" % oidA, token=mtok)["orderInfo"]["team_id"] == team,
      go)
t1 = data("GET", "/api/h5app/wxapp/groups/teams/%s" % team, token=mtok)
check("GP-F3 团详情：1 人缺 1 人，团长昵称只出掩码",
      t1["join_person_num"] == 1 and t1["lack_num"] == 1 and t1["members"][0]["nickname"].endswith("0001")
      and t1["members"][0]["is_head"] and not t1["members"][0]["paid"], t1)
check("开团即扣活动额度（5 → 4）", data("GET", "/api/groups/activities/%s" % gpid, token=atok)["store"] == 4,
      data("GET", "/api/groups/activities/%s" % gpid, token=atok))
st, res = try_order(NUT, 1, "groups", gpid)
check("同一活动只允许一笔待成团单", st == 422 and "未付款的参团订单" in res.get("message", ""), (st, res))
st, res = try_order(NUT, 2, "groups", gpid, tok=otok)
check("拼团一次只能拍 1 件（一件坐一个席位）", st == 422 and "只能拍 1 件" in res.get("message", ""), (st, res))

pay(oidA)
check("结清后成员置为已付，但仍未成团",
      data("GET", "/api/h5app/wxapp/groups/teams/%s" % team, token=mtok)["members"][0]["paid"]
      and data("GET", "/api/h5app/wxapp/groups/teams/%s" % team, token=mtok)["team_status"] == 1)
st, res = req("PUT", "/api/orders/delivery", {"order_id": int(oidA), "delivery_corp": "zto", "delivery_code": RUN}, atok)
check("未成团不得发货（§14.6 末行的卡口，订单状态本身不新增）",
      st == 422 and "尚未成团" in res.get("message", ""), (st, res))

gb = order(NUT, 1, "groups", gpid, team=team, tok=otok)
check("参团回到同一个团号，席位占到 2、额度扣到 3",
      gb["team_id"] == team and data("GET", "/api/h5app/wxapp/groups/teams/%s" % team, token=mtok)["join_person_num"] == 2
      and data("GET", "/api/groups/activities/%s" % gpid, token=atok)["store"] == 3, gb)
pay(gb["order_id"], otok)
t2 = data("GET", "/api/h5app/wxapp/groups/teams/%s" % team, token=mtok)
check("两人结清 → 当场成团（team_status=2，lack 归 0）",
      t2["team_status"] == 2 and t2["team_status_text"] == "已成团" and t2["lack_num"] == 0, t2)
st, res = req("PUT", "/api/orders/delivery", {"order_id": int(oidA), "delivery_corp": "zto", "delivery_code": RUN}, atok)
check("成团后发货放行", st == 200, (st, res))
mine = data("GET", "/api/h5app/wxapp/groups/my/teams", token=otok)["teams"]
check("GP-F4 我的团看得见同一个团",
      team in [x["team_id"] for x in mine] and [x for x in mine if x["team_id"] == team][0]["team_status"] == 2, mine)
st, res = try_order(NUT, 1, "groups", gpid, team)
check("已成团的团号不能再进", st == 422 and "该团已成团" in res.get("message", ""), (st, res))
teams = data("GET", "/api/groups/teams?act_id=%s&team_status=2&pageSize=50" % gpid, token=atok)
check("GP-A6 后台团列表按状态筛", team in [x["team_id"] for x in teams["list"]], teams["list"][:2])

# ===== 10. 强制结束：进行中的团立刻失败并走 M1-1 退款 ===================

gc = order(NUT, 1, "groups", gpid, tok=otok)
oidC, teamC = gc["order_id"], gc["team_id"]
pay(oidC, otok)
ended = data("PUT", "/api/groups/activities/%s/status" % gpid, {"end": True}, atok)
check("GP-A5 end=true 只砸进行中的团（已成团不动）",
      ended["failed_teams"] == 1 and ended["disabled"] is True, ended)
check("失败团的钱走 M1-1 退款链（订单 pay_status 变 REFUND）",
      data("GET", "/api/h5app/wxapp/order/%s" % oidC, token=otok)["orderInfo"]["pay_status"] == "REFUND",
      data("GET", "/api/h5app/wxapp/order/%s" % oidC, token=otok)["orderInfo"])
check("已成团的两席仍占额度（5 → 3），失败那一席已回补",
      data("GET", "/api/groups/activities/%s" % gpid, token=atok)["store"] == 3,
      data("GET", "/api/groups/activities/%s" % gpid, token=atok))
check("强制结束后没有进行中的团",
      data("GET", "/api/groups/teams?act_id=%s&team_status=1&pageSize=50" % gpid, token=atok)["total_count"] == 0)
st, res = try_order(NUT, 1, "groups", gpid)
check("停用/结束后前台不再给活动价，下单直接拒",
      st == 422 and "不在此场次" in res.get("message", ""), (st, res))

# ===== 11. 权限面与收口 =================================================

for path in ["/api/seckill/activities", "/api/groups/activities", "/api/groups/teams"]:
    st, res = req("GET", path + "?pageSize=10", token=vtok)
    check("viewer 无 %s 权限码 → 403" % path, st == 403, (st, res))
st, res = req("POST", "/api/seckill/activities", {"activity_name": "%s 越权" % RUN, "activity_start_time": ts(-60),
                                                 "activity_end_time": ts(3600)}, vtok)
check("viewer 建秒杀场次 → 403", st == 403, (st, res))
st, res = req("PUT", "/api/groups/activities/%s/status" % gpid, {"disabled": True}, vtok)
check("viewer 停拼团 → 403", st == 403, (st, res))
st, res = req("GET", "/api/h5app/wxapp/groups/my/teams")
check("前台面未鉴权 → 401 Unauthenticated.", st == 401 and res.get("message") == "Unauthenticated.", (st, res))

# 限时抢购场次全程没下单（只验类型列），收口一样要关门 —— 秒杀没有删除口，停用即归档
data("PUT", "/api/seckill/activities/%s/status" % ltd["seckill_id"], {"disabled": True}, atok)
left = data("GET", "/api/h5app/wxapp/orders?order_status=notpay&page=1&pageSize=50", token=mtok)["total_count"]
check("本脚本没给会员多留待付款残单（基线 %d）" % base_notpay, left == base_notpay, (base_notpay, left))
check("收尾：本案活动全部停用，seed 的已结束活动仍不在窗",
      all(x["disabled"] for x in data("GET", "/api/seckill/activities?pageSize=100", token=atok)["list"]
          if x["activity_name"].startswith("SG"))
      and preview(NUT, 2)["discount_fee"] == 0, preview(NUT, 2))

print("\n[%s] 通过 %d/%d" % (RUN, sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
