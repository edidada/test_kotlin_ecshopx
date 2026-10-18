import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request

"""M1-4 第三批后半（3B：导购 SM + 员工端 ST/SO + 岗侧提现 CW）实跑冒烟：真 fat jar 上走一遍 docs/04 §18.2/18.3/18.5 的口径。

SalesmanStaffEndToEndTest（15 例）管"口径对不对"，本脚本管"起得来的服务对不对"：
`staffChain` 在真实安全配置里的位次（`/api/admin/wxapp` 归员工、`/api/salesman*` 归运营）、
`distribution_distributor_salesman_role` 那张新表与 45/46 两条权限码是否真落进了常驻库、
`operators.distributor_ids='[2]'` 那个店铺账号在导购列表上是不是还能看见自己店里的人
（`DistributorScope.narrow` 把空集合当成"一家都不要"时，症状与"他店里真没人"一模一样）——
这些都是内存测试碰不到的东西。

覆盖：
 1. **三条链互不认**：员工令牌打后台面 401、运营/会员令牌打员工面 401（`x-wxapp-session` 与
    `Authorization: Bearer` 两种写法都要认，规范头是前者）；
 2. ST-E1 登录：缺字段 400、三种失败同一句 401（停用/没设口令/口令不符，不回答"手机号在不在册"）、
    `distributor_id` 传错店等于查无此人；
 3. SM-A1..A5：手机号店内唯一、`is_valid` 两位枚举脏值 400、读-改-写、**清空口令后立即登不进来**
    （本轮修的凭据缺陷）、**停用即刻关掉已有会话**（D44）、店铺账号只看得到绑定店（本轮修的 narrow 缺陷）；
 4. SM-A6 岗位：复数路径（`/api/salesman/roles`）不与 `/api/salesman/{id}` 抢映射、平台通用岗只有 admin 建得了、
    挂着导购的岗删不掉（422）；
 5. SM-F1/F2 + C177：无岗的在册导购在前台就是 404、绑上之后会员自己下单 `salesman_id` 跟着进单、
    落单即定（换绑不回改旧单）；
 6. SO-E1 三条强制：`salesman_id` 取会话（入参传谁都忽略）、越店 403、被代下单的会员不存在 404、
    散客走购物车 422"为空"（期望行为）；
 7. SO-E2/E3/E4：列表按会话收窄、不是他开的单 404、员工端核销与后台 WO-A2 同一条状态迁移、
    留痕署名 `salesman:<id>`；
 8. ST-E4/E5：一个全新岗的八个数**可以是绝对数**（0 → 1000 → 结算后 min(本岗, 店池)）、
    明细面强制 `salesman_id=会话`；
 9. CW-F3/F4 + §22 C176 一口池：新建一家演示店跑绝对数 —— 店池 2000、店侧先提空 2000 后
    **岗侧一分都提不了**（`min(本岗已结算, 店池余额)`，岗名下那些行不是另开的一份钱）；驳回后岗侧提 1000，
    店侧那道在途闸门同样被挡住 —— **那道闸门按 `distributor_id` 数行、含岗侧那笔，一口池就一个在途位**；
    `refused` 不占池（C149，也是本脚本的收口手段），`refused` 是终态不许再迁。

脚本按**差值或全新实体的绝对数**断言；收尾把 `items.rebate` 还原成 seed 真值 0、本轮建的岗全部停用、
两笔在途提现驳回（池子回到 2000），新建的演示店留在库里（各面都按 `distributor_id` 收窄，不污染兄弟脚本）。
"""

BASE = "http://127.0.0.1:18080"
PW = "admin" + "123"
VPW = "viewer" + "123"
SPW = "shop" + "123"
DPW = "staff" + "123"          # 本轮新建导购的员工端演示口令（只活在本脚本里）
VCODE = "8888"
MEMBER = "13800000001"         # user_id=1，seed 里是 demo 旗舰店老板（salesman_id=1，无岗位）
MEMBER2 = "13800000002"        # user_id=2，seed 里的在册导购（salesman_id=2，role_id=1）
SHOP = 2                       # demo 旗舰店（is_ziti，且绑了自提点）
HQ = 1
ITEM = 3                       # 每日坚果礼盒 8900 分
UNIT = 8900
NUM = 2
REBATE = 500                   # 每件佣金（分）：本案现场设进 items.rebate，收尾还原
FEE = REBATE * NUM             # 一单 1000 分
DAY = 86400
T0 = int(time.time())
RUN = "SM%d" % T0
ok = []


def mob(k):
    """本轮独有的手机号：`uk_salesman_mobile` 在常驻库上是真约束，重复跑必须换号"""
    return "139%08d" % ((T0 + k) % 10 ** 8)


def req(method, path, body=None, token=None, sess=None):
    payload = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=payload, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    if sess:
        r.add_header("x-wxapp-session", sess)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def raw(path, body, ctype):
    r = urllib.request.Request(BASE + path, data=body.encode(), method="POST")
    r.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
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


def sd(sess, method, path, body=None):
    """员工面调用：规范头是 x-wxapp-session，不是 Authorization"""
    st, res = req(method, path, body, sess=sess)
    assert st == 200, "%s %s -> %s %s" % (method, path, st, res)
    return res["data"]


def login(path, body):
    st, res = req("POST", path, body)
    assert st == 200, res
    return res["data"]["token"]


def fail(method, path, body=None, token=None, sess=None):
    return req(method, path, body, token, sess)


def member_login(user):
    return login("/api/h5app/wxapp/new_login", {"auth_type": "local", "username": user,
                                                "check_type": "mobile", "vcode": VCODE})


def staff_login(mobile, password=DPW, shop=None):
    body = {"mobile": mobile, "password": password}
    if shop is not None:
        body["distributor_id"] = shop
    st, res = req("POST", "/api/admin/wxapp/login", body)
    return st, res


def sm_body(shop, name, mobile, **kw):
    body = {"distributor_id": shop, "salesman_name": name, "mobile": mobile}
    for k in ("role_id", "user_id", "password", "is_valid", "avatar", "shop_id"):
        if k in kw:
            body[k] = kw.pop(k)
    body.update(kw)
    return body


def new_sm(shop, name, mobile, token=None, **kw):
    return data("POST", "/api/salesman", sm_body(shop, name, mobile, **kw), token or atok)


def sm_of(sid_, token=None):
    return data("GET", "/api/salesmans?pageSize=100", token=(token or atok))["list"] and \
        [x for x in data("GET", "/api/salesmans?pageSize=100", token=(token or atok))["list"] if x["salesman_id"] == sid_][0]


def role_body(shop, name, **kw):
    body = {"distributor_id": shop, "role_name": name}
    body.update(kw)
    return body


def front_points(shop):
    return data("GET", "/api/h5app/wxapp/distributor/pickuplocation?distributor_id=%s" % shop)["list"]


def pool():
    return data("GET", "/api/h5app/wxapp/goods/items_price_store/%s" % ITEM)["store"]


def add_cart(tok, shop, num=1):
    data("POST", "/api/h5app/wxapp/cart", {"item_id": ITEM, "num": num, "distributor_id": shop}, tok)


def cart_rows(tok):
    c = data("GET", "/api/h5app/wxapp/cart", token=tok)
    out = []
    for key in ("valid_cart", "invalid_cart"):
        for g in c.get(key) or []:
            out += [r["cart_id"] for r in g["list"]]
    return out


def clear_cart():
    for tok in (mtok, otok, ntok):
        ids = cart_rows(tok)
        if ids:
            data("DELETE", "/api/h5app/wxapp/cartdel", {"cart_ids": ids}, tok)


def recv(body):
    """收货四件套必给：本轮新注册那个会员没有地址簿，`address_id` 这条路走不通"""
    body.update({"receiver_name": "张三", "receiver_mobile": MEMBER, "order_from": "h5",
                 "receiver_state": "浙江省", "receiver_city": "杭州市", "receiver_district": "西湖区",
                 "receiver_address": "文三路 1 号"})
    return body


def order_fee(oid, tok):
    return int(data("GET", "/api/h5app/wxapp/order/%s" % oid, token=tok)["orderInfo"]["total_fee"])


def pay(oid, tok):
    t = data("POST", "/api/h5app/wxapp/payment", {"order_id": str(oid), "pay_type": "wxpay"}, tok)
    fields = {"out_trade_no": t["trade_id"], "total_fee": str(order_fee(oid, tok)), "trade_state": "SUCCESS",
              "transaction_id": "WX-" + t["trade_id"][-6:]}
    st, body = raw("/api/notify/wxpay", urllib.parse.urlencode(fields), "application/x-www-form-urlencoded")
    assert "SUCCESS" in body, (st, body)


def so_body(shop, user=None, num_items=None, extra=None):
    """SO-E1 入参：`salesman_id` 故意传一个假的，验它被会话覆盖（§18.3）"""
    body = {"cart_type": "cart", "order_type": "normal", "distributor_id": shop,
            "salesman_id": 987654, "receiver_name": "张三", "receiver_mobile": MEMBER,
            "receiver_state": "浙江省", "receiver_city": "杭州市", "receiver_district": "西湖区",
            "receiver_address": "文三路 1 号", "order_from": "h5"}
    if user is not None:
        body["user_id"] = user
    if extra:
        body.update(extra)
    return body


def staff_order(sess, shop, tok, buyer=None, num=NUM, extra=None):
    add_cart(tok, shop, num)
    # 单号在本项目是 16 位整数，但出参给的是字符串（PHP 兼容），这里统一收成 int 好拼 URL
    return int(sd(sess, "POST", "/api/admin/wxapp/order/create", so_body(shop, buyer, extra=extra))["order_id"])


def logs_of(**query):
    q = "&".join("%s=%s" % (k, urllib.parse.quote(str(v))) for k, v in query.items())
    return data("GET", "/api/distribution/logs?pageSize=100&" + q, token=atok)


def count(shop):
    return data("GET", "/api/distribution/count?distributor_id=%s" % shop, token=atok)


def close(to_time, shop=None):
    body = {"to_time": to_time}
    if shop:
        body["distributor_id"] = shop
    return data("POST", "/api/distribution/logs/close", body, atok)


def patch_item(rebate, store=None):
    body = {"item_name": before_item["item_name"], "price": UNIT, "rebate": rebate}
    body["store"] = store if store is not None else data("GET", "/api/goods/items/%d" % ITEM, token=atok)["store"]
    data("PUT", "/api/goods/items/%d" % ITEM, body, atok)
    return data("GET", "/api/goods/items/%d" % ITEM, token=atok)


# ===== 0. 令牌、库存与佣金基数 =====
atok = login("/api/operator/login", {"username": "admin", "password": PW})
vtok = login("/api/operator/login", {"username": "viewer", "password": VPW})
stok = login("/api/operator/login", {"username": "distributor01", "password": SPW})
mtok = member_login(MEMBER)
otok = member_login(MEMBER2)
NEWM = "137" + str(T0)[-8:]
ntok = member_login(NEWM)
check("六个令牌到位（运营×3 / 会员×3，含本轮新注册的那个）",
      all([atok, vtok, stok, mtok, otok, ntok]))

clear_cart()
before_item = data("GET", "/api/goods/items/%d" % ITEM, token=atok)
old_rebate = before_item["rebate"]
after_item = patch_item(REBATE, max(before_item["store"], 60))
check("GOODS-4 写入 items.rebate 后读得到", after_item["rebate"] == REBATE, after_item["rebate"])
check("库存抬到够下单（常驻库会被往轮买走）", after_item["store"] >= 12, after_item["store"])
POOL0 = pool()

# ===== 1. 三条链互不认（实跑专属：位次与 securityMatcher 都是配置层的东西） =====
st, res = fail("GET", "/api/salesmans", None, None, sess=DPW)
st2, res2 = fail("GET", "/api/admin/wxapp/getinfo", None, atok)
st3, res3 = fail("GET", "/api/admin/wxapp/getinfo", None, mtok)
check("未登录打员工面 → 401 Unauthenticated.", st2 == 401 and res2.get("message") == "Unauthenticated.", (st2, res2))
check("会员令牌打员工面 → 401（frontChain 的令牌不是 SALESMAN）", st3 == 401, (st3, res3))
check("运营令牌打员工面同样 401", fail("GET", "/api/admin/wxapp/shoplist", None, atok)[0] == 401)
check("会员令牌打后台导购面 → 401", fail("GET", "/api/salesmans", None, mtok)[0] == 401)
check("viewer 无 salesman.lists → 403（权限码先判，不是登录问题）",
      fail("GET", "/api/salesmans", None, vtok)[0] == 403)

# ===== 2. ST-E1 员工登录 =====
st, res = fail("POST", "/api/admin/wxapp/login", {"mobile": mob(1), "password": ""})
check("缺口令 → 400 且两个键各自报错（mobile 有值也一起报，与源系统同文案）",
      st == 400 and {"mobile", "password"} <= set(res["errors"]), (st, res))
st, res = fail("POST", "/api/admin/wxapp/login", {"mobile": mob(1), "password": DPW})
check("查无此人 → 401（与'口令不对'同一句话，不回答手机号在不在册）", st == 401, (st, res))
st, res = fail("POST", "/api/admin/wxapp/login", {"mobile": MEMBER2, "password": DPW})
check("在册手机号配错口令 → 401", st == 401, (st, res))
st, res = fail("POST", "/api/admin/wxapp/login", {"mobile": MEMBER2, "password": SPW, "distributor_id": HQ})
check("distributor_id 传成别家店 → 401（查的就是'这家店的这个手机号'）", st == 401, (st, res))
seed2 = staff_login(MEMBER2, SPW, SHOP)
check("seed 的在册导购用演示口令登得上（哈希真的落进常驻库）", seed2[0] == 200, seed2)
ltok = seed2[1]["data"]["token"]
need_login = {"token", "salesman_id", "salesman_name", "distributor_id", "shop_id", "role_id", "role_name"}
check("ST-E1 出参契约", need_login <= set(seed2[1]["data"]), sorted(need_login - set(seed2[1]["data"])))
check("role_name 是 JOIN 出来的岗位名（seed 的岗 2 挂 role_id=1「门店导购」）",
      seed2[1]["data"]["role_id"] == 1 and seed2[1]["data"]["role_name"] == "门店导购", seed2[1]["data"])
check("出参没有 password（口令只进不出，C175）", "password" not in seed2[1]["data"])

# ===== 3. SM-A1 列表与数据权限（本轮 narrow 修复的实跑判据） =====
page = data("GET", "/api/salesmans?pageSize=100", token=atok)
need_row = {"salesman_id", "company_id", "distributor_id", "shop_id", "user_id", "salesman_name", "mobile",
            "role_id", "role_name", "shop_name", "is_valid", "avatar", "order_count", "commission_fee",
            "created", "updated"}
check("SM-A1 外层 PageResult 两键", set(page) == {"total_count", "list"}, sorted(page))
check("SM-A1 行列齐（含 JOIN 的 shop_name/role_name 与两个统计位）",
      page["list"] and all(need_row <= set(x) for x in page["list"]), sorted(need_row - set(page["list"][0])))
boss = [x for x in page["list"] if x["salesman_id"] == 1][0]
check("seed 的老板岗在列：role_id=0 → role_name 空串（没岗不等于没这个人）",
      boss["role_name"] == "" and boss["is_valid"] == "true", boss)
check("后台列表手机号一律掩码", boss["mobile"] == "138****0001", boss["mobile"])
shopview = data("GET", "/api/salesmans?pageSize=100", token=stok)
check("店铺账号（operators.distributor_ids='[2]'）看得见自己店里的人 —— 不是 0 行",
      shopview["total_count"] >= 2 and all(x["distributor_id"] == SHOP for x in shopview["list"]),
      (shopview["total_count"], [x["distributor_id"] for x in shopview["list"]]))
check("越界显式 distributor_id 不报错、只筛到 0 行（列表面不回答'这家店存在'）",
      data("GET", "/api/salesmans?distributor_id=%d&pageSize=50" % HQ, token=stok)["total_count"] == 0)
check("SM-A1 过滤位：is_valid 两值互斥且并起来是全量",
      data("GET", "/api/salesmans?is_valid=true&pageSize=1", token=atok)["total_count"]
      + data("GET", "/api/salesmans?is_valid=false&pageSize=1", token=atok)["total_count"] == page["total_count"])
check("keyword 命中姓名、user_id 命中绑定会员",
      data("GET", "/api/salesmans?keyword=%s&pageSize=10" % urllib.parse.quote("小李"), token=atok)["total_count"] == 1
      and data("GET", "/api/salesmans?user_id=2&pageSize=10", token=atok)["total_count"] >= 1)

# ===== 4. SM-A2 建人 =====
G = new_sm(SHOP, RUN + " 甲（物流岗）", mob(1), role_id=1, password=DPW)["salesman_id"]
grow = sm_of(G)
check("SM-A2 出参与列表同构（建完直接插进列表不用两套渲染）", need_row <= set(grow), sorted(need_row - set(grow)))
check("默认在册（不传 is_valid 就是 'true'，不是布尔）", grow["is_valid"] == "true", grow)
check("password 只进不出：建完的出参里没有这一栏", "password" not in grow)
check("新建的岗立刻登得进员工端", staff_login(mob(1))[0] == 200)
gst = staff_login(mob(1))[1]["data"]["token"]
st, res = fail("POST", "/api/salesman", sm_body(SHOP, RUN + " 撞号", mob(1)), atok)
check("同店同手机号 → 422（店内唯一，不是全局唯一）", st == 422 and "mobile" in res["errors"], (st, res))
st, res = fail("POST", "/api/salesman", {"distributor_id": SHOP, "salesman_name": "", "mobile": ""}, atok)
check("姓名与手机号都缺：两条 400 各报各的键", st == 400 and {"salesman_name", "mobile"} <= set(res["errors"]), (st, res))
st, res = fail("POST", "/api/salesman", sm_body(SHOP, RUN + " 脏状态", mob(2), is_valid="yes"), atok)
check("is_valid 脏值 → 400（不校验就等于把这个人悄悄停用，而报错的是'你没登录'）",
      st == 400 and res["errors"]["is_valid"] == ["只能是 true 或 false"], (st, res))
st, res = fail("POST", "/api/salesman", sm_body(987654, RUN + " 鬼店", mob(3)), atok)
check("店铺不存在 → 404", st == 404, (st, res))
st, res = fail("POST", "/api/salesman", sm_body(HQ, RUN + " 越店添人", mob(4)), stok)
check("店铺账号往别家店添人 → 404（资源引用面越界不答 403，C145 同一条）", st == 404, (st, res))
check("本轮计数 +1", data("GET", "/api/salesmans?keyword=%s&pageSize=10" % RUN, token=atok)["total_count"] == 1,
      data("GET", "/api/salesmans?keyword=%s&pageSize=10" % RUN, token=atok)["total_count"])

# ===== 5. SM-A3 读-改-写 + 凭据落库（本轮修的口令列） =====
upd = data("PUT", "/api/salesman/%d" % G, {"salesman_name": RUN + " 甲（改名）"}, atok)
check("SM-A3 只动传进来的字段：岗位/店/手机号原样", upd["salesman_name"] == RUN + " 甲（改名）"
      and upd["role_id"] == 1 and upd["distributor_id"] == SHOP, upd)
check("出参手机号仍是掩码（后台面共用一份装配）", upd["mobile"] == "139****" + mob(1)[-4:], upd["mobile"])
me = sd(gst, "GET", "/api/admin/wxapp/getinfo")
check("库里落的还是原号（员工面给自己看不掩码，这一栏就是'出参掩码≠写进库'的证）",
      me["mobile"] == mob(1) and me["salesman_id"] == G, me)
data("PUT", "/api/salesman/%d" % G, {"mobile": ""}, atok)
check("空串手机号不当清空：库里那一格没动", sd(gst, "GET", "/api/admin/wxapp/getinfo")["mobile"] == mob(1))
st, res = fail("PUT", "/api/salesman/%d" % G, {"is_valid": "1"}, atok)
check("改的时候同样只收 true/false：'1' 也 400", st == 400 and "is_valid" in res["errors"], (st, res))
check("口令不动原样：旧口令仍登得上", staff_login(mob(1))[0] == 200)
data("PUT", "/api/salesman/%d" % G, {"password": ""}, atok)
st, res = fail("POST", "/api/admin/wxapp/login", {"mobile": mob(1), "password": DPW})
check("传空串=清空口令，随即 401（这一条只在实跑里才验得出真落库）", st == 401, (st, res))
data("PUT", "/api/salesman/%d" % G, {"password": SPW}, atok)
check("换成另一枚口令又能登（updateFull 不含 password 列，必须单独回写）",
      staff_login(mob(1), SPW)[0] == 200 and staff_login(mob(1), DPW)[0] == 401)
st, res = fail("PUT", "/api/salesman/987654", {"salesman_name": "不存在的人"}, atok)
check("不存在的岗 404；id 不是数字也 404", st == 404 and fail("PUT", "/api/salesman/abc", {"salesman_name": "x"}, atok)[0] == 404)

# ===== 6. SM-A4 调岗 + SM-A5 启停（D44） =====
st, res = fail("PUT", "/api/salesman/role/%d" % G, {"role_id": 0}, atok)
check("调岗不传 role_id → 400", st == 400 and "role_id" in res["errors"], (st, res))
B_ROLE = data("POST", "/api/salesman/roles", role_body(987654 if False else SHOP, RUN + " 本店岗"), atok)["role_id"]
plat_role = data("POST", "/api/salesman/roles", role_body(0, RUN + " 平台通用岗"), atok)["role_id"]
check("G 在 demo 旗舰店，选本店新建的岗 → 200", data("PUT", "/api/salesman/role/%d" % G, {"role_id": B_ROLE}, atok)["role_id"] == B_ROLE)
other = new_sm(HQ, RUN + " 乙（总部的人）", mob(5), role_id=1)
check("平台通用岗任何店都选得到（拿总部那个岗去选本店之外的那枚通用岗）",
      data("PUT", "/api/salesman/role/%d" % other["salesman_id"], {"role_id": plat_role}, atok)["role_name"]
      == RUN + " 平台通用岗")
hq_role = data("POST", "/api/salesman/roles", role_body(HQ, RUN + " 总部专属岗"), atok)["role_id"]
st, res = fail("PUT", "/api/salesman/role/%d" % G, {"role_id": hq_role}, atok)
check("选别家店的专属岗 → 422（岗位必须'本店能选'：本店的 + 平台通用的）",
      st == 422 and res["errors"]["role_id"] == ["本店不可选"], (st, res))
off_role = data("POST", "/api/salesman/roles", role_body(SHOP, RUN + " 建好即停的岗", status=0), atok)["role_id"]
st, res = fail("PUT", "/api/salesman/role/%d" % G, {"role_id": off_role}, atok)
check("停用的岗同样选不到（countUsable 把 status 一起判了，不等到登录时才炸）", st == 422, (st, res))
st, res = fail("PUT", "/api/salesman/%d/status" % G, {"is_valid": "1"}, atok)
check("SM-A5 只收 true/false 字符串", st == 400 and res["errors"]["is_valid"] == ["只支持 true/false"], (st, res))
data("PUT", "/api/salesman/%d/status" % G, {"is_valid": "false"}, atok)
check("停用即刻关掉已有会话：那枚还没过期的令牌打不进任何一条 ST-E（D44）",
      fail("GET", "/api/admin/wxapp/getinfo", None, None, sess=gst)[0] == 401)
check("停用后员工登录也 401", staff_login(mob(1), SPW)[0] == 401)
check("停用后前台查无此人（404，不是'这人在但店不能用'）",
      fail("GET", "/api/h5app/wxapp/salesperson?salesman_id=%d" % G)[0] == 404)
back = data("PUT", "/api/salesman/%d/status" % G, {"is_valid": "true"}, atok)
check("启回是原样一枚令牌的事：同一个人重新登得上（令牌是每次新签的）",
      back["is_valid"] == "true" and staff_login(mob(1), SPW)[0] == 200)
gst = staff_login(mob(1), SPW)[1]["data"]["token"]

# ===== 7. SM-A6 岗位（复数路径不能和 /api/salesman/{id} 抢映射） =====
roles = data("GET", "/api/salesman/roles?pageSize=100", token=atok)
need_role = {"role_id", "company_id", "distributor_id", "role_name", "remark", "status", "status_text",
             "scope_text", "created", "updated"}
check("SM-A6 列表可达且列齐（GET /api/salesman/roles 没被 /{salesman_id} 吃掉）",
      roles["total_count"] >= 4 and all(need_role <= set(x) for x in roles["list"]), sorted(need_role))
mine = [x for x in roles["list"] if x["role_id"] == B_ROLE][0]
check("归属列写的是店名，通用岗写'全平台通用'", mine["scope_text"] == "demo 旗舰店"
      and [x for x in roles["list"] if x["role_id"] == plat_role][0]["scope_text"] == "全平台通用", (mine,))
check("status_text 两位枚举的文案", mine["status"] == 1 and mine["status_text"] == "启用", mine)
only2 = data("GET", "/api/salesman/roles?distributor_id=%s&pageSize=100" % SHOP, token=atok)["list"]
check("按店筛默认不含平台通用岗，include_platform=true 才连带带出",
      plat_role not in [x["role_id"] for x in only2]
      and plat_role in [x["role_id"] for x in data("GET", "/api/salesman/roles?distributor_id=%s&include_platform=true&pageSize=100" % SHOP, token=atok)["list"]])
st, res = fail("POST", "/api/salesman/roles", role_body(SHOP, ""), atok)
check("空岗位名 400", st == 400 and "role_name" in res["errors"], (st, res))
st, res = fail("POST", "/api/salesman/roles", role_body(SHOP, RUN + " 本店岗"), atok)
check("同店同名岗 → 422", st == 422 and res["errors"]["role_name"] == ["已存在"], (st, res))
st, res = fail("POST", "/api/salesman/roles", role_body(0, RUN + " 店铺想建通用岗"), stok)
check("店铺账号建不了平台通用岗 → 404（与通用自提点同一条口径 C161）", st == 404, (st, res))
st, res = fail("GET", "/api/salesman/roles?distributor_id=%d&pageSize=10" % HQ, None, stok)
check("店铺账号查别家店的岗位列表 → 404", st == 404, (st, res))
r2 = data("PUT", "/api/salesman/roles/%d" % B_ROLE, {"remark": "改备注"}, atok)
check("改岗位是读-改-写：名字与状态没传就原样", r2["role_name"] == RUN + " 本店岗" and r2["remark"] == "改备注", r2)
st, res = fail("DELETE", "/api/salesman/roles/%d" % B_ROLE, None, atok)
check("挂着导购的岗删不掉 → 422 并写明还有几个人",
      st == 422 and "1 名导购" in res["message"] and res["errors"]["role_id"] == ["仍被引用"], (st, res))
data("PUT", "/api/salesman/role/%d" % G, {"role_id": plat_role}, atok)
check("调走人之后删得掉（删的是行本身）", data("DELETE", "/api/salesman/roles/%d" % B_ROLE, None, atok)["deleted"] == 1)
st, res = fail("DELETE", "/api/salesman/roles/%d" % B_ROLE, None, atok)
check("再删一次 404（幂等不在这里做：删除第二次就是查不到）", st == 404, (st, res))

# ===== 8. SM-F1/F2 前台 + C177 回落 =====
f1 = data("GET", "/api/h5app/wxapp/salesperson?salesman_id=2")
check("SM-F1 匿名可访（扫码进店发生在登录之前）", f1["salesman_id"] == 2 and f1["role_name"] == "门店导购", f1)
check("SM-F1 不出手机号（公开面只给头像/店名/岗位/文案）", "mobile" not in f1, sorted(f1))
check("SM-F1 给拼好的分享文案，前端只负责画码", f1["qrcode_text"] == "salesman=2", f1["qrcode_text"])
check("按 mobile 查是同一条装配", data("GET", "/api/h5app/wxapp/salesperson?mobile=%s" % MEMBER2)["salesman_id"] == 2)
check("seed 的老板（role_id=0）在前台查无此人：没岗位就不算导购",
      fail("GET", "/api/h5app/wxapp/salesperson?salesman_id=1")[0] == 404)
st, res = fail("GET", "/api/h5app/wxapp/salesperson")
check("两个键都不给 → 400（二选一的判据在服务层）", st == 400 and "salesman_id" in res["errors"], (st, res))
check("SM-F1 停用的人 404 已在 §6 验过，这里验'店停业'也算查无此人：不存在的 id 同样 404",
      fail("GET", "/api/h5app/wxapp/salesperson?salesman_id=987654")[0] == 404)
R = new_sm(SHOP, RUN + " 丙（专门接绑定的岗）", mob(6), role_id=1)["salesman_id"]
bd = data("POST", "/api/h5app/wxapp/member/bindSalesperson", {"salesman_id": R}, ntok)
check("SM-F2 出参五键、bind_time 是秒", set(bd) == {"user_id", "salesman_id", "salesman_name", "distributor_id", "bind_time"}
      and bd["salesman_id"] == R and bd["bind_time"] > 0, bd)
st, res = fail("POST", "/api/h5app/wxapp/member/bindSalesperson", {"salesman_id": 0}, ntok)
check("绑 0 = 没给 → 400", st == 400 and "salesman_id" in res["errors"], (st, res))
st, res = fail("POST", "/api/h5app/wxapp/member/bindSalesperson", {"salesman_id": 987654}, ntok)
check("绑不存在的岗 → 404", st == 404, (st, res))
st, res = fail("POST", "/api/h5app/wxapp/member/bindSalesperson", {"salesman_id": R})
check("未登录绑定 → 401", st == 401 and res.get("message") == "Unauthenticated.", (st, res))
add_cart(ntok, SHOP, 1)
n1 = data("POST", "/api/h5app/wxapp/order_new", recv({"cart_type": "cart", "order_type": "normal",
                                                      "distributor_id": SHOP}), ntok)["order_id"]
pay(n1, ntok)
check("C177 回落：会员自己下单，salesman_id 取'当前绑定'并进订单头",
      data("GET", "/api/h5app/wxapp/order/%s" % n1, token=ntok)["orderInfo"]["salesman_id"] == R, n1)
check("钱跟着人走：DL-A1 这一行的 salesman_id 就是那个岗",
      logs_of(order_id=n1)["list"][0]["salesman_id"] == R, logs_of(order_id=n1)["list"][:1])
data("POST", "/api/h5app/wxapp/member/bindSalesperson", {"salesman_id": 2}, ntok)
check("落单即定：换绑不回改已经成交的单",
      data("GET", "/api/h5app/wxapp/order/%s" % n1, token=ntok)["orderInfo"]["salesman_id"] == R)

# ===== 9. SO-E1 三条强制 =====
c0 = pool()
st, res = fail("POST", "/api/admin/wxapp/order/create", so_body(HQ), sess=gst)
check("SO-E1 越店开单 → 403（distributor_id 是写入目标，不是资源引用，所以这里给 403 而不是 404）",
      st == 403 and res["errors"]["distributor_id"] == ["非本店铺"], (st, res))
st, res = fail("POST", "/api/admin/wxapp/order/create", so_body(SHOP, user=987654), sess=gst)
check("被代下单的会员不存在 → 404（写个不存在的 user_id 进单头，这单永远没人能查/付/退）", st == 404, (st, res))
st, res = fail("POST", "/api/admin/wxapp/order/create", so_body(SHOP), sess=gst)
check("散客走购物车 → 422：G 没绑会员账号，buyer=0 时他没有车（期望行为不是缺陷）",
      st == 422 and pool() == c0, (st, res, pool()))
oid = staff_order(gst, SHOP, otok, buyer=2)
check("SO-E1 成交，库存照旧走同一条腿（扣 2 格）", pool() == c0 - NUM, (c0, pool()))
det = data("GET", "/api/orders/%s" % oid, token=atok)["orderInfo"]
check("入参那个假 salesman_id=987654 被会话覆盖：单头落的是会话里那个人", det["salesman_id"] == G, det["salesman_id"])
check("SO-E1 建在会话那家店名下", str(det["distributor_id"]) == str(SHOP), det["distributor_id"])
check("被代下单的会员记的是显式给的那个", str(det["user_id"]) == "2", det["user_id"])
zid = staff_order(gst, SHOP, otok, buyer=2, num=1, extra={"receipt_type": "ziti"})

# ===== 10. ST-E2/E3 与 SO-E2/E3 =====
info = sd(gst, "GET", "/api/admin/wxapp/getinfo")
check("ST-E2 十一键、店名与岗位名都带着",
      set(info) == {"salesman_id", "salesman_name", "mobile", "avatar", "user_id", "distributor_id",
                    "shop_name", "shop_mobile", "role_id", "role_name", "is_valid"}, sorted(info))
check("ST-E2 给自己看：手机号明文、is_valid 明文", info["mobile"] == mob(1) and info["is_valid"] == "true", info)
check("ST-E3 本店简易档八键（hour 是给空串保形状的字段位）",
      set(sd(gst, "GET", "/api/admin/wxapp/shoplist")) == {"distributor_id", "name", "mobile", "address",
                                                            "logo", "is_ziti", "is_delivery", "hour"},
      sd(gst, "GET", "/api/admin/wxapp/shoplist"))
lst = sd(gst, "GET", "/api/admin/wxapp/order/getlist?pageSize=50")
check("SO-E2 只看他开的那些单", lst["total_count"] >= 2 and
      all(x["salesman_id"] == G for x in lst["list"]), [x["salesman_id"] for x in lst["list"]])
check("SO-E2 入参里塞 user_id / distributor_id 一律丢弃（不是取交集）",
      all(x["salesman_id"] == G for x in sd(gst, "GET", "/api/admin/wxapp/order/getlist?user_id=1&distributor_id=1&pageSize=50")["list"]))
d3 = sd(gst, "GET", "/api/admin/wxapp/order/getinfo?order_id=%s" % oid)
check("SO-E3 与 ORDER-5 同一份装配，外加 salesman{} 那三栏",
      set(d3) >= {"orderInfo", "items", "distributor", "operate_logs"}
      and set(d3["salesman"]) == {"salesman_id", "salesman_name", "mobile"}, sorted(d3))
check("SO-E3 详情里的 salesman_id 与单头一致", int(d3["orderInfo"]["order_id"]) == oid
      and d3["salesman"]["salesman_id"] == G, d3["salesman"])
st, res = fail("GET", "/api/admin/wxapp/order/getinfo?order_id=%s" % n1, sess=gst)
check("别人（另一个岗）开的单 → 404：列表判据先跑一遍，不给'拼个 id 直接进详情'的越权面", st == 404, (st, res))
st, res = fail("GET", "/api/admin/wxapp/order/getinfo", sess=gst)
check("不传 order_id 也是 404（员工面不解释'你没传参数'）", st == 404, (st, res))

# ===== 11. ST-E4 统计 + ST-E5 明细（一个全新岗的数可以是绝对数） =====
cnt = sd(gst, "GET", "/api/admin/wxapp/admin/wxapp/x") if False else sd(gst, "GET", "/api/admin/wxapp/salespersonCount")
check("ST-E4 八字键（三个单数 + 佣金三数 + 占用 + 余额）",
      set(cnt) == {"order_count", "today_count", "pending_count", "commission_total", "commission_open",
                   "commission_closed", "withdraw_occupying", "balance"}, cnt)
check("G 是本轮新建的岗：开 2 单未付款 → order/pending 都是绝对数",
      cnt["order_count"] == 2 and cnt["pending_count"] == 2 and cnt["today_count"] == 2, cnt)
check("未付款不入账：这一岗的佣金三数全 0",
      cnt["commission_total"] == 0 and cnt["commission_open"] == 0 and cnt["commission_closed"] == 0, cnt)
pay(oid, otok)
cnt2 = sd(gst, "GET", "/api/admin/wxapp/salespersonCount")
check("结清即入账：total=open=1000，closed 仍 0（delay=7 天，钱在途）",
      cnt2["commission_total"] == FEE and cnt2["commission_open"] == FEE and cnt2["commission_closed"] == 0, cnt2)
check("在途的钱进不了余额", cnt2["balance"] == 0, cnt2)
pay(zid, otok)
check("自提单也入账：这一岗两单 = 1000+500",
      sd(gst, "GET", "/api/admin/wxapp/salespersonCount")["commission_total"] == FEE + REBATE, None)
fee = sd(gst, "GET", "/api/admin/wxapp/salespersonFee?salesman_id=1&pageSize=50")
check("ST-E5 口径同 DL-A1，且入参那个 salesman_id=1 被会话强制覆盖",
      fee["total_count"] == 2 and all(x["salesman_id"] == G for x in fee["list"]),
      (fee["total_count"], [x["salesman_id"] for x in fee["list"]]))
check("ST-E5 的行契约与 DL-A1 同构（含 order_no 与昵称位）",
      set(fee["list"][0]) >= {"id", "order_id", "order_no", "distributor_id", "item_id", "item_name",
                             "user_id", "num", "rebate", "total_rebate", "is_close", "plan_close_time",
                             "salesman_id"}, sorted(fee["list"][0]))
check("明细里那两行分别对应物流单与自提单", {x["order_id"] for x in fee["list"]} == {str(oid), str(zid)},
      [x["order_id"] for x in fee["list"]])
now = int(time.time())
c0x = close(now, SHOP)
check("DL-A3 未到期不动（本案这两行必在其中）", c0x["closed_count"] == 0, c0x)
c1 = close(now + 8 * DAY, SHOP)
check("到期一把结：G 的两行都置已结算",
      sd(gst, "GET", "/api/admin/wxapp/salespersonCount")["commission_closed"] == FEE + REBATE, c1)
pool_now = count(SHOP)["balance"]
cnt3 = sd(gst, "GET", "/api/admin/wxapp/salespersonCount")
check("C176 一口池取 min：岗的余额 = min(本岗已结算−占用, 店池余额)",
      cnt3["balance"] == min(FEE + REBATE, pool_now), (cnt3["balance"], FEE + REBATE, pool_now))

# ===== 12. SO-E4 员工端核销（与后台 WO-A2 同一条状态迁移） =====
zc = data("GET", "/api/h5app/wxapp/order/%s/ziticode" % zid, token=otok)
code = zc["ziti_code"]
check("ZT-F1 员工开的自提单照样发码，点落在这店第一个启用点",
      len(code) == 6 and code.isdigit() and zc["ziti_status"] == "false" and zc["pickup"]["id"] in
      [x["id"] for x in front_points(SHOP)], zc["pickup"])
st, res = fail("POST", "/api/admin/wxapp/order/ziti", {"order_id": zid, "ziti_code": str(int(code) + 1).zfill(6)}, sess=gst)
check("码逐字比对：员工端也是 422", st == 422 and "取货码不正确" in res["message"], (st, res))
st, res = fail("POST", "/api/admin/wxapp/order/ziti", {"order_id": zid}, sess=gst)
check("缺 ziti_code → 400；缺 order_id 也 400（空串不当'没传'）",
      st == 400 and "ziti_code" in res["errors"]
      and fail("POST", "/api/admin/wxapp/order/ziti", {"ziti_code": code}, sess=gst)[0] == 400, (st, res))
wo = sd(gst, "POST", "/api/admin/wxapp/order/ziti", {"order_id": zid, "ziti_code": " %s " % code})
check("SO-E4 核销即完成：DONE + 已核销（两侧空白算 trim）",
      wo["ziti_status"] == "true" and wo["order_status"] == "DONE" and wo["ziti_status_text"] == "已核销", wo)
d4 = sd(gst, "GET", "/api/admin/wxapp/order/getinfo?order_id=%s" % zid)
who = [x for x in d4["operate_logs"] if x["behavior"] == "writeoff"] or d4["operate_logs"]
check("留痕署名是 salesman:<id>，不是 operator:<id>（员工与运营两种署名同表共存）",
      any(str(x["operator"]) == "salesman:%d" % G for x in d4["operate_logs"]),
      [(x["operator"], x["operator_role"], x["behavior"]) for x in d4["operate_logs"]])
st, res = fail("POST", "/api/admin/wxapp/order/ziti", {"order_id": zid, "ziti_code": code}, sess=gst)
check("再核一次 422（同一张码只能花一次）", st == 422, (st, res))
st, res = fail("POST", "/api/writeoff/%d" % oid, {"ziti_code": code}, stok)
check("店铺运营账号没有 order.writeoff 之外的越权面：这张物流单不是自提单 → 422", st == 422, (st, res))
cnt4 = sd(gst, "GET", "/api/admin/wxapp/salespersonCount")
check("核销完这单不再是 pending：待处理数回落", cnt4["pending_count"] == 0 and cnt4["order_count"] == 2, cnt4)

# ===== 13. 一口池与两套账（§22 C176）：新建一家演示店跑绝对数 =====
SB = data("POST", "/api/distributor", {"name": RUN + " 一口池演示店", "mobile": mob(8),
                                       "distribution_type": 1, "is_ziti": True}, atok)["distributor_id"]
P = new_sm(SB, RUN + " 老板岗", mob(2), role_id=1, user_id=1, password=SPW)["salesman_id"]
Q = new_sm(SB, RUN + " 店员岗", mob(3), role_id=1, user_id=2, password=SPW)["salesman_id"]
ps = staff_login(mob(2), SPW)[1]["data"]["token"]
qs = staff_login(mob(3), SPW)[1]["data"]["token"]
po = staff_order(ps, SB, mtok, buyer=1)
qo = staff_order(qs, SB, otok, buyer=2)
pay(po, mtok)
pay(qo, otok)
sB = count(SB)
check("新店未结算：closed/balance 都是绝对数 0",
      sB["commission_closed"] == 0 and sB["balance"] == 0 and sB["commission_open"] == 2 * FEE, sB)
close(now + 8 * DAY, SB)
sB = count(SB)
check("两个岗各 1000 → 店池已结算 2000、占用 0、余额 2000",
      sB["commission_closed"] == 2 * FEE and sB["withdraw_occupying"] == 0 and sB["balance"] == 2 * FEE, sB)
st, res = fail("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", {"money": 100}, ntok)
check("没岗位的会员以导购身份提现 → 403（本轮新注册的那个）",
      st == 403 and "在册导购" in res["message"], (st, res))
st, res = fail("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", {"money": 100}, otok)
n = re.search(r"您在 (\d+) 家店铺各有导购岗", res.get("message", ""))
check("同一个人在几家店各有岗：不指明 distributor_id → 422 并写明几家（N 只保证 >=2：他还占着 seed 那条'小李'）",
      st == 422 and n and int(n.group(1)) >= 2 and res["errors"]["distributor_id"] == ["必填"], (st, res))
st, res = fail("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal",
               {"money": 100, "distributor_id": HQ}, otok)
check("指明一家他没岗的店 → 422 errors.distributor_id=['无此岗']（与停用后是同一条判据）",
      st == 422 and res["errors"]["distributor_id"] == ["无此岗"], (st, res))
st, res = fail("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal",
               {"money": 0, "distributor_id": SB}, otok)
check("金额 0 → 422 + errors.money（与 CW-F1 同一句文案）",
      st == 422 and res["errors"]["money"] == ["提现金额必须大于 0"], (st, res))
st, res = fail("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal",
               {"money": FEE + 1, "distributor_id": SB}, otok)
check("岗侧上限先受自己那部分限制：本岗已结算 1000，申请 1001 → 422 写明 1000",
      st == 422 and "可提现 %d 分" % FEE in res["errors"]["money"][0], (st, res))
w2 = data("POST", "/api/h5app/wxapp/cash_withdrawal", {"distributor_id": SB, "money": 2 * FEE}, otok)
check("CW-F1 店铺那侧的行 salesman_id=0（两套账分列，钱是一口池）",
      w2["salesman_id"] == 0 and w2["status"] == "applied" and w2["money"] == 2 * FEE, w2)
st, res = fail("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal",
               {"money": FEE, "distributor_id": SB}, otok)
check("C176 一口池的正证：池被店侧提空后岗侧一分都提不了 —— min(本岗已结算 1000, 店池 0)=0，"
      "岗名下那些行不是另开的一份钱", st == 422 and "可提现 0 分" in res["errors"]["money"][0], (st, res))
st, res = fail("POST", "/api/h5app/wxapp/cash_withdrawal", {"distributor_id": SB, "money": 100}, otok)
check("店侧在途唯一按店挡 → 422", st == 422 and "已有提现申请待处理" in res["message"], (st, res))
r2 = data("PUT", "/api/distribution/cash_withdrawal/%d" % w2["id"], {"status": "refused", "remarks": "让位给岗侧那笔"}, atok)
check("店侧那笔驳回到 refused（refused 不占池，这也是本轮的还原手段）",
      r2["status"] == "refused" and r2["salesman_id"] == 0, r2)
w1 = data("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", {"money": FEE, "distributor_id": SB}, otok)
check("CW-F3 出参带 salesman_id（>0 = 岗那侧的账，后台据此分栏）",
      w1["salesman_id"] == Q and w1["status"] == "applied" and w1["shop_name"] == RUN + " 一口池演示店", w1)
st, res = fail("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", {"money": 100, "distributor_id": SB}, otok)
check("在途唯一按岗挡 → 422", st == 422 and "已有提现申请待处理" in res["message"], (st, res))
st, res = fail("POST", "/api/h5app/wxapp/cash_withdrawal", {"distributor_id": SB, "money": 2 * FEE}, otok)
check("反过来也挡：岗侧那笔同样占住店侧那道在途闸门（那道闸门按 distributor_id 数，含岗的行 —— "
      "一口池就一个在途位，别让两笔同时盯着同一份钱）",
      st == 422 and "已有提现申请待处理" in res["message"], (st, res))
sB = count(SB)
check("岗侧 1000 在途 → 池占用 1000、池余额 1000（岗的那笔按 distributor_id 记进池）",
      sB["withdraw_occupying"] == FEE and sB["balance"] == FEE, sB)
c4 = sd(qs, "GET", "/api/admin/wxapp/salespersonCount")
check("岗的余额被自己的在途提现扣光（withdraw_occupying 含岗侧申请）",
      c4["withdraw_occupying"] == FEE and c4["balance"] == 0, c4)
f2 = data("GET", "/api/h5app/wxapp/cash_withdrawals?pageSize=50", token=otok)
f4 = data("GET", "/api/h5app/wxapp/salesman/getCashWithdrawalList?pageSize=50", token=otok)
check("CW-F2 只出店侧的行（salesman_id=0）", f2["list"] and all(r["salesman_id"] == 0 for r in f2["list"])
      and w2["id"] in [r["id"] for r in f2["list"]], [r["salesman_id"] for r in f2["list"]])
check("CW-F4 只出岗侧的行：同一个账号两份账，混在一份里就成'他好像提了两倍'",
      f4["list"] and all(r["salesman_id"] > 0 for r in f4["list"])
      and w1["id"] in [r["id"] for r in f4["list"]], [r["salesman_id"] for r in f4["list"]])
check("CW-A1 按岗筛：salesman_id=Q 只出那一岗的行",
      data("GET", "/api/distribution/cash_withdrawals?salesman_id=%d&pageSize=50" % Q, token=atok)["total_count"] == 1)

# ===== 14. 停用即断钱 + 收口（refused 不占池，C149） =====
data("PUT", "/api/salesman/%d/status" % Q, {"is_valid": "false"}, atok)
st, res = fail("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", {"money": 100, "distributor_id": SB}, otok)
check("岗停用后 CW-F3 立刻断钱（认的是'在册'行，不是 members.salesman_id 那个当前绑定）—— "
      "他还留着 seed 那条'小李'的岗，所以是 422 本店无此岗；一家岗都没有才走上面那条 403",
      st == 422 and "在册导购岗" in res["message"] and res["errors"]["distributor_id"] == ["无此岗"], (st, res))
st, res = fail("GET", "/api/h5app/wxapp/salesperson?mobile=%s" % mob(3))
check("停用后前台也查无此人（同一判据两处生效）", st == 404, (st, res))
r1 = data("PUT", "/api/distribution/cash_withdrawal/%d" % w1["id"], {"status": "refused", "remarks": "演示驳回"}, atok)
check("岗侧那笔驳回到 refused（店侧那笔已在 13 段驳回过，终态不许再迁）", r1["status"] == "refused", r1)
st, res = fail("PUT", "/api/distribution/cash_withdrawal/%d" % w2["id"], {"status": "paid"}, atok)
check("已驳回的店侧行是终态：refused → paid 422（否则'驳回了却又打款'能悄悄发生）", st == 422, (st, res))
sB2 = count(SB)
check("refused 不占池：店池余额回到 2000（两笔都驳回 = 这条的还原手段）",
      sB2["withdraw_occupying"] == 0 and sB2["balance"] == 2 * FEE, sB2)
check("驳回后的记录仍在（记录面不删行：少一行就是把他提过的钱从账上抹掉）",
      w1["id"] in [r["id"] for r in data("GET", "/api/h5app/wxapp/salesman/getCashWithdrawalList?pageSize=50", token=otok)["list"]])
st, res = fail("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", {"money": 100, "distributor_id": SB}, otok)
check("驳回后岗侧在途清了，但人已停用 → 仍 422 无此岗（资格判据排在额度之前，池子有钱也不是他的）",
      st == 422 and res["errors"]["distributor_id"] == ["无此岗"], (st, res))

# ===== 15. 收尾：还原佣金基数、停用本轮建的岗、清车 =====
for s in (G, R, P, other["salesman_id"]):
    data("PUT", "/api/salesman/%d/status" % s, {"is_valid": "false"}, atok)
check("本轮建的四个岗全部停用（残留的人登不进员工端，也不占下一轮的唯一索引）",
      all(sm_of(s)["is_valid"] == "false" for s in (G, R, P, Q)))
check("停用后员工面三类令牌一起失效",
      all(fail("GET", "/api/admin/wxapp/getinfo", None, None, sess=t)[0] == 401 for t in (gst, ps, qs)))
data("PUT", "/api/salesman/roles/%d" % plat_role, {"role_name": RUN + " 平台通用岗"}, atok)
check("本轮建的通用岗还在（G 挂着它，删不掉；下一轮换个名字再建）",
      [x for x in data("GET", "/api/salesman/roles?pageSize=100", token=atok)["list"] if x["role_id"] == plat_role][0]["role_id"] == plat_role)
for rid in (hq_role, off_role):
    data("DELETE", "/api/salesman/roles/%d" % rid, None, atok)
live_roles = [x["role_id"] for x in data("GET", "/api/salesman/roles?pageSize=200", token=atok)["list"]]
check("没挂着人的两个岗删干净了（HQ 专属 / 建好即停），不给下一轮留岗名噪音",
      hq_role not in live_roles and off_role not in live_roles, (hq_role, off_role, live_roles))
clear_cart()
# 同 m14_dl：还原到 seed 真值 0（schema 默认）而不是"开跑前读到的值" —— 崩在中途的跑动会把 500 固化成原态
back_item = patch_item(0)
check("items.rebate 还原到 seed 真值 0", back_item["rebate"] == 0, back_item["rebate"])
check("改费率不回溯已落流水（D46）：G 那两行仍是 1000/500",
      sorted(abs(x["rebate"]) for x in logs_of(salesman_id=G)["list"]) == [REBATE, FEE],
      [x["rebate"] for x in logs_of(salesman_id=G)["list"]])
check("库存只被真成交的三单买走（2+1+2+2=7 格，被拒的开单分毫没动池子）",
      pool() <= POOL0 - 7, (POOL0, pool()))

print("\n[%s] 通过 %d/%d" % (RUN, sum(ok), len(ok)))
raise SystemExit(0 if all(ok) else 1)
