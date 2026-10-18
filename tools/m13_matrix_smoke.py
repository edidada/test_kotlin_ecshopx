import json
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

"""M1-3 #58 实跑冒烟（优惠叠加矩阵 + 拼团并发成团/超员 + 秒杀并发抢池）。

MockMvc 的两个门槛用例（`DiscountOverlayMatrixTest` 5 例 / `GroupsSeckillConcurrencyTest` 6 例）
管"口径对不对"，本脚本管"起得来的服务对不对"：真 fat jar 上的 `sys_config` 比例、演示库里
两个会员的真实积分余额与券实例、H2 文件库在真线程下的 CAS 行为，都是内存测试碰不到的东西。

覆盖：
 1. 五档开关全部到位（活动价/会员折/满减/券/积分），会员折用**换会员**切（13800000001 普通 100 折、
    13800000002 金卡 95 折），因为本项目没有"改会员等级"的端点（等级运营属 M2）；
 2. 32 格叠加矩阵（2 会员 × 满减 × 活动价 × 券 × 积分）逐格比一个**独立写的算术模型**，
    逐级基数、四个账、`discount_info[]` 序列、D28 守恒式一次判完；
 3. 券的门槛基数可证：金卡+满减把应付压到 138.15 元（140 元门外），只关掉满减这一档，同一张券立刻能用；
 4. 拒券文案的"当前金额"必须报**满减之后**的钱，不许被积分抵扣污染（本轮实测修的展示层口径）；
 5. 大额券抵到 0 且不越界：面额 300 元只抵 138.15，积分因此一分不动；
 6. 四叠加下单：落库金额 == 预览，快照四行，券 LOCKED、积分即时扣、秒杀按件占池，取消整条回退；
 7. 并发超员：1 人开团 + 8 人并发挤 2 人团 → 恰好 1 人进团，`join_person_num` 不越过 2，
    抢输的 7 笔把活动额度原样还回来；
 8. 并发抢额度/抢池：额度 3 只成 3 个团；池子 3 件只成 3 单，并发取消后可购量回满；
 9. 权限面与收口：viewer 无营销域权限码 → 403，匿名 → 401 `Unauthenticated.`，
    本案场次停用、满减删除、残单取消，演示库回到 seed 态。

金额基准（`sql/seed.sql`）：商品 3 = 8900 分（模板 1 全国包邮），本案秒杀价 5900 分；
积分比例 `point.deduct.max_ratio = 50`，会员 1 余额 1000、会员 2 余额 500 —— 脚本按**实时余额**算 oracle，
不假设余额没被别的脚本动过（与 D21、§15.3 同一口径）。
"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
VPW = "viewer" + "123"
VCODE = "8888"
PLAIN = "13800000001"          # user 1：普通会员（discount=100）
GOLD = "13800000002"           # user 2：金卡会员（discount=95 → 折让 5%）
LATE = ["1380000001%d" % i for i in range(1, 9)]   # 并发段临时注册的新会员

ITEM = 3
UNIT = 8900                    # 商品现价
NUM = 3
SK_PRICE = 5900                # 活动价：每件让利 3000
GRADE_RATE = 5                 # 金卡 95 折 → 折让 5%
MK_FULL = 15000                # 满 150 元
MK_MINUS = 3000                # 减 30 元
CP_MIN = 14000                 # 券门槛 140 元
CP_FEE = 2000                  # 券面额 20 元
BIG_FEE = 30000                # 无门槛 300 元：专门用来抵到 0
GP_PRICE = 6900                # 拼团价

RUN = "MT%d" % int(time.time())
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
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except ValueError:
            return e.code, {}


def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else " -> " + str(extra)[:400]))


def data(method, path, body=None, token=None):
    st, res = req(method, path, body, token)
    assert st == 200, "%s %s -> %s %s" % (method, path, st, res)
    return res["data"]


def login(mobile):
    return data("POST", "/api/h5app/wxapp/new_login",
                {"auth_type": "local", "username": mobile, "check_type": "mobile", "vcode": VCODE})["token"]


def yuan(fen):
    s = ("%.2f" % (fen / 100.0)).rstrip("0").rstrip(".")
    return s


# ===================== 1. 五档开关 =====================

atok = data("POST", "/api/operator/login", {"username": "admin", "password": PW})["token"]
vtok = data("POST", "/api/operator/login", {"username": "viewer", "password": VPW})["token"]
ptok = login(PLAIN)
gtok = login(GOLD)
latoks = [login(m) for m in LATE]
check("令牌到位（后台/只读/两个演示会员/8 个并发会员）", all([atok, vtok, ptok, gtok] + latoks))

NOW = int(time.time())


def ts(off):
    return NOW + off


# 预清理：把上一轮残留的 MT* 场次与拼团停用、满减删掉，矩阵才不会被历史活动污染
for x in data("GET", "/api/seckill/activities?pageSize=100", token=atok)["list"]:
    if x["activity_name"].startswith("MT") and not x["disabled"]:
        data("PUT", "/api/seckill/activities/%s/status" % x["seckill_id"], {"disabled": True}, atok)
for x in data("GET", "/api/groups/activities?pageSize=100", token=atok)["list"]:
    if x["act_name"].startswith("MT") and not x["disabled"]:
        data("PUT", "/api/groups/activities/%s/status" % x["groups_activity_id"], {"disabled": True}, atok)
for x in data("GET", "/api/marketing/activities?pageSize=100", token=atok)["list"]:
    if x["marketing_name"].startswith("MT"):
        req("DELETE", "/api/marketing/activities/%s" % x["marketing_id"], None, atok)

sk = data("POST", "/api/seckill/activities", {
    "activity_name": "%s 矩阵秒杀场" % RUN, "activity_type": 1, "ad_pic": "/img/s.jpg",
    "distributor_id": "1", "activity_start_time": ts(-3600), "activity_end_time": ts(86400),
    "validity_period": 10, "limit_money": 0}, atok)["seckill_id"]
data("PUT", "/api/seckill/activities/%s/goods" % sk, {"items": [
    {"item_id": ITEM, "item_name": "矩阵圈品", "activity_price": SK_PRICE,
     "activity_store": 60, "limit_num": 0, "is_show": True}]}, atok)

cp_small = data("POST", "/api/coupons", {
    "coupon_type": "cash", "coupon_name": "%s 满140减20" % RUN, "discount_fee": CP_FEE,
    "min_order_fee": CP_MIN, "total_num": 30, "valid_type": "range",
    "begin_time": ts(-3600), "end_time": ts(86400), "get_limit": 1}, atok)["coupon_id"]
cp_big = data("POST", "/api/coupons", {
    "coupon_type": "cash", "coupon_name": "%s 无门槛减300" % RUN, "discount_fee": BIG_FEE,
    "min_order_fee": 0, "total_num": 30, "valid_type": "range",
    "begin_time": ts(-3600), "end_time": ts(86400), "get_limit": 1}, atok)["coupon_id"]

CODE = {ptok: data("POST", "/api/h5app/wxapp/coupons/receive", {"coupon_id": cp_small}, ptok)["coupon_code"],
        gtok: data("POST", "/api/h5app/wxapp/coupons/receive", {"coupon_id": cp_small}, gtok)["coupon_code"]}
BIG = {ptok: data("POST", "/api/h5app/wxapp/coupons/receive", {"coupon_id": cp_big}, ptok)["coupon_code"]}
check("两张券模板建好、两个会员各自领到（券归属本人才能进算价链）",
      all(CODE.values()) and all(BIG.values()) and CODE[ptok] != CODE[gtok], (CODE, BIG))

POINT0 = {ptok: data("GET", "/api/h5app/wxapp/member", token=ptok)["point"],
          gtok: data("GET", "/api/h5app/wxapp/member", token=gtok)["point"]}
check("两个会员的积分余额按实时读数进 oracle（普通 %d / 金卡 %d）" % (POINT0[ptok], POINT0[gtok]),
      POINT0[ptok] >= 0 and POINT0[gtok] >= 0)


def pairs(*specs):
    return [{"item_id": i, "num": n} for i, n in specs]


def pbody(act, coupon, point, num=NUM, item=ITEM, tok=ptok):
    body = {"cart_type": "fastbuy", "items": pairs((item, num)), "point_use": bool(point)}
    if act:
        body["order_class"] = "seckill"
        body["act_id"] = sk
    if coupon:
        body["coupon_code"] = CODE[tok]
    return body


def preview(act, coupon, point, tok=ptok, num=NUM):
    return data("POST", "/api/h5app/wxapp/getFreightFee", pbody(act, coupon, point, num=num, tok=tok), tok)


base = preview(False, False, False)
check("基线一格不减：3 × 8900 = %d，且没场次在场时 activity_price_fee 恒 0" % (UNIT * NUM),
      base["total_fee"] == UNIT * NUM and base["discount_fee"] == 0 and base["activity_price_fee"] == 0, base)
check("积分比例来自 sys_config（limit_point=50），不勾开关就不给上限位",
      preview(False, False, True)["limit_point"] == 50 and preview(False, False, False)["limit_point"] == 0,
      (preview(False, False, True)["limit_point"], preview(False, False, False)["limit_point"]))


def enable_mk():
    mid = data("POST", "/api/marketing/activities", {
        "marketing_type": "full_minus", "marketing_name": "%s 矩阵满150减30" % RUN, "ad_pic": "/img/m.jpg",
        "start_time": ts(-3600), "end_time": ts(86400), "use_bound": 0, "condition_type": "totalfee",
        "condition_value": [{"full": "150.00", "minus": "30.00"}],
        "canjoin_repeat": False, "join_limit": 0, "free_postage": False,
        "promotion_tag": "满减", "status": True}, atok)["marketing_id"]
    data("PUT", "/api/marketing/activities/%s/check" % mid, {"check_status": "reviewed"}, atok)
    return mid


def disable_mk(mid):
    if mid:
        req("DELETE", "/api/marketing/activities/%s" % mid, None, atok)
    return 0


# ===================== 2. 独立算术模型（§14.5 手写，不复用责任链） =====================

def oracle(gold, act, mk, coupon, point, balance):
    unit = SK_PRICE if act else UNIT
    item_fee = unit * NUM
    activity = (UNIT - SK_PRICE) * NUM if act else 0
    member = item_fee * GRADE_RATE // 100 if gold else 0
    mk_base = item_fee - member
    minus = MK_MINUS if (mk and mk_base >= MK_FULL) else 0
    cp_base = mk_base - minus
    cp = min(CP_FEE, cp_base) if (coupon and cp_base >= CP_MIN) else 0
    p_base = cp_base - cp
    cap = p_base * 50 // 100
    p = min(balance, cap) if point else 0
    types = [t for t, on in [("seckill", act), ("full_minus", minus > 0),
                             ("coupon", cp > 0), ("point", p > 0)] if on]
    return {"unit": unit, "item_fee": item_fee, "activity": activity, "member": member,
            "mk": minus, "cp_base": cp_base, "cp": cp, "cap": (min(balance, cap) if point else 0),
            "point": p, "total": p_base - p, "types": types}


def assert_cell(p, gold, act, mk, coupon, point, tag):
    e = oracle(gold, act, mk, coupon, point, POINT0[gtok] if gold else POINT0[ptok])
    line = p["items"][0]
    bad = []
    if line["price"] != e["unit"]:
        bad.append("单价基准 %s != %s" % (line["price"], e["unit"]))
    if line["origin_price"] != UNIT:
        bad.append("现价位 %s != %s" % (line["origin_price"], UNIT))
    if line["item_fee"] != e["item_fee"]:
        bad.append("行小计 %s != %s" % (line["item_fee"], e["item_fee"]))
    if line["activity_type"] != ("seckill" if act else "normal"):
        bad.append("行活动类目 %s" % line["activity_type"])
    if p["freight_fee"] != 0:
        bad.append("模板 1 应包邮，实得 %s" % p["freight_fee"])
    for k, want in [("discount_fee", e["member"] + e["mk"]), ("member_discount", e["member"]),
                    ("goods_discount", e["mk"]), ("activity_price_fee", e["activity"]),
                    ("coupon_discount", e["cp"]), ("point_fee", e["point"]),
                    ("max_point", e["cap"]), ("total_fee", e["total"])]:
        if p[k] != want:
            bad.append("%s 实得 %s != 期望 %s" % (k, p[k], want))
    if [x["type"] for x in p["discount_info"]] != e["types"]:
        bad.append("discount_info 序列 %s != %s" % ([x["type"] for x in p["discount_info"]], e["types"]))
    if sum(x["discount_fee"] for x in p["discount_info"]) != e["activity"] + e["mk"] + e["cp"] + e["point"]:
        bad.append("discount_info 合计 %s" % sum(x["discount_fee"] for x in p["discount_info"]))
    # 守恒式（D28）：Σ行应付 + 运费 == 单应付，三类抵扣逐行守恒，行应付不为负
    lines = p["items"]
    if sum(x["total_fee"] for x in lines) != p["total_fee"] - p["freight_fee"]:
        bad.append("Σ行应付 %s != 单应付 %s" % (sum(x["total_fee"] for x in lines), p["total_fee"]))
    for k, row_k in [("discount_fee", "discount_fee"), ("coupon_discount", "coupon_fee"), ("point_fee", "point_fee")]:
        if sum(x[row_k] for x in lines) != p[k]:
            bad.append("%s 未逐行守恒（行合计 %s，单级 %s）" % (k, sum(x[row_k] for x in lines), p[k]))
    if any(x["total_fee"] < 0 for x in lines):
        bad.append("出现负应付行")
    # 被门槛挡下时：文案里的"当前金额"必须是满减之后的应付
    if coupon and e["cp"] == 0:
        r = p.get("coupon_reason") or ""
        if "满 140 元可用" not in r or yuan(e["cp_base"]) not in r:
            bad.append("拒券文案 %s 未报出满减之后的基数 %s" % (r, yuan(e["cp_base"])))
        if p.get("coupon_info"):
            bad.append("抵 0 还编券行")
    elif p.get("coupon_reason"):
        bad.append("没有被拒的券却留了原因 %s" % p["coupon_reason"])
    check("矩阵 %s" % tag, not bad, bad)


cells = 0
for mk_on in (False, True):
    mid = enable_mk() if mk_on else 0
    for gold in (False, True):
        tok = gtok if gold else ptok
        for act in (False, True):
            for coupon in (False, True):
                for point in (False, True):
                    assert_cell(preview(act, coupon, point, tok), gold, act, mk_on, coupon, point,
                                "满减=%d 会员=%s 活动=%d 券=%d 积分=%d" % (mk_on, "金卡" if gold else "普通",
                                                                            act, coupon, point))
                    cells += 1
    disable_mk(mid)
check("32 格叠加矩阵逐格与独立模型逐分一致（实跑 %d 格）" % cells, cells == 32 and all(ok[-cells:]))


# ===================== 3. 券门槛基数可证 + 文案不被积分污染 =====================

mid = enable_mk()
blocked = preview(True, True, False, gtok)
check("金卡+活动价+满减：17700−885−3000=13815，同一张券抵 0",
      blocked["member_discount"] == 885 and blocked["goods_discount"] == 3000
      and blocked["coupon_discount"] == 0, blocked)
disable_mk(mid)
usable = preview(True, True, False, gtok)
check("只关掉满减这一档，同一笔行、同一张券立刻能用（16815 ≥ 14000）",
      usable["coupon_discount"] == CP_FEE and usable["total_fee"] == 14815, usable)

mid = enable_mk()
dirty = preview(True, True, True, gtok)
r = dirty.get("coupon_reason") or ""
check("拒券文案报的是满减之后（138.15），不被积分抵扣后的钱污染（本轮修的展示口径）",
      yuan(13815) in r and "69.08" not in r and dirty["point_fee"] == min(POINT0[gtok], 13815 * 50 // 100),
      (r, dirty["point_fee"], dirty["max_point"]))

b = data("POST", "/api/h5app/wxapp/getFreightFee",
         {"cart_type": "fastbuy", "items": pairs((ITEM, NUM)), "coupon_code": BIG[ptok],
          "point_use": True, "order_class": "seckill", "act_id": sk}, ptok)
check("大额券把一行抵到 0 就停：抵 14700（满减之后的应付）而不是 30000，积分与上限位同时归零",
      b["coupon_discount"] == 14700 and b["total_fee"] == 0 and b["point_fee"] == 0
      and b["max_point"] == 0 and b["items"][0]["total_fee"] == 0, b)
check("抵到 0 之后折让构成里没有积分行（没抵钱就不进账）",
      [x["type"] for x in b["discount_info"]] == ["seckill", "full_minus", "coupon"], b["discount_info"])
disable_mk(mid)


# ===================== 4. 四叠加下单：预览==下单==落库，取消整条回退 =====================

mid = enable_mk()
e = oracle(False, True, True, True, True, POINT0[ptok])
p = preview(True, True, True, ptok)
st, o = req("POST", "/api/h5app/wxapp/order_new", {
    "cart_type": "fastbuy", "order_type": "normal", "items": pairs((ITEM, NUM)), "order_from": "h5",
    "receiver_name": "张三", "receiver_mobile": PLAIN, "receiver_state": "浙江省", "receiver_city": "杭州市",
    "receiver_district": "西湖区", "receiver_address": "文三路 1 号",
    "order_class": "seckill", "act_id": sk, "coupon_discount": CODE[ptok], "point_use": True}, ptok)
check("四叠加下单成功且价格 == 预览（%d 分）" % e["total"], st == 200 and o["data"]["total_fee"] == e["total"]
      == p["total_fee"], (st, o, p["total_fee"]))
oid = o["data"]["order_id"]
det = data("GET", "/api/h5app/wxapp/order/%s" % oid, token=ptok)["orderInfo"]
check("单头四类账各自独立：货款按活动价、折让只有满减、券与积分另记",
      det["item_fee"] == e["item_fee"] and det["discount_fee"] == e["mk"]
      and det["coupon_discount"] == e["cp"] and det["point_fee"] == e["point"]
      and det["total_fee"] == e["total"], det)
info = data("GET", "/api/h5app/wxapp/order/%s" % oid, token=ptok)["orderInfo"]["discount_info"]
check("ORDER-5 折让构成只带行级的活动与满减两条（券与积分各自另有账位列，四行快照由 e2e 判）",
      [x["type"] for x in info] == ["seckill", "full_minus"]
      and sum(x["discount_fee"] for x in info) == e["activity"] + e["mk"], info)
check("资产占用：券被这笔单锁住、积分即时扣、秒杀按件占池",
      data("GET", "/api/h5app/wxapp/coupons/my?status=locked&pageSize=50", token=ptok)["total_count"] >= 1
      and data("GET", "/api/h5app/wxapp/member", token=ptok)["point"] == POINT0[ptok] - e["point"]
      and data("GET", "/api/seckill/activities/%s/report" % sk, token=atok)["hold_store"] == NUM,
      (data("GET", "/api/h5app/wxapp/member", token=ptok)["point"],
       data("GET", "/api/seckill/activities/%s/report" % sk, token=atok)))
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": oid, "cancel_reason": "%s 矩阵收尾" % RUN}, ptok)
check("取消整条回退：券回 UNUSED、积分回补、池子释放",
      data("GET", "/api/h5app/wxapp/member", token=ptok)["point"] == POINT0[ptok]
      and data("GET", "/api/seckill/activities/%s/report" % sk, token=atok)["hold_store"] == 0
      and data("GET", "/api/h5app/wxapp/coupons/my?status=unused&pageSize=100", token=ptok)["total_count"] >= 2,
      (data("GET", "/api/h5app/wxapp/member", token=ptok)["point"],
       data("GET", "/api/seckill/activities/%s/report" % sk, token=atok)))
disable_mk(mid)
back = preview(False, False, False)
check("撤干净回到 seed 态：全场折让 0、基线价 %d" % (UNIT * NUM),
      back["total_fee"] == UNIT * NUM and back["discount_fee"] == 0, back)


# ===================== 5. 并发超员：8 路挤 2 人团 =====================

gp_seat = data("POST", "/api/groups/activities", {
    "act_name": "%s 并发两人团" % RUN, "goods_id": ITEM, "act_price": GP_PRICE, "person_num": 2,
    "store": 20, "limit_buy_num": 0, "limit_time": 24,
    "begin_time": ts(-3600), "end_time": ts(86400), "robot": False, "instruction": "并发门槛"}, atok)
gid_seat = gp_seat["groups_activity_id"]
seat0 = gp_seat["store"]


def gorder(tok, team=None, num=1):
    body = {"cart_type": "fastbuy", "order_type": "normal", "items": pairs((ITEM, num)), "order_from": "h5",
            "receiver_name": "并发会员", "receiver_mobile": "13800000009", "receiver_state": "浙江省",
            "receiver_city": "杭州市", "receiver_district": "西湖区", "receiver_address": "文三路 9 号",
            "order_class": "groups", "act_id": gid_seat}
    if team:
        body["team_id"] = team
    st, res = req("POST", "/api/h5app/wxapp/order_new", body, tok)
    return (st, res)


head = gorder(ptok)
check("开团者先占第一席并拿到团号",
      head[0] == 200 and head[1]["data"]["team_id"].startswith("GT"), head)
seat_team = head[1]["data"]["team_id"]
head_oid = head[1]["data"]["order_id"]

gate = threading.Barrier(len(latoks))


def join_one(tk):
    gate.wait()
    return gorder(tk, seat_team)


with ThreadPoolExecutor(max_workers=len(latoks)) as ex:
    joined = list(ex.map(join_one, latoks))
wins = [x for x in joined if x[0] == 200]
fails = [x for x in joined if x[0] != 200]
check("8 人并发挤只剩 1 席的 2 人团：恰好 1 人进团", len(wins) == 1, [(x[0], x[1].get("message")) for x in joined])
check("抢输的 7 笔都是业务拒绝（名额已满），不是 500",
      all(x[0] == 422 and "名额" in (x[1].get("message") or "") for x in fails),
      [(x[0], x[1].get("message")) for x in fails])
team = data("GET", "/api/h5app/wxapp/groups/teams/%s" % seat_team, token=ptok)
check("团席位恰好 2，没有超员", team["join_person_num"] == 2 and team["team_status"] == 1, team)
check("活动额度只扣 2（进团的两位），抢输的把额度原样还回来了",
      data("GET", "/api/groups/activities/%s" % gid_seat, token=atok)["store"] == seat0 - 2,
      data("GET", "/api/groups/activities/%s" % gid_seat, token=atok))
mine = data("GET", "/api/h5app/wxapp/groups/my/teams", token=ptok)["teams"]
check("开团者的「我的团」能看到这个团且人数为 2",
      any(x["team_id"] == seat_team and x["join_person_num"] == 2 for x in mine), mine)

# 进团者用自己的令牌取消（HTTP 应答里没有令牌位，归属只能从 latoks 与 joined 的对应关系取）
joiner_tok = [tk for tk, x in zip(latoks, joined) if x[0] == 200][0]
joiner_oid = [x[1]["data"]["order_id"] for x in joined if x[0] == 200][0]
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": joiner_oid, "cancel_reason": "%s 席位回退" % RUN}, joiner_tok)
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": head_oid, "cancel_reason": "%s 席位回退" % RUN}, ptok)
after = data("GET", "/api/groups/activities/%s" % gid_seat, token=atok)
check("两笔取消后席位与额度双双回位（额度回满、团里没人）",
      after["store"] == seat0 and data("GET", "/api/h5app/wxapp/groups/teams/%s" % seat_team, token=ptok)["join_person_num"] == 0,
      after)


# ===================== 6. 并发抢额度 / 抢池 =====================

gid_cap = data("POST", "/api/groups/activities", {
    "act_name": "%s 并发限量团" % RUN, "goods_id": ITEM, "act_price": GP_PRICE, "person_num": 3,
    "store": 3, "limit_buy_num": 0, "limit_time": 24,
    "begin_time": ts(-3600), "end_time": ts(86400), "robot": False, "instruction": "额度门槛"}, atok)["groups_activity_id"]


def gbody(tok, act):
    return {"cart_type": "fastbuy", "order_type": "normal", "items": pairs((ITEM, 1)), "order_from": "h5",
            "receiver_name": "并发会员", "receiver_mobile": "13800000009", "receiver_state": "浙江省",
            "receiver_city": "杭州市", "receiver_district": "西湖区", "receiver_address": "文三路 9 号",
            "order_class": "groups", "act_id": act}


tokens9 = [ptok, gtok] + latoks[:6]
gate2 = threading.Barrier(len(tokens9))


def open_one(tk):
    gate2.wait()
    st, res = req("POST", "/api/h5app/wxapp/order_new", gbody(tk, gid_cap), tk)
    return st, res


with ThreadPoolExecutor(max_workers=len(tokens9)) as ex:
    opened = list(ex.map(open_one, tokens9))
won_cap = [x for x in opened if x[0] == 200]
check("9 路并发各开新团、活动额度 3 → 恰好 3 个团", len(won_cap) == 3,
      [(x[0], x[1].get("message")) for x in opened])
check("额度抢光归零，不多留一行（没有「占了额度却没开团」的残账）",
      data("GET", "/api/groups/activities/%s" % gid_cap, token=atok)["store"] == 0,
      data("GET", "/api/groups/activities/%s" % gid_cap, token=atok))
tlist = data("GET", "/api/groups/teams?act_id=%s&pageSize=50" % gid_cap, token=atok)
check("团数 == 成单数，且每个新团都只有开团者 1 人",
      tlist["total_count"] == 3 and all(x["join_person_num"] == 1 for x in tlist["list"]), tlist["list"])

gid_sk = data("POST", "/api/seckill/activities", {
    "activity_name": "%s 并发秒杀池" % RUN, "activity_type": 1, "ad_pic": "/img/s2.jpg",
    "distributor_id": "1", "activity_start_time": ts(-3600), "activity_end_time": ts(86400),
    "validity_period": 10, "limit_money": 0}, atok)["seckill_id"]
data("PUT", "/api/seckill/activities/%s/goods" % gid_sk, {"items": [
    {"item_id": ITEM, "item_name": "并发圈品", "activity_price": SK_PRICE,
     "activity_store": 3, "limit_num": 0, "is_show": True}]}, atok)


def skbody(tk):
    return {"cart_type": "fastbuy", "order_type": "normal", "items": pairs((ITEM, 1)), "order_from": "h5",
            "receiver_name": "并发会员", "receiver_mobile": "13800000009", "receiver_state": "浙江省",
            "receiver_city": "杭州市", "receiver_district": "西湖区", "receiver_address": "文三路 9 号",
            "order_class": "seckill", "act_id": gid_sk}


gate3 = threading.Barrier(len(tokens9))


def race_sk(tk):
    gate3.wait()
    st, res = req("POST", "/api/h5app/wxapp/order_new", skbody(tk), tk)
    return tk, st, res


with ThreadPoolExecutor(max_workers=len(tokens9)) as ex:
    pool = list(ex.map(race_sk, tokens9))
won_sk = [x for x in pool if x[1] == 200]
lost_sk = [x for x in pool if x[1] != 200]
rep = data("GET", "/api/seckill/activities/%s/report" % gid_sk, token=atok)
check("9 单并发抢 3 件池子 → 恰好 3 单成功", len(won_sk) == 3, [(x[1], x[2].get("message")) for x in pool])
check("池子只被占 3 件、销量 0、可购归零（CAS 没被抢穿）",
      rep["hold_store"] == 3 and rep["sales_store"] == 0 and rep["available_store"] == 0, rep)
check("抢输的 6 笔全是业务拒绝", all(x[1] == 422 for x in lost_sk), [(x[1], x[2].get("message")) for x in lost_sk])
st, late2 = req("POST", "/api/h5app/wxapp/order_new", skbody(ptok), ptok)
msg = late2.get("message") or ""
check("池子空了之后再下单继续被拒，且占用纹丝不动（预检与 CAS 两条路都算对）",
      st == 422 and ("名额" in msg or "抢完" in msg)
      and data("GET", "/api/seckill/activities/%s/report" % gid_sk, token=atok)["hold_store"] == 3, (st, msg))


def cancel_one(triple):
    tk, _, res = triple
    return data("POST", "/api/h5app/wxapp/order/cancel",
                {"order_id": res["data"]["order_id"], "cancel_reason": "%s 并发回池" % RUN}, tk)


with ThreadPoolExecutor(max_workers=3) as ex:
    list(ex.map(cancel_one, won_sk))
rep2 = data("GET", "/api/seckill/activities/%s/report" % gid_sk, token=atok)
check("并发取消后占用清零、池子回满（还能再卖 3 件）",
      rep2["hold_store"] == 0 and rep2["available_store"] == 3, rep2)


# ===================== 7. 权限面与收口 =====================

st, _ = req("GET", "/api/seckill/activities?pageSize=1", token=vtok)
check("viewer 无 promotions.seckill.lists → 403", st == 403, st)
st, _ = req("POST", "/api/h5app/wxapp/getFreightFee", pbody(False, False, False))
check("匿名前台算价 → 401 Unauthenticated.", st == 401, st)

for x in data("GET", "/api/marketing/activities?pageSize=100", token=atok)["list"]:
    if x["marketing_name"].startswith("MT"):
        req("DELETE", "/api/marketing/activities/%s" % x["marketing_id"], None, atok)
data("PUT", "/api/seckill/activities/%s/status" % sk, {"disabled": True}, atok)
data("PUT", "/api/seckill/activities/%s/status" % gid_sk, {"disabled": True}, atok)
for g in (gid_seat, gid_cap):
    data("PUT", "/api/groups/activities/%s/status" % g, {"disabled": True}, atok)
for tk in set(latoks + [ptok, gtok]):
    for x in data("GET", "/api/h5app/wxapp/orders?order_status=notpay&page=1&pageSize=50", token=tk)["list"]:
        req("POST", "/api/h5app/wxapp/order/cancel",
            {"order_id": x["order_id"], "cancel_reason": "%s 收口" % RUN}, tk)
front_sk = [a["seckill_id"] for a in data("GET", "/api/h5app/wxapp/seckill/activities?pageSize=100", token=ptok)["list"]]
check("收口：本案三个活动都停用，前台秒杀列表不再含本案场次",
      sk not in front_sk and gid_sk not in front_sk, front_sk)
clean = preview(False, False, False)
check("收口：本案优惠全部撤销后，真跑报价回到 seed 基线",
      clean["total_fee"] == UNIT * NUM and clean["discount_fee"] == 0
      and clean["activity_price_fee"] == 0, clean)

passed = sum(1 for x in ok if x)
print("\n[M1-3 #58 叠加矩阵与并发冒烟] %d/%d PASS" % (passed, len(ok)))
raise SystemExit(0 if passed == len(ok) else 1)
