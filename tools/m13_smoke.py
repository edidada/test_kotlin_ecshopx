import json
import time
import urllib.error
import urllib.request

"""M1-3 #56 实跑冒烟（满减满折 + 券/积分挂进算价链）：真 fat jar 上走一遍 §14.5 的逐级抵扣。

MockMvc 的 MarketingEndToEndTest（14 例）管"口径对不对"，本脚本管"起得来的服务对不对"：
权限码缓存、sys_config 读取、演示库里的真实积分余额与券实例，这些都是内存测试碰不到的东西。

覆盖：
 1. MK-A1 建活动：元字符串入库、分单位阶梯出参、`date_status` 派生、新建即 non-reviewed；
 2. **审核是唯一生效开关**：未审核时预览折让恒 0、前台详情 404，审核后立即开始减钱；
 3. MK-A4 编辑必打回：改力度之后折让归 0，重审才按新力度生效；
 4. MK-A5/A6 圈品 + 按件门槛：同组多活动只取折让最大的一个（一店一活动择优）；
 5. MK-F1/F2/F3 会员面：只给"已审核 + 启用 + 在窗"的活动，商详角标与算价同源；
 6. 券 × 满减 × 积分三级叠加：门槛逐级比"折让后的应付"，抵扣不超应付，预览==下单；
 7. 显式带券下单而抵扣为 0 → 422 拒绝（撤销 C25 的开关位口径），不静默按不用券成交；
 8. 取消整条回退：券回 UNUSED、积分回补、券还能用第二笔；
 9. 权限：viewer 无 `promotions.marketing.*` 三条码 → 403，匿名前台 → 401 `Unauthenticated.`；
10. 收口：本案活动全部 MK-A7 删除、券只能停发（CP-A6 已发放过不许删），残单取消，
    最后一条断言是"演示库回到 seed 态：全场折让 0"。

金额基准（`sql/seed.sql`）：商品 3 = 8900 分、12 件 = 106800 分；模板 1 全国包邮。
积分断言按**基线差值**写：演示库的余额会被 m08 等脚本动过，脚本不假设它一定是 1000。
"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
VPW = "viewer" + "123"
VCODE = "8888"
MEMBER = "13800000001"
ITEM = 3                      # 每日坚果礼盒 8900 分 / 模板 1 包邮
UNIT = 8900
NUM = 12
BASKET = UNIT * NUM           # 106800 分 = 1068 元，够 A 的最高档（满200减50）
ACT_A = 5000                  # 满200减50
ACT_LOW = 2000                # 满100减20（前台文案取的就是这一档）
COUPON_FEE = 1000             # 自建券面额 10 元
COUPON_MIN = 10000            # 门槛 100 元
RATIO = 50                    # sys_config point.deduct.max_ratio

RUN = "MK%d" % int(time.time())
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


atok = login("/api/operator/login", {"username": "admin", "password": PW})
vtok = login("/api/operator/login", {"username": "viewer", "password": VPW})
mtok = login("/api/h5app/wxapp/new_login", {"auth_type": "local", "username": MEMBER,
                                            "check_type": "mobile", "vcode": VCODE})
check("三类令牌到位（管理员/只读/会员）", all([atok, vtok, mtok]))

# 预清理：本脚本的活动都叫 "MK<时间戳> …"，上一次跑到一半挂掉留下的残档会污染"全场折让回 0"这条收尾断言
for x in data("GET", "/api/marketing/activities?pageSize=100", token=atok)["list"]:
    if x["marketing_name"].startswith("MK") and x["marketing_name"][2:].split(" ")[0].isdigit():
        data("DELETE", "/api/marketing/activities/%s" % x["marketing_id"], token=atok)


def pairs(*specs):
    return [{"item_id": i, "num": n} for i, n in specs]


def preview(coupon=None, point=False, num=NUM, item=ITEM):
    body = {"cart_type": "fastbuy", "items": pairs((item, num)), "point_use": point}
    if coupon:
        body["coupon_code"] = coupon
    return data("POST", "/api/h5app/wxapp/getFreightFee", body, mtok)


def order(coupon=None, point=False, num=NUM, item=ITEM):
    body = {"cart_type": "fastbuy", "order_type": "normal", "address_id": 1,
            "items": pairs((item, num)), "point_use": point, "order_from": "h5"}
    if coupon:
        # PHP 的下单券码字段就叫 coupon_discount（ORDER-1 用 coupon_code，两个名字都收）
        body["coupon_discount"] = coupon
    return data("POST", "/api/h5app/wxapp/order_new", body, mtok)


def my_status(code):
    """CP-F3 默认桶不含 LOCKED（在用中的券不单列），所以四态逐个问一遍。"""
    for s in ("UNUSED", "LOCKED", "USED", "EXPIRED"):
        rows = data("GET", "/api/h5app/wxapp/coupons/my?status=%s&page=1&pageSize=100" % s, token=mtok)["list"]
        if any(r["coupon_code"] == code for r in rows):
            return s
    return None


def mk_body(name, steps, bound=0, quantity=False, repeat=False, free_postage=False,
            mtype="full_minus", tag="满减"):
    now = int(time.time())
    return {"marketing_type": mtype, "marketing_name": name, "ad_pic": "/img/mk.jpg",
            "start_time": now - 3600, "end_time": now + 86400, "use_bound": bound,
            "condition_type": "quantity" if quantity else "totalfee",
            "condition_value": steps, "canjoin_repeat": repeat, "join_limit": 0,
            "free_postage": free_postage, "promotion_tag": tag, "status": True}


def step(full, minus=None, discount=None):
    s = {"full": str(full)}
    if minus is not None:
        s["minus"] = minus
    if discount is not None:
        s["discount"] = discount
    return s


# ===== 1. MK-A1 建活动：入库形态与出参换算各管一半 =====
act = data("POST", "/api/marketing/activities",
           mk_body(RUN + " 全场满减", [step("100.00", minus="20.00"), step("200.00", minus="50.00")]),
           atok)["marketing_id"]
check("MK-A1 建活动出 marketing_id", act > 0, act)
d = data("GET", "/api/marketing/activities/%s" % act, token=atok)
check("新建即 non-reviewed（未审核不生效）", d["check_status"] == "non-reviewed", d["check_status"])
check("date_status 由时间窗派生，不落列", d["date_status"] == "doing", d["date_status"])
check("阶梯换算成分并带规则文案",
      [d["steps"][0]["full"], d["steps"][0]["minus"], d["steps"][0]["rule"]] == [10000, ACT_LOW, "满100元减20"],
      d["steps"])
check("condition_value 原样回 PHP 的元字符串", "100.00" in json.dumps(d["condition_value"], ensure_ascii=False),
      d["condition_value"])

# ===== 2. 审核闸门：唯一生效开关 =====
check("未审核时算价链里等于不存在", preview()["discount_fee"] == 0, preview())
st, res = req("GET", "/api/h5app/wxapp/marketing/activities/%s" % act, token=mtok)
check("未审核活动前台详情 404（页面写着减、结算时不减 = 事故）", st == 404, (st, res))
check("未审核活动不进 MK-F1 前台列表",
      act not in [x["marketing_id"] for x in data("GET", "/api/h5app/wxapp/marketing/activities?pageSize=100",
                                                 token=mtok)["list"]])

check("MK-A6 审核通过", data("PUT", "/api/marketing/activities/%s/check" % act,
                            {"check_status": "reviewed"}, atok)["check_status"] == "reviewed")
p = preview()
check("审核后开始减钱：1068 元命中满200减50", p["discount_fee"] == ACT_A, p["discount_fee"])
check("应付 = 货款 − 折让（模板 1 包邮）",
      p["item_fee"] == BASKET and p["freight_fee"] == 0 and p["total_fee"] == BASKET - ACT_A, p)
check("goods_discount 是活动给的那部分", p["goods_discount"] == ACT_A, p)
check("discount_info 记的是最高档",
      len(p["discount_info"]) == 1 and p["discount_info"][0]["type"] == "full_minus"
      and p["discount_info"][0]["rule"] == "满200元减50", p["discount_info"])
check("行上留了活动痕迹（CART-2 的结算行才带 activity_info）",
      len(data("GET", "/api/h5app/wxapp/cart?cart_type=fastbuy&items=%s:%d" % (ITEM, NUM),
               token=mtok)["valid_cart"][0]["list"][0]["activity_info"]) >= 1)

# ===== 3. MK-A4 编辑必重审：不存在「改完继续悄悄生效」这条后门 =====
data("PUT", "/api/marketing/activities/%s" % act,
     mk_body(RUN + " 全场满减", [step("100.00", minus="20.00"), step("200.00", minus="60.00")]), atok)
check("改力度打回 non-reviewed",
      data("GET", "/api/marketing/activities/%s" % act, token=atok)["check_status"] == "non-reviewed")
check("打回后立刻停止减钱", preview()["discount_fee"] == 0, preview()["discount_fee"])
data("PUT", "/api/marketing/activities/%s/check" % act, {"check_status": "reviewed"}, atok)
check("重审后按新力度（满200减60）", preview()["discount_fee"] == 6000, preview()["discount_fee"])
data("PUT", "/api/marketing/activities/%s" % act,
     mk_body(RUN + " 全场满减", [step("100.00", minus="20.00"), step("200.00", minus="50.00")]), atok)
data("PUT", "/api/marketing/activities/%s/check" % act, {"check_status": "reviewed"}, atok)
check("力度改回 5000，后面的金额断言才成立", preview()["discount_fee"] == ACT_A, preview()["discount_fee"])

# ===== 4. 圈品 + 按件门槛 + 一店一活动择优 =====
act2 = data("POST", "/api/marketing/activities",
            mk_body(RUN + " 满2件减30", [step(2, minus="30.00")], bound=1, quantity=True), atok)["marketing_id"]
saved = data("PUT", "/api/marketing/activities/%s/items" % act2,
             {"items": [{"item_id": ITEM, "goods_id": ITEM, "item_name": "冒烟圈品", "price": UNIT}]}, atok)
check("MK-A5 圈品保存回显行并计入 item_count",
      saved["items"][0]["item_id"] == ITEM
      and data("GET", "/api/marketing/activities/%s" % act2, token=atok)["item_count"] == 1, saved)
data("PUT", "/api/marketing/activities/%s/check" % act2, {"check_status": "reviewed"}, atok)
two = preview(num=2)
check("按件门槛命中：2 件减 30 优于 A 的减 20",
      two["discount_fee"] == 3000 and two["discount_info"][0]["rule"] == "满2件减30", two["discount_info"])
check("1 件不达标（按件只看件数，不看金额）", preview(num=1)["discount_fee"] == 0, preview(num=1))
check("同组只择优记一个（1068 元仍是 A 的 5000）",
      preview()["discount_fee"] == ACT_A and len(preview()["discount_info"]) == 1, preview())

# ===== 5. 前台面：只给看得懂且买得到的 =====
front = data("GET", "/api/h5app/wxapp/marketing/activities?pageSize=100", token=mtok)
row = [x for x in front["list"] if x["marketing_id"] == act]
check("MK-F1 列表含本案活动，condition_text 取最低档",
      len(row) == 1 and row[0]["condition_text"] == "满100元减20" and row[0]["date_status"] == "doing", row)
fd = data("GET", "/api/h5app/wxapp/marketing/activities/%s" % act, token=mtok)
check("MK-F2 详情不给后台配置字段（steps/condition_value 都不该露）",
      "steps" not in fd and "condition_value" not in fd, list(fd))
promo = data("GET", "/api/h5app/wxapp/items/%s/promotions" % ITEM, token=mtok)
check("MK-F3 商详角标与算价同源，秒杀键位先占位 null",
      act in [x["marketing_id"] for x in promo["marketing"]] and "满减" in promo["tags"]
      and promo["seckill"] is None, promo)
check("按件活动只对圈中的商品露出",
      act2 not in [x["marketing_id"] for x in data("GET", "/api/h5app/wxapp/items/2/promotions", token=mtok)["marketing"]])

# ===== 6. 权限码分立 =====
st, res = req("GET", "/api/marketing/activities", token=vtok)
check("viewer 无 promotions.marketing.lists → 403", st == 403, (st, res))
st, res = req("POST", "/api/marketing/activities", mk_body(RUN + " 越权", [step("100.00", minus="1.00")]), vtok)
check("viewer 无 update 码 → 403", st == 403, (st, res))
st, res = req("PUT", "/api/marketing/activities/%s/check" % act, {"check_status": "reviewed"}, vtok)
check("viewer 无 check 码 → 403", st == 403, (st, res))
st, res = req("GET", "/api/h5app/wxapp/marketing/activities")
check("匿名前台 401 且文案是 Unauthenticated.", st == 401 and res["message"] == "Unauthenticated.", (st, res))

# ===== 7. 券 × 满减 × 积分：三级依次以折让后的应付为基数 =====
coupon = data("POST", "/api/coupons", {"coupon_type": "cash", "coupon_name": RUN + " 满100减10",
                                       "discount_fee": COUPON_FEE, "min_order_fee": COUPON_MIN,
                                       "total_num": 3, "valid_type": "range",
                                       "begin_time": int(time.time()) - 60,
                                       "end_time": int(time.time()) + 86400,
                                       "get_limit": 1}, atok)["coupon_id"]
code = data("POST", "/api/h5app/wxapp/coupons/receive", {"coupon_id": coupon}, mtok)["coupon_code"]
check("CP-F2 领券拿到券码", len(code) > 0, code)

miss = preview(coupon=code, num=1)
check("券门槛比的是折后应付：89 元用不了满 100 元的券",
      miss["coupon_discount"] == 0 and "满 100 元可用" in miss["coupon_reason"], miss["coupon_reason"])
st, res = req("POST", "/api/h5app/wxapp/order_new",
              {"cart_type": "fastbuy", "order_type": "normal", "address_id": 1,
               "items": pairs((ITEM, 1)), "coupon_discount": code}, mtok)
check("带券下单而抵扣为 0 → 422（不静默按不用券成交）",
      st == 422 and "满 100 元可用" in json.dumps(res, ensure_ascii=False), (st, res))

both = preview(coupon=code)
check("满减之后才轮到券：抵 10 元后应付 %d" % (BASKET - ACT_A - COUPON_FEE),
      both["discount_fee"] == ACT_A and both["coupon_discount"] == COUPON_FEE
      and both["total_fee"] == BASKET - ACT_A - COUPON_FEE, both)
check("coupon_info 记实抵，规则文案与 CP-F4 同源",
      both["coupon_info"][0]["deduct_fee"] == COUPON_FEE
      and both["coupon_info"][0]["coupon_code"] == code
      and "满 100 元减 10" in both["coupon_info"][0]["rule"], both["coupon_info"])
check("折让构成按链路顺序记", [x["type"] for x in both["discount_info"]] == ["full_minus", "coupon"],
      both["discount_info"])

point0 = data("GET", "/api/h5app/wxapp/member", token=mtok)["point"]
after_coupon = BASKET - ACT_A - COUPON_FEE
exp_point = min(point0, after_coupon * RATIO // 100)
tri = preview(coupon=code, point=True)
check("三级叠加：积分抵 min(余额 %d, 应付×%d%%) = %d" % (point0, RATIO, exp_point),
      tri["point_fee"] == exp_point and tri["total_fee"] == after_coupon - exp_point, tri)
check("limit_point 给比例、max_point 给本单上限（未抵前）",
      tri["limit_point"] == RATIO and tri["max_point"] == exp_point,
      (tri["limit_point"], tri["max_point"], tri["user_point"]))
check("不勾积分开关：一分不抵且比例位也不给",
      both["point_fee"] == 0 and both["limit_point"] == 0, (both["point_fee"], both["limit_point"]))
check("Σ行应付 + 运费 == 单应付（三类抵扣都逐行分摊）",
      sum(l["total_fee"] for l in tri["items"]) + tri["freight_fee"] == tri["total_fee"], tri["items"])

# ===== 8. 预览 == 下单，取消整条回退 =====
base_notpay = data("GET", "/api/h5app/wxapp/orders?order_status=notpay&page=1&pageSize=50", token=mtok)["total_count"]
o = order(coupon=code, point=True)
oid = o["order_id"]
check("ORDER-2 与 ORDER-1 逐分一致", o["total_fee"] == tri["total_fee"], (o["total_fee"], tri["total_fee"]))
info = data("GET", "/api/h5app/wxapp/order/%s" % oid, token=mtok)["orderInfo"]
check("落库金额：货款记原价，三类抵扣各归各列",
      info["item_fee"] == BASKET and info["discount_fee"] == ACT_A
      and info["coupon_discount"] == COUPON_FEE and info["point_fee"] == exp_point
      and info["total_fee"] == o["total_fee"], info)
check("下单即占资产：积分即时扣减、券 LOCKED 并挂上订单",
      data("GET", "/api/h5app/wxapp/member", token=mtok)["point"] == point0 - exp_point
      and my_status(code) == "LOCKED", my_status(code))
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": oid, "cancel_reason": "M1-3 冒烟回退验证"}, mtok)
back = data("GET", "/api/h5app/wxapp/member", token=mtok)["point"]
check("取消整条回退：积分回补到基线 %d、券回 UNUSED" % point0,
      back == point0 and my_status(code) == "UNUSED", (back, my_status(code)))
o2 = order(coupon=code)
check("回退后的券还能用（第二笔照抵 %d）" % COUPON_FEE, o2["total_fee"] == BASKET - ACT_A - COUPON_FEE, o2)
data("POST", "/api/h5app/wxapp/order/cancel", {"order_id": o2["order_id"], "cancel_reason": "M1-3 冒烟收口"}, mtok)

# ===== 9. 收口：活动删干净、券停发、回到 seed 态 =====
st, res = req("DELETE", "/api/coupons/%s" % coupon, token=atok)
check("已发放过的券不能删（CP-A6 只能停发）", st == 422, (st, res))
data("PUT", "/api/coupons/%s/status" % coupon, {"status": False}, atok)
for a in (act, act2):
    dd = data("DELETE", "/api/marketing/activities/%s" % a, token=atok)
    check("MK-A7 删除活动 %s 后即 404" % a,
          dd["deleted"] is True and req("GET", "/api/marketing/activities/%s" % a, token=atok)[0] == 404, dd)
left = data("GET", "/api/h5app/wxapp/orders?order_status=notpay&page=1&pageSize=50", token=mtok)["total_count"]
check("本脚本没给会员多留待付款残单（基线 %d）" % base_notpay, left == base_notpay, (base_notpay, left))
check("删干净之后全场折让回 0（seed 那两条永远不在窗）", preview()["discount_fee"] == 0, preview())
check("前台列表不再出现本案活动",
      act not in [x["marketing_id"] for x in data("GET", "/api/h5app/wxapp/marketing/activities?pageSize=100",
                                                 token=mtok)["list"]])
check("停发的券不再出现在可领列表",
      coupon not in [x["coupon_id"] for x in data("GET", "/api/h5app/wxapp/coupons?pageSize=100",
                                                 token=mtok)["list"]])

print("\n[%s] 通过 %d/%d" % (RUN, sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
